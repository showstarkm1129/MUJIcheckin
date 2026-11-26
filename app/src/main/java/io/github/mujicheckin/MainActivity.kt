package io.github.mujicheckin

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

/** 権限の設定・動作設定・学習済み店舗の一覧を表示する画面 */
class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var content: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(16)
            setPadding(pad, pad, pad, pad)
        }
        setContentView(ScrollView(this).apply {
            fitsSystemWindows = true
            addView(content)
        })
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        content.removeAllViews()

        section("状態")
        val mujiInstalled = packageManager.getLaunchIntentForPackage(CheckinAccessibilityService.MUJI) != null
        status("MUJIアプリ", mujiInstalled)
        status("ユーザー補助（自動タップ）", isAccessibilityEnabled())
        status("位置情報：常に許可", GeofenceManager.hasPermissions(this))
        status("通知", has(Manifest.permission.POST_NOTIFICATIONS))
        text("今日のチェックイン: " + prefs.storesCheckedInToday().ifEmpty { listOf("まだ") }.joinToString("、"))
        if (prefs.pendingSince != 0L) text("店舗付近：ロック解除待ち")

        section("セットアップ")
        button("1. 位置情報を許可（「常に許可」を選択）") { requestLocation() }
        button("2. 通知を許可") { requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 3) }
        button("3. ユーザー補助を開いて「MUJI自動チェックイン」をON") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        text(
            "※ ONにできない場合: 設定 > アプリ > MUJI自動チェックイン > 右上︙ > 「制限付き設定を許可」",
            small = true
        )
        button("アプリ情報を開く") {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
        }

        section("動作設定")
        toggle("店舗に近づいたらチェックインする", prefs.autoLaunch) { prefs.autoLaunch = it }
        toggle("確認モード: 自動で開かず「チェックインしますか？」通知を出す", prefs.confirmByNotification) {
            prefs.confirmByNotification = it
        }
        toggle("自分でMUJIアプリを開いたときも自動タップ", prefs.assistManualOpen) { prefs.assistManualOpen = it }
        button("今すぐテスト（MUJIアプリを開いてチェックイン）") {
            val service = CheckinAccessibilityService.instance
            if (service == null) toast("ユーザー補助がOFFです") else service.requestAutoCheckin(emptyList(), force = true)
        }

        section("登録店舗（半径${GeofenceManager.RADIUS_M.toInt()}m）")
        text(
            "MUJIアプリでチェックインに成功すると、その場所が自動で登録されます。" +
                "次回からは、今日まだチェックインしていない店舗に近づいたときだけMUJIアプリを開き、" +
                "チェックインできる店舗をすべてチェックインします。",
            small = true
        )
        button("現在地を店舗として登録") { addCurrentLocation() }
        val stores = prefs.stores
        if (stores.isEmpty()) text("（まだありません）")
        stores.forEach { store ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(TextView(this).apply {
                val done = if (prefs.isCheckedInToday(store.name)) "（今日チェックイン済み）" else ""
                text = "${store.name}$done\n%.5f, %.5f".format(store.lat, store.lng)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(Button(this).apply {
                text = "削除"
                setOnClickListener {
                    prefs.removeStore(store.id)
                    GeofenceManager.sync(this@MainActivity)
                    render()
                }
            })
            content.addView(row)
        }
    }

    private fun requestLocation() {
        if (!has(Manifest.permission.ACCESS_FINE_LOCATION)) {
            requestPermissions(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 1
            )
        } else if (!has(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) {
            // Android 11+ では設定画面に遷移して「常に許可」を選ぶ
            requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), 2)
        } else {
            toast("許可済みです")
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1 && has(Manifest.permission.ACCESS_FINE_LOCATION)) {
            requestLocation() // 続けてバックグラウンド位置情報を求める
        }
        GeofenceManager.sync(this)
        render()
    }

    @SuppressLint("MissingPermission")
    private fun addCurrentLocation() {
        if (!has(Manifest.permission.ACCESS_FINE_LOCATION)) {
            toast("先に位置情報を許可してください")
            return
        }
        val input = EditText(this).apply { hint = "店舗名（例: 無印良品 有楽町）" }
        AlertDialog.Builder(this)
            .setTitle("現在地を店舗として登録")
            .setView(input)
            .setPositiveButton("登録") { _, _ ->
                val name = input.text.toString().ifBlank { "店舗" }
                LocationServices.getFusedLocationProviderClient(this)
                    .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                    .addOnSuccessListener { loc ->
                        if (loc == null) {
                            toast("現在地を取得できませんでした")
                        } else {
                            prefs.upsertStore(name, loc.latitude, loc.longitude)
                            GeofenceManager.sync(this)
                            render()
                        }
                    }
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun isAccessibilityEnabled(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val me = ComponentName(this, CheckinAccessibilityService::class.java)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    private fun has(permission: String) = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun section(title: String) {
        content.addView(TextView(this).apply {
            text = title
            textSize = 18f
            setPadding(0, dp(20), 0, dp(6))
        })
    }

    private fun status(label: String, ok: Boolean) = text((if (ok) "✅ " else "❌ ") + label)

    private fun text(s: String, small: Boolean = false) {
        content.addView(TextView(this).apply {
            text = s
            if (small) textSize = 12f
            setPadding(0, dp(2), 0, dp(2))
        })
    }

    private fun button(label: String, onClick: (View) -> Unit) {
        content.addView(Button(this).apply {
            text = label
            isAllCaps = false
            setOnClickListener(onClick)
        })
    }

    @Suppress("DEPRECATION")
    private fun toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
        content.addView(Switch(this).apply {
            text = label
            isChecked = checked
            setPadding(0, dp(8), 0, dp(8))
            setOnCheckedChangeListener { _, v -> onChange(v) }
        })
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
