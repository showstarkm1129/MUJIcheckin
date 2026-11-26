package io.github.mujicheckin

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 再起動・アプリ更新でジオフェンスが消えるので登録し直す */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        GeofenceManager.sync(context)
    }
}
