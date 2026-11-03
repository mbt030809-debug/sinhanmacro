package com.sinhanmacro.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 주문 확인 알림의 [승인]/[거부] 버튼 처리. */
class ConfirmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra(NotificationConfirmer.EXTRA_ID, -1)
        val approved = intent.action == NotificationConfirmer.ACTION_APPROVE
        Engine.log(if (approved) "주문 승인 버튼 눌림" else "주문 거부 버튼 눌림")
        Engine.confirmer.resolve(id, approved)
    }
}
