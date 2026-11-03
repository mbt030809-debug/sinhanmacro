package com.sinhanmacro.core

import org.json.JSONObject

class StepException(message: String) : Exception(message)

class NotLoggedInException(message: String) : Exception(message)

class DeviceException(message: String) : Exception(message)

/** 기기 제어 추상화. 안드로이드에서는 접근성 서비스가 구현, 테스트에서는 가짜 기기 사용. */
interface Device {
    fun nowMs(): Long
    fun sleep(ms: Long)
    fun wake()
    fun launch(pkg: String): Boolean
    fun foregroundPackage(): String?
    fun nodes(): List<UiNode>
    fun tap(x: Int, y: Int): Boolean
    fun setText(node: UiNode, text: String): Boolean
    fun back(): Boolean
    fun home(): Boolean
    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, ms: Long): Boolean

    /** 스크린샷 저장 후 파일 경로 (실패/미지원이면 빈 문자열). */
    fun screenshot(label: String): String

    /** 주문 흐름 종료 시 호출 (웨이크락 해제 등). */
    fun finish()
}

data class ConfirmRequest(val summary: String, val shotPath: String, val timeoutSec: Int)

/** 주문 제출 직전 사용자 승인. 승인=true, 거부/시간초과=false. 응답이 올 때까지(최대 timeoutSec) 블록. */
interface Confirmer {
    fun confirm(req: ConfirmRequest): Boolean
}

/** status: ok | unconfirmed | dry_run | cancelled */
data class ExecResult(val status: String, val submitted: Boolean, val shot: String = "")

/**
 * config.flows 의 step 을 순서대로 실행해 신한앱에서 주문을 낸다.
 *
 * step(do): tap / tap_xy / type / wait / wait_for / key(BACK|HOME) / swipe / shot
 * 공통 옵션: "when": 주문유형 키 | "priced"(시장가 제외) | "always" | 배열,
 *            "final": true(실제 제출 단계), "optional": true(요소 없으면 건너뜀), "timeout": 초
 * 템플릿 변수: {ticker} {ticker_re}(정규식용) {name} {exchange} {qty} {price} {stop_price}
 *              {side} {order_type} {order_type_label}(앱 화면의 주문유형 문구)
 *
 * 제출 직전 순서: flows.<side>.verify 전부 통과 → 스크린샷 → (dry_run 이면 여기서 중단)
 *                → 사용자 승인(confirm.required) → verify 재검증 → final step 실행
 */
