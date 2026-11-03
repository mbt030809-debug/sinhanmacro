package com.sinhanmacro.core

import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RiskTest {
    private fun sig(side: String = "buy", code: String = "005930", qty: Int = 1, price: Int? = null) =
        Signal(side, code, qty, price, "raw")

    private fun risk(): Pair<Risk, StateRepo> {
        val st = StateRepo(MemoryStateStore())
        return Risk(st) to st
    }

    @Test
    fun ok() {
        risk().first.check(makeConfig(), sig(), MON_10AM, killed = false)
    }

    @Test
    fun qtyOverMax() {
        assertFailsWith<RiskException> { risk().first.check(makeConfig(), sig(qty = 6), MON_10AM, false) }
    }

    @Test
    fun tradingHours() {
        val cfg = makeConfig("risk.enforce_trading_hours" to true)
        val r = risk().first
        r.check(cfg, sig(), MON_10AM, false)
        assertFailsWith<RiskException> { r.check(cfg, sig(), MON_10AM.withHour(16), false) }
        assertFailsWith<RiskException> { r.check(cfg, sig(), MON_10AM.plusDays(5), false) } // 토요일
    }

    @Test
    fun dedupeAndDailyLimit() {
        val cfg = makeConfig("risk.max_orders_per_day" to 2)
        val (r, st) = risk()
        st.addOrder(sig(), MON_10AM)
        assertFailsWith<RiskException> { r.check(cfg, sig(), MON_10AM.plusMinutes(10), false) }
        r.check(cfg, sig(), MON_10AM.plusMinutes(31), false)
        st.addOrder(sig("sell"), MON_10AM.plusMinutes(1))
        assertFailsWith<RiskException> { r.check(cfg, sig(code = "000660"), MON_10AM.plusHours(2), false) }
    }

    @Test
    fun killSwitch() {
        assertFailsWith<RiskException> { risk().first.check(makeConfig(), sig(), MON_10AM, killed = true) }
    }

    @Test
    fun limitModeNeedsPrice() {
        val cfg = makeConfig("order.price_mode" to "limit_from_signal")
        val r = risk().first
        assertFailsWith<RiskException> { r.check(cfg, sig(), MON_10AM, false) }
        r.check(cfg, sig(price = 70000), MON_10AM, false)
    }

    @Test
    fun statePersistsAcrossInstances() {
        val store = MemoryStateStore()
        val a = StateRepo(store)
        a.markSeen("k1")
        a.addOrder(sig(), MON_10AM)
        val b = StateRepo(store)
        assertEquals(true, b.hasSeen("k1"))
        assertEquals(1, b.orderList().size)
        assertEquals(Duration.ZERO, Duration.between(java.time.ZonedDateTime.parse(b.orderList()[0].ts), MON_10AM))
    }

    @Test
    fun corruptedStateStartsFresh() {
        val store = MemoryStateStore().also { it.text = "{not json" }
        assertEquals(0, StateRepo(store).orderList().size)
    }
}
