package com.sinhanmacro.app

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var logView: TextView
    private lateinit var killButton: Button
    private lateinit var confirmBox: LinearLayout
    private lateinit var confirmText: TextView
    private lateinit var signalInput: EditText
    private lateinit var keywordInput: EditText

    private val ticker = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 1500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        Updater.checkOnLaunch(this) // 실행 시 업데이트 확인
    }

    override fun onResume() {
        super.onResume()
        handler.post(ticker)
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    // --- UI ---
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun buildUi(): ScrollView {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(28), dp(14), dp(20))
        }
        fun title(t: String) = TextView(this).apply {
            text = t
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(14), 0, dp(4))
        }
        fun btn(label: String, onClick: () -> Unit) = Button(this).apply {
            text = label
            isAllCaps = false
            setOnClickListener { onClick() }
        }

        col.addView(TextView(this).apply {
            text = "신한매크로 v${BuildConfig.VERSION_NAME}"
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
        })
        status = TextView(this).apply {
            textSize = 13f
            setPadding(0, dp(6), 0, 0)
        }
        col.addView(status)

        // 주문 승인 대기 박스 (승인 알림과 동일 기능 — 알림이 안 보일 때 여기서 승인)
        confirmBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFFFE0B2.toInt())
            setPadding(dp(10), dp(10), dp(10), dp(10))
            visibility = View.GONE
        }
        confirmText = TextView(this).apply {
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
        }
        confirmBox.addView(confirmText)
        val confirmRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        confirmRow.addView(btn("승인") { answerConfirm(true) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        confirmRow.addView(btn("거부") { answerConfirm(false) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        confirmBox.addView(confirmRow)
        col.addView(confirmBox)

        col.addView(title("1. 권한 설정"))
        col.addView(btn("접근성 서비스 설정 열기") { open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) })
        col.addView(btn("알림 접근 설정 열기") { open(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) })
        col.addView(btn("앱 정보 (⋮ → 제한된 설정 허용)") {
            open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        })
        col.addView(btn("배터리 최적화 제외") {
            open(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        })
        col.addView(btn("다른 앱 위에 표시 허용 (앱 실행 안 될 때)") {
            open(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        })

        col.addView(title("2. 설정"))
        col.addView(btn("설정(JSON) 편집") { startActivity(Intent(this, ConfigActivity::class.java)) })
        killButton = btn("") {
            Engine.setKilled(!Engine.isKilled())
            render()
        }
        col.addView(killButton)
        col.addView(btn("업데이트 확인") { Updater.check(this, manual = true) })

        col.addView(title("3. 도구"))
        keywordInput = EditText(this).apply {
            hint = "앱 이름/패키지 검색어 (예: 신한)"
            inputType = InputType.TYPE_CLASS_TEXT
        }
        col.addView(keywordInput)
        col.addView(btn("설치된 앱 패키지명 검색") { searchPackages() })
        col.addView(btn("주문 승인 알림 테스트 (실제 주문 아님)") { Engine.testConfirm() })
        col.addView(btn("5초 뒤 현재 화면 요소 덤프 (신한앱으로 이동)") {
            Toast.makeText(this, "5초 안에 덤프할 화면으로 이동하세요", Toast.LENGTH_LONG).show()
            handler.postDelayed({
                Engine.dumpScreen { text ->
                    Engine.log("--- 화면 요소 덤프 ---\n$text")
                    runOnUiThread { copyToClipboard("dump", text) }
                }
            }, 5000)
        })

        col.addView(title("4. 신호 테스트"))
        signalInput = EditText(this).apply {
            hint = "예: 삼성전자 매수 1주"
            inputType = InputType.TYPE_CLASS_TEXT
        }
        col.addView(signalInput)
        col.addView(btn("검사만 (파싱 + 리스크)") { submit(execute = false) })
        col.addView(btn("실행 (dry_run 설정에 따름)") { submit(execute = true) })

        col.addView(title("로그"))
        col.addView(btn("로그 복사") { copyToClipboard("log", Engine.logText()) })
        logView = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextIsSelectable(true)
        }
        col.addView(logView, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        return ScrollView(this).apply { addView(col) }
    }

    private fun open(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "열 수 없음: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun answerConfirm(approved: Boolean) {
        val p = Engine.confirmer.current() ?: return
        Engine.log(if (approved) "주문 승인 (앱 화면)" else "주문 거부 (앱 화면)")
        Engine.confirmer.resolve(p.id, approved)
    }

    private fun submit(execute: Boolean) {
        val text = signalInput.text.toString().trim()
        if (text.isEmpty()) {
            Toast.makeText(this, "신호 텍스트를 입력하세요", Toast.LENGTH_SHORT).show()
            return
        }
        Engine.log("수동 신호: $text (execute=$execute)")
        Engine.submit(text, execute)
    }

    private fun searchPackages() {
        val kw = keywordInput.text.toString().trim().lowercase()
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val lines = pm.queryIntentActivities(intent, 0)
            .map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
            .filter { kw.isEmpty() || it.first.lowercase().contains(kw) || it.second.lowercase().contains(kw) }
            .sortedBy { it.first }
            .joinToString("\n") { "${it.first} → ${it.second}" }
        Engine.log("--- 앱 검색 '$kw' ---\n${lines.ifEmpty { "(없음)" }}")
    }

    private fun copyToClipboard(label: String, text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(this, "클립보드에 복사됨", Toast.LENGTH_SHORT).show()
    }

    // --- 상태 표시 ---
    private fun accessibilityOn() = TradeAccessibilityService.instance != null

    private fun listenerOn(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false
        return enabled.split(":").any { it.startsWith("$packageName/") }
    }

    private fun render() {
        val cfg = Engine.config()
        fun ok(b: Boolean) = if (b) "✅ 켜짐" else "❌ 꺼짐"
        status.text = buildString {
            appendLine("접근성 서비스: ${ok(accessibilityOn())}")
            appendLine("알림 접근(신호 수신): ${ok(listenerOn())}")
            appendLine("설치 허용: ${ok(packageManager.canRequestPackageInstalls())}")
            appendLine("모드: ${if (cfg.dryRun) "🧪 DRY RUN (제출 직전 중단)" else "⚠️ 실전 주문"}")
            appendLine("킬스위치: ${if (Engine.isKilled()) "🛑 ON (주문 차단)" else "OFF"}")
            appendLine(
                "제출 전 승인: " + if (cfg.confirmRequired) "✅ 필요 (${cfg.confirmTimeoutSec}초 내)" else "⚠️ 꺼짐 (자동 제출)",
            )
            appendLine(
                "주문유형: 기본 ${cfg.orderTypeLabel(cfg.defaultOrderType)} / 허용 " +
                    cfg.allowedOrderTypes.joinToString { cfg.orderTypeLabel(it) },
            )
            appendLine("신한앱: ${cfg.appPackage.ifEmpty { "(미설정)" }}")
            appendLine("신호앱: ${cfg.sourcePackages.ifEmpty { listOf("(미설정)") }}")
            append("허용 티커(정확히 일치): ${cfg.symbols.keys.ifEmpty { setOf("(없음)") }}")
        }
        val pending = Engine.confirmer.current()
        if (pending != null) {
            val left = ((pending.deadlineMs - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
            confirmText.text = "주문 승인 대기 (${left}초 남음)\n${pending.summary}"
            confirmBox.visibility = View.VISIBLE
        } else {
            confirmBox.visibility = View.GONE
        }
        killButton.text = if (Engine.isKilled()) "킬스위치 해제 (주문 재개)" else "킬스위치 켜기 (모든 주문 차단)"
        val text = Engine.logText()
        if (logView.text.toString() != text) logView.text = text
    }
}
