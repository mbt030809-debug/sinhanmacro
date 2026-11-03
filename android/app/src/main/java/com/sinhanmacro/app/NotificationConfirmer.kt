package com.sinhanmacro.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import com.sinhanmacro.core.ConfirmRequest
import com.sinhanmacro.core.Confirmer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 주문 제출 직전 사용자 승인: 헤드업 알림의 [승인]/[거부] 버튼 또는 앱 첫 화면 버튼으로 응답.
 * 제한시간 안에 승인하지 않으면 거부와 동일(주문 취소). 주문 스레드는 응답이 올 때까지 대기.
 */
class NotificationConfirmer(private val ctx: Context) : Confirmer {

    class Pending(val id: Int, val summary: String, val deadlineMs: Long) {
        val latch = CountDownLatch(1)
        val approved = AtomicBoolean(false)
    }

    private val nextId = AtomicInteger(1000)

    @Volatile
    private var pending: Pending? = null

    /** 현재 승인 대기 중인 주문 (없으면 null) — 화면 표시용. */
    fun current(): Pending? = pending

    override fun confirm(req: ConfirmRequest): Boolean {
        val p = Pending(
            nextId.incrementAndGet(),
            req.summary,
            System.currentTimeMillis() + req.timeoutSec * 1000L,
        )
        pending = p
        val nm = ctx.getSystemService(NotificationManager::class.java)
        try {
            post(nm, p, req)
        } catch (e: Exception) {
            Engine.log("확인 알림 표시 실패: ${e.message} (앱 첫 화면에서 승인 가능)")
        }
        Engine.log("🔔 주문 확인 대기 (${req.timeoutSec}초): ${req.summary}")
        val answered = try {
            p.latch.await(req.timeoutSec.toLong(), TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            false
        }
        pending = null
        nm.cancel(p.id)
        return answered && p.approved.get()
    }

    /** [ConfirmReceiver] / 화면 버튼에서 호출. 현재 대기 건과 id 가 다르면 무시(지난 알림 방지). */
    fun resolve(id: Int, approved: Boolean) {
        val p = pending ?: return
        if (p.id != id) return
        p.approved.set(approved)
        p.latch.countDown()
    }

    private fun post(nm: NotificationManager, p: Pending, req: ConfirmRequest) {
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "주문 확인", NotificationManager.IMPORTANCE_HIGH),
        )

        fun action(label: String, act: String, iconRes: Int): Notification.Action {
            val i = Intent(ctx, ConfirmReceiver::class.java).setAction(act).putExtra(EXTRA_ID, p.id)
            val code = p.id * 2 + if (act == ACTION_APPROVE) 0 else 1
            val pi = PendingIntent.getBroadcast(
                ctx, code, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            return Notification.Action.Builder(Icon.createWithResource(ctx, iconRes), label, pi).build()
        }

        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val b = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("주문 확인 필요 (${req.timeoutSec}초 내)")
            .setContentText(req.summary)
            .setContentIntent(open)
            .setTimeoutAfter(req.timeoutSec * 1000L)
            .addAction(action("승인", ACTION_APPROVE, android.R.drawable.ic_menu_send))
            .addAction(action("거부", ACTION_REJECT, android.R.drawable.ic_menu_close_clear_cancel))

        // 제출 직전 화면 캡처를 함께 보여줌 (캡처 실패/미지원이면 텍스트만)
        val shot = if (req.shotPath.isNotEmpty()) {
            BitmapFactory.decodeFile(req.shotPath, BitmapFactory.Options().apply { inSampleSize = 2 })
        } else {
            null
        }
        if (shot != null) {
            b.setStyle(Notification.BigPictureStyle().bigPicture(shot).setSummaryText(req.summary))
        } else {
            b.setStyle(Notification.BigTextStyle().bigText(req.summary))
        }
        nm.notify(p.id, b.build())
    }

    companion object {
        const val CHANNEL = "confirm"
        const val ACTION_APPROVE = "com.sinhanmacro.app.CONFIRM_APPROVE"
        const val ACTION_REJECT = "com.sinhanmacro.app.CONFIRM_REJECT"
        const val EXTRA_ID = "id"
    }
}
