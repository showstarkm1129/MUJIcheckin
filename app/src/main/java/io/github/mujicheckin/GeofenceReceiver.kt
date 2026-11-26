package io.github.mujicheckin

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent

class GeofenceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError()) {
            Log.w("MujiCheckin", "geofence error ${event.errorCode}")
            return
        }
        val ids = event.triggeringGeofences?.map { it.requestId }.orEmpty()
        when (event.geofenceTransition) {
            Geofence.GEOFENCE_TRANSITION_ENTER -> {
                Log.i("MujiCheckin", "enter $ids")
                onEnter(context, ids)
            }
            Geofence.GEOFENCE_TRANSITION_EXIT -> {
                Log.i("MujiCheckin", "exit $ids")
                val prefs = Prefs(context)
                prefs.pendingStoreIds = prefs.pendingStoreIds - ids.toSet()
                if (prefs.pendingStoreIds.isEmpty()) {
                    prefs.pendingSince = 0L
                    Notifier.cancelNearStore(context)
                }
            }
        }
    }

    companion object {
        /** 店舗に近づいたときの処理（デバッグ用の模擬トリガーからも呼ぶ） */
        fun onEnter(context: Context, storeIds: List<String>, force: Boolean = false) {
            val prefs = Prefs(context)
            if (!force && !prefs.autoLaunch) return
            val unchecked = prefs.uncheckedStores(storeIds)
            if (!force && unchecked.isEmpty()) {
                Log.i("MujiCheckin", "skip: already checked in today")
                return
            }
            val service = CheckinAccessibilityService.instance
            // 実行中に新規登録した店舗のジオフェンス（初回進入）が届いても、今の実行でまとめて押すので何もしない
            if (service?.isAutoRunning == true) return
            if (prefs.confirmByNotification || service == null) {
                // 確認モード、またはユーザー補助がオフ → 通知のボタンから実行してもらう
                Notifier.nearStore(context, storeIds, unchecked.map { it.name })
            } else {
                service.requestAutoCheckin(storeIds, force)
            }
        }
    }
}
