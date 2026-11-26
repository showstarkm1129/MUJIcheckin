package io.github.mujicheckin

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * ジオフェンス進入の模擬（登録済みの全店舗に入ったことにする）:
 *   adb shell am broadcast -a io.github.mujicheckin.DEBUG_ENTER -n io.github.mujicheckin/.DebugTriggerReceiver [--ez force true]
 */
class DebugTriggerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val force = intent.getBooleanExtra("force", false)
        val ids = Prefs(context).stores.map { it.id }
        Log.i("MujiCheckin", "debug enter $ids (force=$force)")
        GeofenceReceiver.onEnter(context, ids, force)
    }
}
