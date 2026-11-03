package com.sinhanmacro.app

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/** 신호앱의 알림을 수신해 [Engine] 으로 전달. */
class SignalListenerService : NotificationListenerService() {

    override fun onListenerConnected() {
        Engine.log("알림 수신 서비스 연결됨")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        try {
            val ex = sbn.notification.extras
            val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
            val big = ex.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            val text = ex.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
            Engine.onNotification(sbn.packageName, sbn.key, sbn.postTime, title, big ?: text)
        } catch (e: Exception) {
            Engine.log("알림 처리 오류: ${e.message}")
        }
    }
}
