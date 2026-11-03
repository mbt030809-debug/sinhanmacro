package com.sinhanmacro.core

import java.time.Duration
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RiskTest {
    private fun sig(
        side: String = "buy",
        ticker: String = "TMV",
        qty: Int = 1,
        type: OrderType = OrderType.LIMIT,
        price: String? = "43.38",
        stop: String? = null,
    ) = Signal(side, ticker, qty, type, price?.let { bd(it) }, stop?.let { bd(it) }, "raw")

    private fun risk(): Pair<Risk, StateRepo> {
        val st = StateRepo(MemoryStateStore())
        return Risk(st) to st
    }

    private val now = MON_10AM_KST

    @Test
    fun ok() {
        risk().first.check(makeConfig(), sig(), now, killed = false)
    }

    @Test
    fun qtyOverMax() {
        assertFailsWith<RiskException> { risk().first.check(makeConfig(), sig(qty = 6), now, false) }
    }

    @Test
    fun orderTypeMustBeAllowed() {
        val cfg = makeConfig("order.allowed_types" to org.json.JSONArray(listOf("limit")))
        val r = risk().first
        r.check(cfg, sig(), now, false)
        assertFailsWith<RiskException> { r.check(cfg, sig(type = OrderType.MARKET, price = null), now, false) }
        assertFailsWith<RiskException> { r.check(makeConfig(), sig(type = OrderType.VWAP), now, false) } // 기본 허용목록 밖
    }

    @Test
    fun pricedTypesNeedPriceMarketDoesNot() {
        val r = risk().first
        val cfg = makeConfig()
        assertFailsWith<RiskException> { r.check(cfg, sig(price = null), now, false) }
        assertFailsWith<RiskException> { r.check(cfg, sig(type = OrderType.LOC, price = null), now, false) }
        r.check(cfg, sig(type = OrderType.MARKET, price = null), now, false)
        assertFailsWith<RiskException> { // Stop limit 은 감지단가도 필요
            r.check(cfg, sig(type = OrderType.STOP_LIMIT, price = "42"), now, false)
        }
        r.check(cfg, sig(type = OrderType.STOP_LIMIT, price = "42", stop = "41.5"), now, false)
    }

    @Test
    fun maxOrderValue() {
        val cfg = makeConfig("risk.max_order_value" to 500)
        val r = risk().first
        r.check(cfg, sig(qty = 5, price = "43.38"), now, false) // 216.9
        assertFailsWith<RiskException> { r.check(cfg, sig(qty = 5, price = "100.01"), now, false) } // 500.05
        r.check(cfg, sig(type = OrderType.MARKET, qty = 5, price = null), now, false) // 시장가는 금액 산정 불가 → 통과
    }

    @Test
    fun usSessionHoursFollowNewYorkTimeIncludingDst() {
        val cfg = makeConfig("risk.enforce_trading_hours" to true)
        val r = risk().first
        // 2026-11-02(월)~: DST 종료 후라 뉴욕 = KST - 14시간. 정규장 09:30~16:00 ET = 23:30~06:00 KST
        r.check(cfg, sig(), kst(3, 23, 40), false) // 화 23:40 KST = 월 09:40 ET
        r.check(cfg, sig(), kst(4, 2, 0), false) // 수 02:00 KST = 화 12:00 ET
        assertFailsWith<RiskException> { r.check(cfg, sig(), kst(3, 22, 40), false) } // 08:40 ET 개장 전
        assertFailsWith<RiskException> { r.check(cfg, sig(), kst(4, 6, 30), false) } // 16:30 ET 마감 후
        // 주말: 토 02:00 KST = 금 12:00 ET → 거래일, 토 23:40 KST = 토 09:40 ET → 주말
        r.check(cfg, sig(), kst(7, 2, 0), false)
        assertFailsWith<RiskException> { r.check(cfg, sig(), kst(7, 23, 40), false) }
        assertFailsWith<RiskException> { r.check(cfg, sig(), kst(2, 10, 0), false) } // 월 10:00 KST = 일 20:00 ET
        // 서머타임(2027-03-15): 뉴욕 = KST - 13시간 → 23:40 KST 는 10:40 ET 로 여전히 장중, 22:40 KST = 09:40 ET 장중
        r.check(cfg, sig(), ZonedDateTime.of(2027, 3, 15, 22, 40, 0, 0, KST), false)
    }

    @Test
    fun dailyLimitCountsPerTradingDayNotKstDay() {
        val cfg = makeConfig("risk.max_orders_per_day" to 2, "risk.dedupe_minutes" to 1)
        val (r, st) = risk()
        // 같은 미국 거래일(월)에 속하는 KST 화 00:30, 화 03:00
        st.addOrder(sig(), kst(3, 0, 30))
        st.addOrder(sig("sell"), kst(3, 3, 0))
        assertFailsWith<RiskException> { r.check(cfg, sig(ticker = "TQQQ"), kst(3, 5, 0), false) }
        // 다음 미국 거래일(화) 개장 후엔 새로 카운트
        r.check(cfg, sig(ticker = "TQQQ"), kst(3, 23, 40), false)
    }

    @Test
    fun dedupe() {
        val cfg = makeConfig()
        val (r, st) = risk()
        st.addOrder(sig(), now)
        assertFailsWith<RiskException> { r.check(cfg, sig(), now.plusMinutes(10), false) }
        r.check(cfg, sig(), now.plusMinutes(31), false)
        r.check(cfg, sig("sell"), now.plusMinutes(10), false) // 방향이 다르면 OK
        r.check(cfg, sig(ticker = "TQQQ"), now.plusMinutes(10), false) // 종목이 다르면 OK
    }

    @Test
    fun killSwitch() {
        assertFailsWith<RiskException> { risk().first.check(makeConfig(), sig(), now, killed = true) }
    }

    @Test
    fun statePersistsAcrossInstances() {
        val store = MemoryStateStore()
        val a = StateRepo(store)
        a.markSeen("k1")
        a.addOrder(sig(), now)
        val b = StateRepo(store)
        assertEquals(true, b.hasSeen("k1"))
        assertEquals(1, b.orderList().size)
        assertEquals(Duration.ZERO, Duration.between(ZonedDateTime.parse(b.orderList()[0].ts), now))
    }

    @Test
    fun corruptedStateStartsFresh() {
        val store = MemoryStateStore().also { it.text = "{not json" }
        assertEquals(0, StateRepo(store).orderList().size)
    }
}
