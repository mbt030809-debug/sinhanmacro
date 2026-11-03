package com.sinhanmacro.core

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExecutorPipelineTest {
    private fun sig(price: Int? = null) = Signal("buy", "005930", 3, price, "raw")

    @Test
    fun dryRunStopsBeforeFinal() {
        val dev = FakeDevice(FULL_SCREEN)
        val res = Executor(dev).execute(makeConfig("dry_run" to true), sig())
        assertEquals("dry_run", res.status)
        assertEquals(false, res.submitted)
        assertTrue((300 to 650) !in dev.taps) // 매수주문 버튼 미클릭
        assertEquals(listOf("3"), dev.texts) // market 모드 → 가격 입력 step 건너뜀
        assertContains(dev.events, "finish")
    }

    @Test
    fun liveSubmitsAndConfirms() {
        val dev = FakeDevice(FULL_SCREEN)
        val res = Executor(dev).execute(makeConfig("dry_run" to false), sig())
        assertEquals("ok", res.status)
        assertEquals(true, res.submitted)
        assertEquals(300 to 650, dev.taps.last())
    }

    @Test
    fun limitModeTypesPrice() {
        val dev = FakeDevice(FULL_SCREEN)
        Executor(dev).execute(makeConfig("dry_run" to false, "order.price_mode" to "limit_from_signal"), sig(70000))
        assertEquals(listOf("3", "70000"), dev.texts)
    }

    @Test
    fun unconfirmedWhenNoSuccessText() {
        val dev = FakeDevice(FULL_SCREEN.dropLast(1))
        val res = Executor(dev).execute(makeConfig("dry_run" to false), sig())
        assertEquals("unconfirmed", res.status)
        assertEquals(true, res.submitted)
    }

    @Test
    fun missingElementThrowsBeforeSubmit() {
        val dev = FakeDevice(listOf(node("엉뚱한화면", "", 0, 0, 10, 10)))
        assertFailsWith<StepException> { Executor(dev).execute(makeConfig("dry_run" to false), sig()) }
        assertTrue(dev.taps.isEmpty())
        assertContains(dev.events, "finish")
    }

    @Test
    fun ignoresNodesFromOtherPackages() {
        val other = FULL_SCREEN.map { node(it.text, it.id, it.left, it.top, it.right, it.bottom, pkg = "com.evil") }
        val dev = FakeDevice(other)
        assertFailsWith<StepException> { Executor(dev).execute(makeConfig("dry_run" to false), sig()) }
        assertTrue(dev.taps.isEmpty())
    }

    @Test
    fun abortsWhenTargetAppNotForeground() {
        val dev = FakeDevice(FULL_SCREEN, fg = "com.other")
        assertFailsWith<StepException> { Executor(dev).execute(makeConfig("dry_run" to false), sig()) }
        assertTrue(dev.taps.isEmpty())
    }

    @Test
    fun notLoggedIn() {
        val cfg = makeConfig("dry_run" to false, "app.login_check" to org.json.JSONObject("""{"text":"내 계좌"}"""))
        assertFailsWith<NotLoggedInException> { Executor(FakeDevice(FULL_SCREEN)).execute(cfg, sig()) }
    }

    @Test
    fun launchFailure() {
        val dev = FakeDevice(FULL_SCREEN).also { it.launchOk = false }
        assertFailsWith<StepException> { Executor(dev).execute(makeConfig(), sig()) }
    }

    @Test
    fun requiresFinalStep() {
        val cfg = Config.parse(BASE_CONFIG.replace("\"final\": true", "\"x\": 1"))
        assertFailsWith<StepException> { Executor(FakeDevice(FULL_SCREEN)).execute(cfg, sig()) }
    }

    // --- Pipeline ---
    private class Env(dry: Boolean = false) {
        val cfg = makeConfig("dry_run" to dry)
        val dev = FakeDevice(FULL_SCREEN)
        val state = StateRepo(MemoryStateStore())
        val sent = ArrayList<String>()
        var killed = false
        val pipeline = Pipeline(
            { cfg }, dev, state, { sent.add(it) }, { killed }, clock = { MON_10AM },
        )
    }

    @Test
    fun pipelineEndToEndAndDedupe() {
        val e = Env()
        assertNotNull(e.pipeline.processText("삼성전자 매수 1주"))
        assertEquals(1, e.state.orderList().size)
        val tapsBefore = e.dev.taps.size
        assertNull(e.pipeline.processText("삼성전자 매수 1주")) // 중복 → 차단
        assertEquals(tapsBefore, e.dev.taps.size)
        assertTrue(e.sent.any { "중복" in it })
    }

    @Test
    fun pipelineDryRunRecordsNothing() {
        val e = Env(dry = true)
        e.pipeline.processText("삼성전자 매수 1주")
        assertTrue(e.state.orderList().isEmpty())
    }

    @Test
    fun pipelineIgnoresUnparseable() {
        val e = Env()
        assertNull(e.pipeline.processText("오늘의 증시 브리핑"))
        assertTrue(e.dev.events.isEmpty())
    }

    @Test
    fun pipelineKillSwitch() {
        val e = Env().also { it.killed = true }
        assertNull(e.pipeline.processText("삼성전자 매수 1주"))
        assertTrue(e.dev.events.isEmpty())
    }

    @Test
    fun pipelineNoExecute() {
        val e = Env()
        assertNotNull(e.pipeline.processText("삼성전자 매수 1주", execute = false))
        assertTrue(e.dev.events.isEmpty())
    }
}
