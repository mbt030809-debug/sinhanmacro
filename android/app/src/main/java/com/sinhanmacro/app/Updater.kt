package com.sinhanmacro.app

import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import com.sinhanmacro.core.ReleaseInfo
import com.sinhanmacro.core.ReleaseParser
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * 앱 실행 시 GitHub Releases 의 최신 릴리스(태그 v<번호>)와 현재 versionCode 를 비교해
 * 새 버전이 있으면 다운로드 → SHA-256 검증 → 시스템 설치 화면으로 업데이트.
 */
object Updater {
    const val ACTION_INSTALL_RESULT = "com.sinhanmacro.app.INSTALL_RESULT"

    private var checkedThisLaunch = false

    /** 프로세스당 1회 자동 확인. */
    fun checkOnLaunch(act: Activity) {
        if (checkedThisLaunch) return
        checkedThisLaunch = true
        check(act, manual = false)
    }

    fun check(act: Activity, manual: Boolean) {
        Thread {
            val info = try {
                fetchLatest()
            } catch (e: Exception) {
                Engine.log("업데이트 확인 실패: ${e.message}")
                if (manual) act.runOnUiThread { toast(act, "업데이트 확인 실패: ${e.message}") }
                return@Thread
            }
            act.runOnUiThread {
                if (act.isFinishing) return@runOnUiThread
                if (info != null && ReleaseParser.isNewer(info, BuildConfig.VERSION_CODE)) {
                    showDialog(act, info)
                } else if (manual) {
                    toast(act, "최신 버전입니다 (v${BuildConfig.VERSION_CODE})")
                }
            }
        }.start()
    }

    private fun fetchLatest(): ReleaseInfo? {
        val url = URL("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest")
        val c = url.openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 10_000
            c.readTimeout = 15_000
            c.setRequestProperty("Accept", "application/vnd.github+json")
            c.setRequestProperty("User-Agent", "sinhanmacro-updater")
            return when (val code = c.responseCode) {
                200 -> ReleaseParser.parse(c.inputStream.bufferedReader().use { it.readText() })
                404 -> null // 아직 릴리스 없음
                else -> throw IOException("HTTP $code")
            }
        } finally {
            c.disconnect()
        }
    }

    private fun showDialog(act: Activity, info: ReleaseInfo) {
        Engine.log("새 버전 발견: ${info.tag} (현재 v${BuildConfig.VERSION_CODE})")
        val notes = info.notes.trim().take(300)
        AlertDialog.Builder(act)
            .setTitle("새 버전 ${info.tag} 있음")
            .setMessage(
                "현재 v${BuildConfig.VERSION_CODE} → ${info.tag}\n${info.title}" +
                    (if (notes.isNotEmpty()) "\n\n$notes" else "") +
                    "\n\n지금 업데이트할까요?",
            )
            .setCancelable(false)
            .setPositiveButton("업데이트") { _, _ -> startUpdate(act, info) }
            .setNegativeButton("나중에", null)
            .show()
    }

    private fun startUpdate(act: Activity, info: ReleaseInfo) {
        if (!act.packageManager.canRequestPackageInstalls()) {
            AlertDialog.Builder(act)
                .setTitle("설치 권한 필요")
                .setMessage("'이 출처 허용'을 켠 뒤 돌아와서 다시 업데이트해 주세요.")
                .setPositiveButton("설정 열기") { _, _ ->
                    act.startActivity(
                        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${act.packageName}")),
                    )
                }
                .setNegativeButton("취소", null)
                .show()
            return
        }

        val dialog = AlertDialog.Builder(act)
            .setTitle("업데이트 다운로드")
            .setMessage("0%")
            .setCancelable(false)
            .show()

        Thread {
            try {
                val apk = download(act, info) { pct -> act.runOnUiThread { dialog.setMessage("$pct%") } }
                act.runOnUiThread { dialog.dismiss() }
                install(act, apk)
            } catch (e: Exception) {
                Engine.log("업데이트 실패: ${e.message}")
                act.runOnUiThread {
                    dialog.dismiss()
                    toast(act, "업데이트 실패: ${e.message}")
                }
            }
        }.start()
    }

    private fun download(ctx: Context, info: ReleaseInfo, onProgress: (Int) -> Unit): File {
        val dir = File(ctx.cacheDir, "update").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val dest = File(dir, info.apkName)

        val c = URL(info.apkUrl).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 15_000
            c.readTimeout = 30_000
            c.setRequestProperty("User-Agent", "sinhanmacro-updater")
            if (c.responseCode != 200) throw IOException("다운로드 HTTP ${c.responseCode}")
            val total = if (c.contentLengthLong > 0) c.contentLengthLong else info.size
            val md = MessageDigest.getInstance("SHA-256")
            var done = 0L
            var lastPct = -1
            c.inputStream.use { input ->
                dest.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        md.update(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val pct = (done * 100 / total).toInt().coerceIn(0, 100)
                            if (pct != lastPct) {
                                lastPct = pct
                                onProgress(pct)
                            }
                        }
                    }
                }
            }
            val hex = md.digest().joinToString("") { "%02x".format(it) }
            if (info.sha256 != null && info.sha256 != hex) {
                dest.delete()
                throw IOException("무결성 검증 실패 (SHA-256 불일치)")
            }
            if (dest.length() == 0L) throw IOException("빈 파일")
            Engine.log("다운로드 완료 ${dest.length()}B sha256=$hex")
            return dest
        } finally {
            c.disconnect()
        }
    }

    private fun install(ctx: Context, apk: File) {
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setSize(apk.length())
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            apk.inputStream().use { input ->
                session.openWrite("update.apk", 0, apk.length()).use { out ->
                    input.copyTo(out)
                    session.fsync(out)
                }
            }
            val intent = Intent(ctx, UpdateReceiver::class.java).setAction(ACTION_INSTALL_RESULT)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
            val pending = PendingIntent.getBroadcast(ctx, id, intent, flags)
            session.commit(pending.intentSender)
        }
        Engine.log("설치 세션 커밋 (id=$id) — 시스템 설치 화면에서 확인해 주세요")
    }

    private fun toast(ctx: Context, msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
}
