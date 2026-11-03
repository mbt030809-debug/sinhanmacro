package com.sinhanmacro.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import com.sinhanmacro.core.Device
import com.sinhanmacro.core.DeviceException
import com.sinhanmacro.core.UiNode
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** [Device] 의 안드로이드 구현: 접근성 서비스로 화면 읽기/탭/입력/스크린샷. */
class AccessibilityDevice(private val ctx: Context) : Device {

    private var wakeLock: PowerManager.WakeLock? = null
    private val shotExecutor = Executors.newSingleThreadExecutor()
    private var lastShotAt = 0L

    private fun svc(): TradeAccessibilityService =
        TradeAccessibilityService.instance ?: throw DeviceException("접근성 서비스가 꺼져 있음 (설정에서 켜 주세요)")

    override fun nowMs(): Long = SystemClock.elapsedRealtime()

    override fun sleep(ms: Long) {
        Thread.sleep(ms)
    }

    @Suppress("DEPRECATION")
    override fun wake() {
        finish()
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "sinhanmacro:order",
        ).also { it.acquire(3 * 60_000L) }
    }

    override fun finish() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    override fun launch(pkg: String): Boolean {
        val intent = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: run {
            Engine.log("설치되지 않았거나 실행할 수 없는 앱: $pkg")
            return false
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            svc().startActivity(intent)
            true
        } catch (e: Exception) {
            Engine.log("앱 실행 오류: ${e.message}")
            false
        }
    }

    override fun foregroundPackage(): String? = svc().rootInActiveWindow?.packageName?.toString()

    override fun nodes(): List<UiNode> {
        val s = svc()
        val roots = ArrayList<AccessibilityNodeInfo>()
        s.rootInActiveWindow?.let { roots.add(it) }
        try {
            for (w in s.windows) w.root?.let { roots.add(it) }
        } catch (_: Exception) {
        }
        val out = ArrayList<UiNode>()
        val seenWindows = HashSet<Int>()
        for (r in roots) {
            if (seenWindows.add(r.windowId)) flatten(r, out, 0)
        }
        return out
    }

    private fun flatten(n: AccessibilityNodeInfo, out: MutableList<UiNode>, depth: Int) {
        if (out.size >= 4000 || depth > 60) return
        if (n.isVisibleToUser) {
            val r = Rect()
            n.getBoundsInScreen(r)
            out.add(
                UiNode(
                    text = n.text?.toString() ?: "",
                    id = n.viewIdResourceName ?: "",
                    desc = n.contentDescription?.toString() ?: "",
                    cls = n.className?.toString() ?: "",
                    pkg = n.packageName?.toString() ?: "",
                    left = r.left, top = r.top, right = r.right, bottom = r.bottom,
                    clickable = n.isClickable,
                    enabled = n.isEnabled,
                    handle = n,
                ),
            )
        }
        for (i in 0 until n.childCount) {
            n.getChild(i)?.let { flatten(it, out, depth + 1) }
        }
    }

    // --- 입력 ---
    override fun tap(x: Int, y: Int): Boolean {
        val p = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        return gesture(p, 60)
    }

    override fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, ms: Long): Boolean {
        val p = Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            lineTo(x2.toFloat(), y2.toFloat())
        }
        return gesture(p, ms.coerceAtLeast(50))
    }

    private fun gesture(path: Path, durationMs: Long): Boolean {
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        val latch = CountDownLatch(1)
        val ok = AtomicBoolean(false)
        val dispatched = svc().dispatchGesture(
            g,
            object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    ok.set(true)
                    latch.countDown()
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    latch.countDown()
                }
            },
            null,
        )
        if (!dispatched) return false
        latch.await(5, TimeUnit.SECONDS)
        return ok.get()
    }

    override fun setText(node: UiNode, text: String): Boolean {
        val info = node.handle as? AccessibilityNodeInfo ?: return false
        info.refresh()
        if (setTextOn(info, text)) return true

        // 포커스된 입력창을 다시 찾아서 시도
        info.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        Thread.sleep(300)
        val focused = svc().findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focused != null && setTextOn(focused, text)) return true

        // 마지막 수단: 클립보드 붙여넣기
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("sinhanmacro", text))
        val target = focused ?: info
        return target.performAction(AccessibilityNodeInfo.ACTION_PASTE)
    }

    private fun setTextOn(n: AccessibilityNodeInfo, text: String): Boolean {
        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        return n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    override fun back(): Boolean = svc().performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)

    override fun home(): Boolean = svc().performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)

    // --- 스크린샷 (Android 11+) ---
    override fun screenshot(label: String): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return ""
        return try {
            // takeScreenshot 은 호출 간격 제한이 있음
            val wait = 1200 - (SystemClock.elapsedRealtime() - lastShotAt)
            if (wait > 0) Thread.sleep(wait)
            lastShotAt = SystemClock.elapsedRealtime()
            takeShot(label)
        } catch (e: Exception) {
            Engine.log("스크린샷 실패: ${e.message}")
            ""
        }
    }

    private fun takeShot(label: String): String {
        val dir = (ctx.getExternalFilesDir(null) ?: ctx.filesDir).let { File(it, "shots") }.apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.KOREA)
            .apply { timeZone = TimeZone.getTimeZone("Asia/Seoul") }
            .format(Date())
        val file = File(dir, "${label}_$stamp.png")
        val latch = CountDownLatch(1)
        val ok = AtomicBoolean(false)
        svc().takeScreenshot(
            Display.DEFAULT_DISPLAY,
            shotExecutor,
            object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                    try {
                        val hb = result.hardwareBuffer
                        val hw = Bitmap.wrapHardwareBuffer(hb, result.colorSpace)
                        if (hw != null) {
                            val bmp = hw.copy(Bitmap.Config.ARGB_8888, false)
                            FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.PNG, 90, it) }
                            ok.set(true)
                        }
                        hb.close()
                    } catch (_: Exception) {
                    } finally {
                        latch.countDown()
                    }
                }

                override fun onFailure(errorCode: Int) {
                    latch.countDown()
                }
            },
        )
        latch.await(5, TimeUnit.SECONDS)
        return if (ok.get()) file.absolutePath else ""
    }
}
