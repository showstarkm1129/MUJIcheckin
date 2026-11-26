package io.github.mujicheckin

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

object Notifier {
    private const val CHANNEL = "checkin"
    private const val CHANNEL_NEAR = "near_store"
    private const val ID_RESULT = 1
    private const val ID_NEAR = 2

    private fun manager(context: Context): NotificationManager? {
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return null
        }
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "チェックイン結果", NotificationManager.IMPORTANCE_LOW)
        )
        return nm
    }

    private fun builder(context: Context) =
        Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setAutoCancel(true)

    private fun openMujiIntent(context: Context): PendingIntent? =
        context.packageManager.getLaunchIntentForPackage(CheckinAccessibilityService.MUJI)?.let {
            PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE)
        }

    fun checkedIn(context: Context, store: String) {
        manager(context)?.notify(
            ID_RESULT,
            builder(context).setContentTitle("チェックインしました").setContentText(store).build()
        )
    }

    fun failed(context: Context) {
        manager(context)?.notify(
            ID_RESULT,
            builder(context)
                .setContentTitle("自動チェックインできませんでした")
                .setContentText("タップしてMUJIアプリを開く")
                .setContentIntent(openMujiIntent(context))
                .build()
        )
    }

    /** 「チェックインしますか？」通知。ボタンかタップで ConfirmCheckinActivity → 自動チェックイン */
    fun nearStore(context: Context, storeIds: List<String>, uncheckedNames: List<String>) {
        val nm = manager(context) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_NEAR, "店舗付近の確認", NotificationManager.IMPORTANCE_HIGH)
        )
        val confirm = PendingIntent.getActivity(
            context, 1,
            Intent(context, ConfirmCheckinActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putStringArrayListExtra(ConfirmCheckinActivity.EXTRA_STORE_IDS, ArrayList(storeIds)),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        nm.notify(
            ID_NEAR,
            Notification.Builder(context, CHANNEL_NEAR)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentTitle("無印良品の近くにいます")
                .setContentText(
                    uncheckedNames.takeIf { it.isNotEmpty() }?.joinToString("、", postfix = " に").orEmpty() +
                        "チェックインしますか？"
                )
                .setContentIntent(confirm)
                .addAction(Notification.Action.Builder(null, "チェックイン", confirm).build())
                .setAutoCancel(true)
                .setTimeoutAfter(2 * 60 * 60 * 1000L)
                .build()
        )
    }

    fun cancelNearStore(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(ID_NEAR)
    }
}
