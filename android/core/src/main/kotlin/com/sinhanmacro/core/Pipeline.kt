package com.sinhanmacro.core

import java.time.ZonedDateTime

/** 신호 텍스트 → 파싱 → 리스크 검사 → 주문 실행. 한 번에 하나씩(단일 스레드)에서 호출할 것. */
class Pipeline(
    private val cfgProvider: () -> Config,
    private val device: Device,
    private val state: StateRepo,
    private val notify: (String) -> Unit,
    private val killed: () -> Boolean,
    private val log: (String) -> Unit = {},
    private val clock: () -> ZonedDateTime = { nowKst() },
) {
    private val risk = Risk(state)

    /** 신호 1건 처리. 무시/차단/실패 시 null. [execute]=false 면 검사까지만. */
    fun processText(text: String, execute: Boolean = true): Signal? {
        val cfg = cfgProvider()
        val sig = try {
            SignalParser.parse(text, cfg)
        } catch (e: ParseException) {
            log("무시(파싱): ${e.message} | ${text.take(80)}")
            return null
        }

        val now = clock()
        try {
            risk.check(cfg, sig, now, killed())
        } catch (e: RiskException) {
            notify("⛔ 주문 차단: ${e.message} | $sig")
            return null
        }

        if (!execute) {
            log("검사 통과(실행 안 함): $sig")
            return sig
        }

        notify("${if (cfg.dryRun) "[DRY] " else ""}주문 시작: $sig")
        val res = try {
            Executor(device, log).execute(cfg, sig)
        } catch (e: NotLoggedInException) {
            notify("🔒 로그인 필요 — 주문 중단: ${e.message} | $sig")
            return null
        } catch (e: Exception) { // 제출 이전 실패 → 주문 안 나감
            notify("❌ 주문 실패(제출 전): ${e.message} | $sig")
            return null
        }

        if (res.submitted) state.addOrder(sig, now) // 제출했거나 제출 여부 불명 → 중복 방지 위해 기록
        val icon = when (res.status) {
            "ok" -> "✅"
            "unconfirmed" -> "⚠️ 접수 확인 필요"
            else -> "🧪"
        }
        notify("$icon ${res.status}: $sig ${res.shot}")
        return sig
    }
}
