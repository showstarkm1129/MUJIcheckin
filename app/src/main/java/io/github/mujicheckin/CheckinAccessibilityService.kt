package io.github.mujicheckin

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.InputMethodManager
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

/**
 * MUJIアプリの画面を監視し、チェックインボタンを自動でタップする。
 *
 * 画面遷移（docs/checkin-flow.md 参照）:
 *   ホームの帯「チェックイン」 → 店舗検索画面の各店舗 `id/check_in` → 「チェックイン済み」
 */
class CheckinAccessibilityService : AccessibilityService() {

    companion object {
        const val MUJI = "net.muji.passport.android"
        private const val TAG = "MujiCheckin"
        private const val ID_SNACKBAR = "$MUJI:id/checkin_snackbar_view"
        private const val ID_HOME_ROOT = "$MUJI:id/from_muji_root_view"
        private const val ID_HOME_TAB = "$MUJI:id/from_muji_fragment"
        private const val ID_SHOP_ROW = "$MUJI:id/check_in_shop"
        private const val ID_SHOP_NAME = "$MUJI:id/shop_name"
        private const val ID_CHECK_IN = "$MUJI:id/check_in"
        private const val LABEL_CHECK_IN = "チェックイン"

        private const val AUTO_TIMEOUT_MS = 30_000L
        private const val PENDING_TTL_MS = 2 * 60 * 60 * 1000L
        private const val TICK_MS = 800L
        /** MUJIアプリが位置を取得して帯を出すまでの猶予 */
        private const val NO_BANNER_GIVE_UP_MS = 12_000L
        private const val BANNER_SUPPRESS_MS = 5 * 60 * 1000L

        @Volatile
        var instance: CheckinAccessibilityService? = null
            private set
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var prefs: Prefs

    /** アプリ外のトリガーでMUJIアプリを開いた（終わったら元の画面に戻す） */
    private var autoRun = false
    val isAutoRunning: Boolean get() = autoRun
    private var autoDeadline = 0L
    private var returnPkg: String? = null
    /** 今回の自動実行のきっかけになった登録店舗 */
    private var targetStoreIds: Set<String> = emptySet()

    /** ボタンを押した直後の確認待ち */
    private var clickedStore: String? = null
    private var clickedAt = 0L
    private var bannerClickedAt = 0L
    private var bannerStoreText: String? = null
    private var tabClickedAt = 0L
    /** 自動実行中にホーム画面を最初に見た時刻（帯が出るまでの待ち時間の起点） */
    private var homeSeenAt = 0L
    /** このサービスが帯を押して店舗検索画面に来た */
    private var navigatedByUs = false
    /** 店舗一覧に押せるボタンがなかった後、帯を押し直さない期限 */
    private var bannerSuppressedUntil = 0L

    private var foregroundPkg: String? = null
    private val launchableCache = HashMap<String, Boolean>()
    /** 既定のホームアプリのみ（設定アプリも FallbackHome で HOME を持つので全件取得は不可） */
    private val homePackages: Set<String> by lazy {
        setOfNotNull(
            packageManager.resolveActivity(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY
            )?.activityInfo?.packageName
        )
    }

    private val tickRunnable = Runnable { tick() }
    private val autoLoop = object : Runnable {
        override fun run() {
            if (!autoRun) return
            if (SystemClock.elapsedRealtime() > autoDeadline) {
                Log.i(TAG, "auto check-in timed out")
                finishAuto(success = false)
                return
            }
            tick()
            handler.postDelayed(this, TICK_MS)
        }
    }

    private val unlockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val since = prefs.pendingSince
            if (since == 0L) return
            if (System.currentTimeMillis() - since > PENDING_TTL_MS) {
                prefs.pendingSince = 0L
                return
            }
            // ロック解除直後は前のアプリの復帰と競合するので少し待つ
            handler.postDelayed({ requestAutoCheckin(prefs.pendingStoreIds) }, 700)
        }
    }

    override fun onServiceConnected() {
        prefs = Prefs(this)
        instance = this
        registerReceiver(unlockReceiver, IntentFilter(Intent.ACTION_USER_PRESENT))
        GeofenceManager.sync(this)
    }

    override fun onDestroy() {
        instance = null
        runCatching { unregisterReceiver(unlockReceiver) }
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        if (pkg != MUJI) {
            // 画面オフ/ロック中のロック画面・AOD表示で戻り先が上書きされないようにする
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
                !getSystemService(KeyguardManager::class.java).isKeyguardLocked &&
                (pkg in homePackages || isLaunchable(pkg))
            ) {
                if (foregroundPkg != pkg) Log.d(TAG, "foreground: $pkg")
                foregroundPkg = pkg
            }
            return
        }
        foregroundPkg = MUJI
        handler.removeCallbacks(tickRunnable)
        handler.postDelayed(tickRunnable, 300)
    }

    /**
     * ジオフェンス進入時・ロック解除時・通知のボタンから呼ばれる。
     * [storeIds] のうち今日まだチェックインしていない店舗があれば、MUJIアプリを開いてチェックインする。
     * [force] は設定や記録を無視して必ず開く（テスト・通知ボタン用）。
     */
    fun requestAutoCheckin(storeIds: Collection<String>, force: Boolean = false) {
        if (!force && !prefs.autoLaunch) return
        if (!force && prefs.uncheckedStores(storeIds).isEmpty()) {
            Log.i(TAG, "all of $storeIds already checked in today")
            clearPending()
            return
        }
        if (autoRun) {
            clearPending() // 実行中なのでロック解除待ちは不要
            return
        }

        val power = getSystemService(PowerManager::class.java)
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (!power.isInteractive || keyguard.isKeyguardLocked) {
            if (prefs.pendingSince == 0L) prefs.pendingSince = System.currentTimeMillis()
            prefs.pendingStoreIds = prefs.pendingStoreIds + storeIds
            Log.i(TAG, "screen locked; waiting for unlock")
            return
        }

        val launch = packageManager.getLaunchIntentForPackage(MUJI) ?: run {
            Log.w(TAG, "MUJI app not installed")
            return
        }
        clearPending()
        targetStoreIds = storeIds.toSet()
        // イベント由来の記録はサービス再接続直後などに欠けるので、今の前面ウィンドウを優先する
        // 取れなければ（アプリウィンドウの root が null のことがある）イベント由来の記録を使う
        // 通知から ConfirmCheckinActivity 経由で来た場合は自アプリが前面なので、その記録は使わない
        windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            ?.root?.packageName?.toString()?.takeIf { it != packageName }?.let { foregroundPkg = it }
        // ホーム画面にいた場合は null（終了時に GLOBAL_ACTION_HOME）
        returnPkg = foregroundPkg?.takeIf { it != MUJI && it != packageName && it !in homePackages }
        resetClickState()
        autoRun = true
        autoDeadline = SystemClock.elapsedRealtime() + AUTO_TIMEOUT_MS
        Log.i(TAG, "launching MUJI app (return to $returnPkg)")
        if (foregroundPkg != MUJI) {
            startActivityFromBackground(launch)
        }
        handler.removeCallbacks(autoLoop)
        handler.postDelayed(autoLoop, TICK_MS)
    }

    private fun tick() {
        val root = topAppRoot() ?: return
        if (root.packageName?.toString() != MUJI) return
        if (!autoRun && !prefs.assistManualOpen) return
        val now = SystemClock.elapsedRealtime()

        // 1. 押したボタンが「チェックイン済み」になったか確認
        clickedStore?.let { name ->
            val btn = findButtonForStore(root, name)
            if (btn != null && btn.text?.contains("済") == true) {
                clickedStore = null
                onCheckedIn(name)
            } else if (now - clickedAt > 20_000) {
                clickedStore = null // 反映されなかったので押し直しを許可
            }
            return
        }

        // 2. 店舗検索画面: チェックインできる店舗を上から順にすべて押す
        val buttons = root.findAccessibilityNodeInfosByViewId(ID_CHECK_IN)
        if (buttons.isNotEmpty()) {
            // 自分で押した分なども含め、「チェックイン済み」の店舗を記録に反映する
            buttons.filter { it.text?.contains("済") == true }
                .mapNotNull { storeNameOf(it) }
                .filterNot { prefs.isCheckedInToday(it) }
                .forEach { prefs.recordCheckin(it) }

            val target = buttons.firstOrNull { it.isEnabled && it.text?.toString() == LABEL_CHECK_IN }
            if (target != null) {
                val name = storeNameOf(target) ?: "不明な店舗"
                Log.i(TAG, "tap check-in: $name")
                target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                clickedStore = name
                clickedAt = now
            } else {
                // もう押すボタンがない。帯が残っていても押し直さない
                bannerSuppressedUntil = now + BANNER_SUPPRESS_MS
                if (autoRun) finishAuto(success = true)
                else if (navigatedByUs) {
                    navigatedByUs = false
                    performGlobalAction(GLOBAL_ACTION_BACK)
                }
            }
            return
        }

        // 3. ホームの帯
        val bar = root.findAccessibilityNodeInfosByViewId(ID_SNACKBAR).firstOrNull()
        val label = bar?.let { findExactText(it, LABEL_CHECK_IN) }
        if (label != null && now < bannerSuppressedUntil) {
            if (autoRun) finishAuto(success = true)
            return
        }
        if (label != null) {
            val clickable = generateSequence(label) { it.parent }.firstOrNull { it.isClickable }
            if (clickable != null && now - bannerClickedAt > 3_000) {
                bannerStoreText = collectTexts(bar).firstOrNull { it != LABEL_CHECK_IN }
                Log.i(TAG, "tap banner ($bannerStoreText)")
                clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                bannerClickedAt = now
                navigatedByUs = true
            }
            return
        }

        // 帯を押したのに店舗検索画面に行かず帯が消えた = 単一店舗で直接チェックインされた（と推定）
        val onHome = root.findAccessibilityNodeInfosByViewId(ID_HOME_ROOT).isNotEmpty()
        if (onHome && bannerClickedAt != 0L && now - bannerClickedAt in 3_000..10_000) {
            bannerClickedAt = 0L
            navigatedByUs = false
            onCheckedIn(bannerStoreText ?: "不明な店舗")
            if (autoRun) handler.postDelayed({ finishAuto(success = true) }, 800)
            return
        }

        // 4. 自動実行中: ホームに帯が出ないまま一定時間たったら、チェックインできる店舗なしとして終了
        if (autoRun && onHome) {
            if (homeSeenAt == 0L) homeSeenAt = now
            if (now - homeSeenAt > NO_BANNER_GIVE_UP_MS) finishAuto(success = true)
        }

        // 5. 自動実行中でホーム以外のタブにいるならホームへ
        if (autoRun && !onHome && now - tabClickedAt > 3_000) {
            root.findAccessibilityNodeInfosByViewId(ID_HOME_TAB).firstOrNull()?.let {
                it.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                tabClickedAt = now
            }
        }
    }

    private fun onCheckedIn(storeName: String) {
        Log.i(TAG, "checked in: $storeName")
        prefs.recordCheckin(storeName)
        Notifier.checkedIn(this, storeName)
        learnStoreLocation(storeName)
        // 残りの店舗は次の tick で続けて押す（ボタンがなくなったら tick 側で終了）
    }

    /**
     * [success] = MUJIアプリ上でチェックインできる店舗が残っていないことを確認できた。
     * false はタイムアウト等で確認できなかった場合。
     */
    private fun finishAuto(success: Boolean) {
        if (!autoRun) return
        autoRun = false
        handler.removeCallbacks(autoLoop)
        resetClickState()
        if (success) {
            // 手動登録の店舗名がMUJIアプリの表記と違っても、今日はもう開かないよう対象店舗も済みにする
            prefs.stores.filter { it.id in targetStoreIds && !prefs.isCheckedInToday(it.name) }
                .forEach { prefs.recordCheckin(it.name) }
        } else {
            Notifier.failed(this)
        }
        targetStoreIds = emptySet()
        val back = returnPkg?.let { packageManager.getLaunchIntentForPackage(it) }
        Log.i(TAG, "auto check-in finished (success=$success, return to ${returnPkg ?: "home"})")
        returnPkg = null
        if (success) Notifier.cancelNearStore(this)
        // 途中でユーザーが別アプリに移っていたら、画面を奪わない
        if (topAppRoot()?.packageName?.toString() != MUJI) return
        if (back != null) {
            startActivityFromBackground(back)
        } else {
            performGlobalAction(GLOBAL_ACTION_HOME)
        }
    }

    /**
     * バックグラウンドからの startActivity は Android 14+ で黙って破棄される。
     * 透明なユーザー補助オーバーレイを一瞬表示して「可視ウィンドウを持つアプリ」として起動する。
     */
    private fun startActivityFromBackground(intent: Intent) {
        val wm = getSystemService(WindowManager::class.java)
        val overlay = View(this)
        val params = WindowManager.LayoutParams(
            1, 1,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
        val added = runCatching { wm.addView(overlay, params) }.isSuccess
        // ウィンドウが表示状態になってから起動する
        handler.postDelayed({
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            handler.postDelayed({ if (added) runCatching { wm.removeView(overlay) } }, 1_000)
        }, 150)
    }

    private fun clearPending() {
        prefs.pendingSince = 0L
        prefs.pendingStoreIds = emptySet()
    }

    private fun resetClickState() {
        clickedStore = null
        bannerClickedAt = 0L
        bannerStoreText = null
        tabClickedAt = 0L
        homeSeenAt = 0L
        navigatedByUs = false
    }

    /** チェックインできた場所 = 店舗の位置として覚え、次回からジオフェンスで検知する */
    @SuppressLint("MissingPermission")
    private fun learnStoreLocation(storeName: String) {
        try {
            LocationServices.getFusedLocationProviderClient(this)
                .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                .addOnSuccessListener { loc ->
                    if (loc != null && prefs.upsertStore(storeName, loc.latitude, loc.longitude)) {
                        Log.i(TAG, "learned store $storeName @ ${loc.latitude},${loc.longitude}")
                        GeofenceManager.sync(this)
                    }
                }
        } catch (e: SecurityException) {
            Log.w(TAG, "no location permission", e)
        }
    }

    /**
     * 最前面のアプリウィンドウのルート。rootInActiveWindow は Galaxy のエッジパネル等の
     * オーバーレイを返すことがあるため、アプリ種別のウィンドウを z順で探す。
     */
    private fun topAppRoot(): AccessibilityNodeInfo? {
        val app = windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        return if (app != null) app.root else rootInActiveWindow
    }

    private fun findButtonForStore(root: AccessibilityNodeInfo, name: String): AccessibilityNodeInfo? =
        root.findAccessibilityNodeInfosByViewId(ID_SHOP_ROW).firstNotNullOfOrNull { row ->
            val rowName = row.findAccessibilityNodeInfosByViewId(ID_SHOP_NAME).firstOrNull()?.text?.toString()
            if (rowName == name) row.findAccessibilityNodeInfosByViewId(ID_CHECK_IN).firstOrNull() else null
        }

    private fun storeNameOf(button: AccessibilityNodeInfo): String? =
        button.parent?.findAccessibilityNodeInfosByViewId(ID_SHOP_NAME)?.firstOrNull()?.text?.toString()

    private fun findExactText(node: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        if (node.text?.toString() == text) return node
        for (i in 0 until node.childCount) {
            val found = node.getChild(i)?.let { findExactText(it, text) }
            if (found != null) return found
        }
        return null
    }

    private fun collectTexts(node: AccessibilityNodeInfo, out: MutableList<String> = mutableListOf()): List<String> {
        node.text?.toString()?.takeIf { it.isNotBlank() }?.let(out::add)
        for (i in 0 until node.childCount) node.getChild(i)?.let { collectTexts(it, out) }
        return out
    }

    /** キーボード等を除外し、ランチャーから開けるアプリだけを「戻り先」として記録する */
    private fun isLaunchable(pkg: String): Boolean =
        pkg != packageName && launchableCache.getOrPut(pkg) {
            // Gboard 等は設定画面のランチャーを持つので、入力方式は明示的に除外する
            val isIme = getSystemService(InputMethodManager::class.java)
                .inputMethodList.any { it.packageName == pkg }
            !isIme && packageManager.getLaunchIntentForPackage(pkg) != null
        }
}
