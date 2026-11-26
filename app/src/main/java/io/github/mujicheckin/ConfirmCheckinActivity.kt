package io.github.mujicheckin

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/** 通知の「チェックイン」ボタンの受け口。画面は持たず、自動チェックインを開始して即終了する。 */
class ConfirmCheckinActivity : Activity() {
    companion object {
        const val EXTRA_STORE_IDS = "store_ids"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Notifier.cancelNearStore(this)
        val ids = intent.getStringArrayListExtra(EXTRA_STORE_IDS).orEmpty()
        val service = CheckinAccessibilityService.instance
        if (service != null) {
            service.requestAutoCheckin(ids, force = true)
        } else {
            // ユーザー補助がオフならMUJIアプリを開くだけ
            packageManager.getLaunchIntentForPackage(CheckinAccessibilityService.MUJI)?.let {
                startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}
