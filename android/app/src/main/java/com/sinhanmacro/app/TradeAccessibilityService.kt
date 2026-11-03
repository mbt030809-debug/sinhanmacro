package com.sinhanmacro.app

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent

/** 증권앱 화면 읽기/탭/입력을 대신 수행하는 접근성 서비스 (실제 동작은 [AccessibilityDevice]). */
class TradeAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        instance = this
        Engine.log("접근성 서비스 연결됨")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        Engine.log("접근성 서비스 해제됨")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    companion object {
        @Volatile
        var instance: TradeAccessibilityService? = null
    }
}
