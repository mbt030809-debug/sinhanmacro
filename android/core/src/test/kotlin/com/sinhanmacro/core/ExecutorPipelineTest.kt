package com.sinhanmacro.core

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExecutorPipelineTest {
    private val FINAL_BTN = 300 to 650 // '매수주문' 버튼 중심

    private fun sig(
        type: OrderType = OrderType.LIMIT,
        price: String? = "70.50",
        stop: String? = null,
        qty: Int = 3,
    ) = Signal("buy", "TMV", qty, type, price?.let { bd(it) }, stop?.let { bd(it) }, "raw")

    // --- 화면 검증 / dry run ---
    @Test
    fun dryRunStopsBeforeFinalAndSkipsConfirm() {
        val dev = FakeDevice(orderScreen())
        val conf = FakeConfirmer()
        val res = Executor(dev, conf).execute(makeConfig("dry_run" to true, "confirm.required" to true), sig())
        assertEquals("dry_run", res.status)
        assertEquals(false, res.submitted)
        assertTrue(FINAL_BTN !in dev.taps)
        assertEquals(listOf("3", "70.50"), dev.texts)
        assertTrue(conf.requests.isEmpty()) // dry run 은 승인 요청 없음
        assertContains(dev.events, "finish")
    }

    @Test
    fun orderTypeLabelSelectsMatchingOption() {
        val dev = FakeDevice(orderScreen())
        Executor(dev).execute(makeConfig(), sig(OrderType.LOO, "43.1"))
        assertContains(dev.taps, 50 to 1250) // 'LOO'
        assertTrue((50 to 1050) !in dev.taps) // '지정가' 아님
    }

    @Test
    fun customOrderTypeLabelFromConfig() {
        val labels = org.json.JSONObject("""{"loo":"장시작 지정가"}""")
        val screen = orderScreen() + node("장시작 지정가", "x:id/opt_loo2", 0, 1500, 100, 1600)
        val dev = FakeDevice(screen)
        Executor(dev).execute(makeConfig("order.type_labels" to labels), sig(OrderType.LOO, "43.1"))
        assertContains(dev.taps, 50 to 1550)
    }

    @Test
    fun marketOrderSkipsPriceStepAndPriceVerification() {
        val dev = FakeDevice(orderScreen())
        val res = Executor(dev).execute(makeConfig(), sig(OrderType.MARKET, price = null))
        assertEquals("dry_run", res.status)
        assertEquals(listOf("3"), dev.texts)
        assertContains(dev.taps, 50 to 1150) // '시장가'
    }

    @Test
    fun stopLimitFillsStopPrice() {
        val dev = FakeDevice(orderScreen())
        val res = Executor(dev).execute(makeConfig(), sig(OrderType.STOP_LIMIT, "42", "41.5"))
        assertEquals("dry_run", res.status)
        assertEquals(listOf("3", "42", "41.5"), dev.texts)
        assertContains(dev.taps, 50 to 1450) // 'Stop limit'
    }

    @Test
    fun verifyBlocksWhenTickerNotOnScreen() {
        val dev = FakeDevice(orderScreen(withTicker = false))
        val conf = FakeConfirmer()
        val e = assertFailsWith<StepException> {
            Executor(dev, conf).execute(makeConfig("dry_run" to false, "confirm.required" to true), sig())
        }
        assertContains(e.message ?: "", "티커")
        assertTrue(conf.requests.isEmpty()) // 검증 실패 → 승인 요청조차 안 함
        assertTrue(FINAL_BTN !in dev.taps)
    }

    @Test
    fun verifyBlocksWhenQtyNotReflectedOnScreen() {
        val dev = FakeDevice(orderScreen()).also { it.ignoreText = true }
        val e = assertFailsWith<StepException> { Executor(dev).execute(makeConfig("dry_run" to false), sig()) }
        assertContains(e.message ?: "", "수량")
        assertTrue(FINAL_BTN !in dev.taps)
    }

    // --- 사용자 승인 ---
    @Test
    fun approvedOrderIsSubmitted() {
        val dev = FakeDevice(orderScreen())
        val conf = FakeConfirmer(answer = true)
        val res = Executor(dev, conf).execute(makeConfig("dry_run" to false, "confirm.required" to true), sig())
        assertEquals("ok", res.status)
        assertEquals(true, res.submitted)
        assertEquals(FINAL_BTN, dev.taps.last())
        assertEquals(1, conf.requests.size)
        assertContains(conf.requests[0].summary, "TMV")
        assertContains(conf.requests[0].summary, "3주")
        assertContains(conf.requests[0].summary, "70.50")
        assertEquals("before_confirm_buy_TMV.png", conf.requests[0].shotPath)
        assertEquals(120, conf.requests[0].timeoutSec)
    }

    @Test
    fun rejectedOrTimedOutOrderIsCancelled() {
        val dev = FakeDevice(orderScreen())
        val conf = FakeConfirmer(answer = false) // 거부/시간초과는 동일하게 false
        val res = Executor(dev, conf).execute(makeConfig("dry_run" to false, "confirm.required" to true), sig())
        assertEquals("cancelled", res.status)
        assertEquals(false, res.submitted)
        assertTrue(FINAL_BTN !in dev.taps)
        assertContains(dev.events, "finish")
    }

    @Test
    fun screenChangedWhileWaitingForApprovalIsCaught() {
        val dev = FakeDevice(orderScreen())
        val conf = FakeConfirmer(answer = true, onConfirm = {
            dev.screen = dev.screen.filter { it.id != "x:id/ticker" } // 대기 중 화면이 바뀜
        })
        assertFailsWith<StepException> {
            Executor(dev, conf).execute(makeConfig("dry_run" to false, "confirm.required" to true), sig())
        }
        assertTrue(FINAL_BTN !in dev.taps)
    }

    @Test
    fun confirmRequiredWithoutConfirmerFailsSafe() {
        val dev = FakeDevice(orderScreen())
        assertFailsWith<StepException> {
            Executor(dev, null).execute(makeConfig("dry_run" to false, "confirm.required" to true), sig())
        }
        assertTrue(dev.taps.isEmpty())
    }

    @Test
    fun confirmDisabledSubmitsDirectly() {
        val dev = FakeDevice(orderScreen())
        val res = Executor(dev, null).execute(makeConfig("dry_run" to false, "confirm.required" to false), sig())
        assertEquals("ok", res.status)
        assertEquals(FINAL_BTN, dev.taps.last())
    }

    @Test
    fun unconfirmedWhenNoSuccessText() {
        val dev = FakeDevice(orderScreen().filter { it.id != "x:id/msg" })
        val res = Executor(dev).execute(makeConfig("dry_run" to false), sig())
        assertEquals("unconfirmed", res.status)
        assertEquals(true, res.submitted)
    }

    // --- 안전 검사 ---
    @Test
    fun missingElementThrowsBeforeSubmit() {
        val dev = FakeDevice(listOf(node("엉뚱한화면", "", 0, 0, 10, 10)))
        assertFailsWith<StepException> { Executor(dev).execute(makeConfig("dry_run" to false), sig()) }
        assertTrue(dev.taps.isEmpty())
        assertContains(dev.events, "finish")
    }

    @Test
    fun ignoresNodesFromOtherPackages() {
        val other = orderScreen().map { node(it.text, it.id, it.left, it.top, it.right, it.bottom, pkg = "com.evil") }
        val dev = FakeDevice(other)
        assertFailsWith<StepException> { Executor(dev).execute(makeConfig("dry_run" to false), sig()) }
        assertTrue(dev.taps.isEmpty())
    }

    @Test
    fun abortsWhenTargetAppNotForeground() {
        val dev = FakeDevice(orderScreen(), fg = "com.other")
        assertFailsWith<StepException> { Executor(dev).execute(makeConfig("dry_run" to false), sig()) }
        assertTrue(dev.taps.isEmpty())
    }

    @Test
    fun notLoggedIn() {
        val cfg = makeConfig("dry_run" to false, "app.login_check" to org.json.JSONObject("""{"text":"내 계좌"}"""))
        assertFailsWith<NotLoggedInException> { Executor(FakeDevice(orderScreen())).execute(cfg, sig()) }
    }

    @Test
    fun launchFailure() {
        val dev = FakeDevice(orderScreen()).also { it.launchOk = false }
        assertFailsWith<StepException> { Executor(dev).execute(makeConfig(), sig()) }
    }

    @Test
    fun requiresFinalStep() {
        val cfg = Config.parse(BASE_CONFIG.replace("\"final\": true", "\"x\": 1"))
        assertFailsWith<StepException> { Executor(FakeDevice(orderScreen())).execute(cfg, sig()) }
    }

    @Test
    fun unknownTemplateVariableIsAnError() {
        val json = BASE_CONFIG.replace("\"{qty}\"", "\"{qtyy}\"")
        assertFailsWith<StepException> { Executor(FakeDevice(orderScreen())).execute(Config.parse(json), sig()) }
    }

    // --- Pipeline ---
    private class Env(dry: Boolean = false, confirm: Boolean = false, answer: Boolean = true) {
        val cfg = makeConfig("dry_run" to dry, "confirm.required" to confirm)
        val dev = FakeDevice(orderScreen())
        val confirmer = FakeConfirmer(answer)
        val state = StateRepo(MemoryStateStore())
        val sent = ArrayList<String>()
        var killed = false
        val pipeline = Pipeline(
            { cfg }, dev, state, { sent.add(it) }, { killed },
            confirmer = confirmer, clock = { MON_10AM_KST },
        )
    }

    @Test
    fun pipelineEndToEndAndDedupe() {
        val e = Env()
        assertNotNull(e.pipeline.processText("TMV 매수 1주 @43.38"))
        assertEquals(1, e.state.orderList().size)
        val tapsBefore = e.dev.taps.size
        assertNull(e.pipeline.processText("TMV 매수 1주 @43.38")) // 중복 → 차단
        assertEquals(tapsBefore, e.dev.taps.size)
        assertTrue(e.sent.any { "중복" in it })
    }

    @Test
    fun pipelineWithApproval() {
        val e = Env(confirm = true, answer = true)
        assertNotNull(e.pipeline.processText("TMV 매수 1주 LOC 43.38"))
        assertEquals(1, e.confirmer.requests.size)
        assertContains(e.confirmer.requests[0].summary, "LOC")
        assertEquals(1, e.state.orderList().size)
    }

    @Test
    fun pipelineCancelledIsNotRecorded() {
        val e = Env(confirm = true, answer = false)
        assertNull(e.pipeline.processText("TMV 매수 1주 @43.38"))
        assertTrue(e.state.orderList().isEmpty())
        assertTrue(e.sent.any { "취소" in it })
    }

    @Test
    fun pipelineDryRunRecordsNothing() {
        val e = Env(dry = true)
        e.pipeline.processText("TMV 매수 1주 @43.38")
        assertTrue(e.state.orderList().isEmpty())
    }

    @Test
    fun pipelineBlocksPricedOrderWithoutPrice() {
        val e = Env()
        assertNull(e.pipeline.processText("TMV 매수 1주")) // 기본 지정가인데 가격 없음
        assertTrue(e.dev.events.isEmpty())
        assertTrue(e.sent.any { "가격" in it })
    }

    @Test
    fun pipelineIgnoresUnparseableAndInexactTicker() {
        val e = Env()
        assertNull(e.pipeline.processText("오늘의 증시 브리핑"))
        assertNull(e.pipeline.processText("tmv 매수 1주 @43.38"))
        assertNull(e.pipeline.processText("TMVX 매수 1주 @43.38"))
        assertTrue(e.dev.events.isEmpty())
    }

    @Test
    fun pipelineKillSwitch() {
        val e = Env().also { it.killed = true }
        assertNull(e.pipeline.processText("TMV 매수 1주 @43.38"))
        assertTrue(e.dev.events.isEmpty())
    }

    @Test
    fun pipelineNoExecute() {
        val e = Env()
        assertNotNull(e.pipeline.processText("TMV 매수 1주 @43.38", execute = false))
        assertTrue(e.dev.events.isEmpty())
    }
}
