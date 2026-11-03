package com.sinhanmacro.app

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.util.Log
import com.sinhanmacro.core.Config
import com.sinhanmacro.core.ConfigException
import com.sinhanmacro.core.Pipeline
import com.sinhanmacro.core.StateRepo
import com.sinhanmacro.core.StateStore
import com.sinhanmacro.core.UiNode
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors

private class FileStateStore(private val file: File) : StateStore {
    override fun read(): String? = if (file.exists()) file.readText() else null

    override fun write(text: String) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) file.writeText(text)
    }
}

/** 앱 전역 싱글턴: 설정/상태/로그/알림 + 신호 처리 파이프라인. */
object Engine {
    private lateinit var app: Application
    private lateinit var prefs: android.content.SharedPreferences

    // 주문 처리는 반드시 단일 스레드 (동시 주문 방지)
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "macro-worker") }
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "macro-io") }

    private val logLines = ArrayDeque<String>()

    @Volatile
    private var cfg: Config? = null
    private var cfgMtime = -1L

    lateinit var state: StateRepo
        private set
    lateinit var device: AccessibilityDevice
        private set
    lateinit var confirmer: NotificationConfirmer
        private set
    private lateinit var pipeline: Pipeline

    fun attach(a: Application) {
        app = a
        prefs = a.getSharedPreferences("macro", Context.MODE_PRIVATE)
        state = StateRepo(FileStateStore(File(a.filesDir, "state.json")))
        device = AccessibilityDevice(a)
        confirmer = NotificationConfirmer(a)
        pipeline = Pipeline(
            cfgProvider = { config() },
            device = device,
            state = state,
            notify = { notify(it) },
            killed = { isKilled() },
            confirmer = confirmer,
            log = { log(it) },
        )
        log("앱 시작 v${BuildConfig.VERSION_NAME} (code ${BuildConfig.VERSION_CODE})")
    }

    // --- 설정 ---
    private fun configFile() = File(app.filesDir, "config.json")

    fun configText(): String {
        val f = configFile()
        if (!f.exists()) f.writeText(exampleConfigText())
        return f.readText()
    }

    fun exampleConfigText(): String = app.assets.open("config.example.json").bufferedReader().use { it.readText() }

    /** 저장 성공이면 null, 실패면 오류 메시지. */
    fun saveConfigText(text: String): String? = try {
        Config.parse(text)
        configFile().writeText(text)
        synchronized(this) { cfg = null }
        log("설정 저장됨")
        null
    } catch (e: ConfigException) {
        e.message ?: "설정 오류"
    }

    @Synchronized
    fun config(): Config {
        val f = configFile()
        val cur = cfg
        if (cur != null && f.lastModified() == cfgMtime) return cur
        cfgMtime = f.lastModified()
        return try {
            Config.parse(configText()).also { cfg = it }
        } catch (e: ConfigException) {
            log("설정 오류(이전 설정 유지): ${e.message}")
            cfg ?: Config.parse("{}").also { cfg = it } // 기본값: 허용 종목 없음 → 매매 안 함
        }
    }

    // --- 킬스위치 ---
    fun isKilled(): Boolean = prefs.getBoolean("killed", false)

    fun setKilled(v: Boolean) {
        prefs.edit().putBoolean("killed", v).apply()
        notify(if (v) "🛑 킬스위치 ON — 모든 주문 차단" else "킬스위치 OFF")
    }

    // --- 로그 / 알림 ---
    private fun kst(pattern: String) = SimpleDateFormat(pattern, Locale.KOREA).apply {
        timeZone = TimeZone.getTimeZone("Asia/Seoul")
    }

    fun log(msg: String) {
        val now = Date()
        val line = "${kst("MM-dd HH:mm:ss").format(now)} $msg"
        synchronized(logLines) {
            logLines.addLast(line)
            while (logLines.size > 400) logLines.removeFirst()
        }
        Log.i("sinhanmacro", msg)
        val day = kst("yyyyMMdd").format(now)
        io.execute {
            try {
                val dir = File(app.filesDir, "logs").apply { mkdirs() }
                File(dir, "sinhanmacro_$day.log").appendText(line + "\n")
            } catch (_: Exception) {
            }
        }
    }

    fun logText(): String = synchronized(logLines) { logLines.joinToString("\n") }

    fun notify(msg: String) {
        log(msg)
        try {
            val nm = app.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel("alerts", "자동매매 알림", NotificationManager.IMPORTANCE_DEFAULT))
            val n = Notification.Builder(app, "alerts")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("신한매크로")
                .setContentText(msg)
                .setStyle(Notification.BigTextStyle().bigText(msg))
                .setAutoCancel(true)
                .build()
            nm.notify((System.currentTimeMillis() % Int.MAX_VALUE).toInt(), n)
        } catch (_: Exception) {
        }
        sendTelegram(msg)
    }

    private fun sendTelegram(msg: String) {
        val c = try {
            config()
        } catch (_: Exception) {
            return
        }
        val token = c.telegramToken
        val chat = c.telegramChatId
        if (token.isEmpty() || chat.isEmpty()) return
        io.execute {
            try {
                val body = "chat_id=${URLEncoder.encode(chat, "UTF-8")}&text=${URLEncoder.encode(msg, "UTF-8")}"
                val conn = URL("https://api.telegram.org/bot$token/sendMessage").openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.connectTimeout = 10_000
                conn.readTimeout = 10_000
                conn.outputStream.use { it.write(body.toByteArray()) }
                conn.responseCode
                conn.disconnect()
            } catch (e: Exception) {
                Log.w("sinhanmacro", "텔레그램 전송 실패: ${e.message}")
            }
        }
    }

    // --- 신호 ---
    fun onNotification(pkg: String, key: String, postTime: Long, title: String, body: String) {
        val c = config()
        if (pkg !in c.sourcePackages) return
        if (c.titleRegex?.containsMatchIn(title) == false) return
        val id = sha1("$pkg|$key|$postTime|$title|$body")
        if (state.hasSeen(id)) return
        state.markSeen(id)
        val text = "$title $body".trim()
        log("신호 수신 [$pkg]: ${text.take(100)}")
        submit(text, execute = true)
    }

    fun submit(text: String, execute: Boolean) {
        worker.execute {
            try {
                pipeline.processText(text, execute)
            } catch (e: Exception) {
                notify("❌ 처리 오류: ${e.javaClass.simpleName} ${e.message}")
            }
        }
    }

    /** 승인 알림이 제대로 뜨는지 확인하는 테스트 (주문과 무관). */
    fun testConfirm() {
        worker.execute {
            val ok = confirmer.confirm(com.sinhanmacro.core.ConfirmRequest("테스트: 매수 TMV 1주 지정가 @1.00 (실제 주문 아님)", "", 30))
            log(if (ok) "확인 테스트: 승인됨" else "확인 테스트: 거부 또는 시간초과")
        }
    }

    /** 현재 화면의 요소 목록 (flows 작성용). */
    fun dumpScreen(callback: (String) -> Unit) {
        worker.execute {
            val text = try {
                formatNodes(device.nodes())
            } catch (e: Exception) {
                "덤프 실패: ${e.message}"
            }
            callback(text)
        }
    }

    private fun formatNodes(nodes: List<UiNode>): String {
        val own = app.packageName
        val lines = nodes.filter { it.pkg != own && (it.text.isNotEmpty() || it.desc.isNotEmpty() || it.id.isNotEmpty()) }
            .map { n ->
                val tap = if (n.clickable) "탭" else "  "
                "[$tap] pkg=${n.pkg} text='${n.text}' id='${n.id}' desc='${n.desc}' cls=${n.cls.substringAfterLast('.')} center=(${n.centerX},${n.centerY})"
            }
        return if (lines.isEmpty()) "(요소 없음 — 접근성 서비스가 꺼져 있거나 보안화면)" else lines.joinToString("\n")
    }

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