class Executor(
    private val device: Device,
    private val confirmer: Confirmer? = null,
    private val log: (String) -> Unit = {},
) {

    fun execute(cfg: Config, sig: Signal): ExecResult {
        val flow = cfg.flow(sig.side)
        if (flow == null || flow.steps.isEmpty()) throw StepException("config.flows.${sig.side}.steps 가 비어 있음")
        if (flow.steps.none { it.optBoolean("final", false) }) {
            throw StepException("flow 에 final:true 단계가 없음 (주문 제출 버튼 지정 필요)")
        }
        if (cfg.requireVerify && flow.verify.none { it.toString().contains("{ticker") }) {
            throw StepException("flows.${sig.side}.verify 에 티커 검증이 없음")
        }
        val pkg = cfg.appPackage
        if (pkg.isEmpty()) throw StepException("app.package 미설정")
        val pkgs = setOf(pkg) + cfg.extraPackages
        val sym = cfg.symbols[sig.ticker] ?: throw StepException("허용되지 않은 티커: ${sig.ticker}")
        val dry = cfg.dryRun
        val confirmNeeded = cfg.confirmRequired && !dry
        if (confirmNeeded && confirmer == null) throw StepException("주문 확인 수단이 없음 (confirm.required=true)")
        val type = sig.orderType
        val vals = mapOf(
            "ticker" to sig.ticker,
            "ticker_re" to sig.ticker.replace(".", "\\."),
            "name" to sym.name,
            "exchange" to sym.exchange,
            "qty" to sig.qty.toString(),
            "price" to (sig.price?.toPlainString() ?: ""),
            "stop_price" to (sig.stopPrice?.toPlainString() ?: ""),
            "side" to sig.side,
            "order_type" to type.key,
            "order_type_label" to cfg.orderTypeLabel(type),
        )

        try {
            device.wake()
            if (!device.launch(pkg)) throw StepException("앱 실행 실패: $pkg")
            device.sleep((cfg.launchWaitSec * 1000).toLong())
            ensureForeground(pkgs)

            cfg.loginCheck?.let {
                if (waitNode(pkgs, fmtSel(it, vals), 10_000) == null) {
                    throw NotLoggedInException("미로그인/로그인 화면으로 보임 (login_check 요소 없음)")
                }
            }

            for ((i, step) in flow.steps.withIndex()) {
                if (!whenMatches(step.opt("when"), type)) continue

                if (step.optBoolean("final", false)) {
                    verifyScreen(flow, vals, pkgs, type)
                    val before = device.screenshot("before_confirm_${sig.side}_${sig.ticker}")
                    if (dry) {
                        log("[DRY RUN] 최종 제출 직전 중단 $before")
                        return ExecResult("dry_run", false, before)
                    }
                    if (confirmNeeded) {
                        val approved = confirmer!!.confirm(ConfirmRequest(sig.toString(), before, cfg.confirmTimeoutSec))
                        if (!approved) {
                            log("사용자 미승인/시간초과 — 주문 취소")
                            return ExecResult("cancelled", false, before)
                        }
                        // 대기 중 화면이 바뀌지 않았는지 재검증
                        ensureForeground(pkgs)
                        verifyScreen(flow, vals, pkgs, type)
                    }
                    runStep(step, vals, pkgs)
                    return afterSubmit(flow, pkgs, vals, sig)
                }
                runStep(step, vals, pkgs)
                if (step.getString("do") == "shot") {
                    device.screenshot("${step.optString("name", "step$i")}_${sig.side}_${sig.ticker}")
                }
            }
            throw StepException("final 단계에 도달하지 못함")
        } finally {
            device.finish()
        }
    }

    /** 주문 직전 화면에 의도한 티커/수량/가격 등이 실제로 표시되는지 확인 (하나라도 없으면 중단). */
    private fun verifyScreen(flow: Flow, vals: Map<String, String>, pkgs: Set<String>, type: OrderType) {
        for (v in flow.verify) {
            if (!whenMatches(v.opt("when"), type)) continue
            val sel = fmtSel(v.getJSONObject("sel"), vals)
            if (waitNode(pkgs, sel, 5_000) == null) {
                throw StepException("주문 전 화면 검증 실패 [${v.optString("label", "verify")}]: $sel")
            }
        }
    }

    /** 제출 이후 오류는 '주문이 나갔을 수 있음' → submitted=true 로 반환(중복 주문 방지). */
    private fun afterSubmit(flow: Flow, pkgs: Set<String>, vals: Map<String, String>, sig: Signal): ExecResult {
        return try {
            val ok = flow.success?.let { waitNode(pkgs, fmtSel(it, vals), 10_000) != null } ?: true
            ExecResult(if (ok) "ok" else "unconfirmed", true, device.screenshot("after_submit_${sig.side}_${sig.ticker}"))
        } catch (e: Exception) {
            log("제출 후 확인 중 오류: ${e.message}")
            ExecResult("unconfirmed", true, "")
        }
    }

    // --- 도우미 ---
    private fun ensureForeground(pkgs: Set<String>) {
        val deadline = device.nowMs() + 8_000
        while (true) {
            val fg = device.foregroundPackage()
            if (fg != null && fg in pkgs) return
            if (device.nowMs() >= deadline) throw StepException("대상 앱이 전면에 없음 (현재: $fg)")
            device.sleep(500)
        }
    }

    private fun appNodes(pkgs: Set<String>): List<UiNode> = device.nodes().filter { it.pkg in pkgs }

    private fun waitNode(pkgs: Set<String>, sel: JSONObject, timeoutMs: Long): UiNode? {
        val deadline = device.nowMs() + timeoutMs
        while (true) {
            Selector.find(appNodes(pkgs), sel)?.let { return it }
            if (device.nowMs() >= deadline) return null
            device.sleep(800)
        }
    }

    // {name} 형태만 치환. {2} 같은 정규식 수량자(숫자)는 그대로 둠. 알 수 없는 변수명은 오류.
    private fun fmt(s: String, vals: Map<String, String>): String =
        Regex("\\{([A-Za-z_]\\w*)}").replace(s) { m ->
            vals[m.groupValues[1]] ?: throw StepException("알 수 없는 변수: {${m.groupValues[1]}}")
        }

    private fun fmtSel(sel: JSONObject, vals: Map<String, String>): JSONObject {
        val out = JSONObject()
        for (k in sel.keys()) {
            val v = sel.get(k)
            out.put(k, if (v is String) fmt(v, vals) else v)
        }
        return out
    }

    private fun need(step: JSONObject, vals: Map<String, String>, pkgs: Set<String>): UiNode? {
        val sel = fmtSel(step.getJSONObject("sel"), vals)
        val timeout = (step.optDouble("timeout", 8.0) * 1000).toLong()
        val node = waitNode(pkgs, sel, timeout)
        if (node == null && !step.optBoolean("optional", false)) throw StepException("요소를 찾지 못함: $sel")
        return node
    }

    private fun runStep(step: JSONObject, vals: Map<String, String>, pkgs: Set<String>) {
        when (val kind = step.getString("do")) {
            "tap" -> need(step, vals, pkgs)?.let { tapNode(it) }
            "tap_xy" -> {
                ensureForeground(pkgs)
                val xy = step.getJSONArray("xy")
                if (!device.tap(xy.getInt(0), xy.getInt(1))) throw StepException("탭 실패(좌표)")
            }
            "type" -> {
                if (!step.has("sel")) throw StepException("type step 에는 sel 이 필요함")
                val node = need(step, vals, pkgs) ?: return
                tapNode(node)
                device.sleep(300)
                var text = fmt(step.getString("text"), vals)
                if (!step.optBoolean("clear", true)) text = node.text + text
                if (!device.setText(node, text)) throw StepException("텍스트 입력 실패: '$text'")
            }
            "wait" -> device.sleep((step.optDouble("sec", 1.0) * 1000).toLong())
            "wait_for" -> {
                val sel = fmtSel(step.getJSONObject("sel"), vals)
                if (waitNode(pkgs, sel, (step.optDouble("timeout", 10.0) * 1000).toLong()) == null) {
                    throw StepException("화면 대기 실패: $sel")
                }
            }
            "key" -> {
                val ok = when (step.getString("code").removePrefix("KEYCODE_").uppercase()) {
                    "BACK" -> device.back()
                    "HOME" -> device.home()
                    else -> throw StepException("지원하지 않는 key: ${step.getString("code")} (BACK|HOME)")
                }
                if (!ok) throw StepException("key 실패")
            }
            "swipe" -> {
                val f = step.getJSONArray("from")
                val t = step.getJSONArray("to")
                if (!device.swipe(f.getInt(0), f.getInt(1), t.getInt(0), t.getInt(1), step.optLong("ms", 300))) {
                    throw StepException("스와이프 실패")
                }
            }
            "shot" -> Unit // execute() 에서 처리
            else -> throw StepException("알 수 없는 step: $kind")
        }
    }

    private fun tapNode(n: UiNode) {
        if (!device.tap(n.centerX, n.centerY)) throw StepException("탭 실패: $n")
    }
}
