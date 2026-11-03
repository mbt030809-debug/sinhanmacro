package com.sinhanmacro.core

import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class SignalParserTest {
    private val cfg = makeConfig()

    private fun parse(text: String) = SignalParser.parse(text, cfg)

    @Test
    fun basicBuyWithQtyAndLimitPrice() {
        val s = parse("TMV 매수 10주 지정가 43.38")
        assertEquals("buy", s.side)
        assertEquals("TMV", s.ticker)
        assertEquals(10, s.qty)
        assertEquals(OrderType.LIMIT, s.orderType)
        assertEquals(bd("43.38"), s.price)
    }

    @Test
    fun defaultTypeAndQty() {
        val s = parse("TMV 매수")
        assertEquals(OrderType.LIMIT, s.orderType) // config 기본값
        assertEquals(2, s.qty)
        assertNull(s.price)
    }

    @Test
    fun priceFormats() {
        assertEquals(bd("43.38"), parse("TMV 매수 1주 @43.38").price)
        assertEquals(bd("43.5"), parse("TMV 매수 1주 \$43.5").price)
        assertEquals(bd("43.38"), parse("TMV 매수 1주 43.38 USD").price)
        assertEquals(bd("43.38"), parse("TMV 매수 1주 43.38달러").price)
        assertEquals(bd("1000.5"), parse("TMV 매수 1주 가격 1,000.5").price)
    }

    @Test
    fun quantityIsNotConfusedWithPrice() {
        val s = parse("TMV 매수 LIMIT 10주")
        assertEquals(10, s.qty)
        assertNull(s.price) // "LIMIT 10주" 의 10 은 수량
        val t = parse("TMV 매수 10주 LIMIT 43")
        assertEquals(10, t.qty)
        assertEquals(bd("43"), t.price)
    }

    @Test
    fun orderTypes() {
        assertEquals(OrderType.MARKET, parse("TMV 매수 1주 시장가").orderType)
        assertEquals(OrderType.MARKET, parse("TMV BUY 1주 market").orderType)
        assertEquals(OrderType.LOO, parse("TMV 매수 1주 LOO 43.1").orderType)
        assertEquals(bd("43.1"), parse("TMV 매수 1주 LOO 43.1").price)
        assertEquals(OrderType.LOC, parse("TMV 매수 1주 loc 43.1").orderType)
        assertEquals(bd("43.1"), parse("TMV 매수 1주 loc 43.1").price)
        assertEquals(OrderType.LOO, parse("TMV 매수 1주 LOO 지정가 43.1").orderType) // '지정가' 는 가격 설명
        assertEquals(10, parse("TMV 매수 10주 LOC 43.1").qty)
        assertNull(parse("TMV 매수 LOC 10주").price) // 10 은 수량
        assertEquals(OrderType.VWAP, parse("TMV 매수 1주 Vwap 지정가 43.1").orderType)
        assertEquals(OrderType.TWAP, parse("TMV 매수 1주 TWAP 43.1").orderType)
        // "Vwap 지정가" 가 지정가로 중복 검출되지 않음 → 모호 오류가 아님
        assertEquals(bd("43.1"), parse("TMV 매수 1주 Vwap 지정가 43.1").price)
    }

    @Test
    fun stopLimit() {
        val s = parse("TMV 매수 2주 Stop limit 감지 41.5 지정가 42")
        assertEquals(OrderType.STOP_LIMIT, s.orderType)
        assertEquals(bd("41.5"), s.stopPrice)
        assertEquals(bd("42"), s.price)
        assertEquals(bd("41.5"), parse("TMV 매수 2주 스톱리밋 감지단가 41.5 @42").stopPrice)
    }

    @Test
    fun marketOrderWithPriceRejected() {
        assertFailsWith<ParseException> { parse("TMV 매수 1주 시장가 @43.38") }
    }

    @Test
    fun conflictingOrderTypesRejected() {
        assertFailsWith<ParseException> { parse("TMV 매수 1주 LOO LOC 43") }
        assertFailsWith<ParseException> { parse("TMV 매수 1주 시장가 지정가 43") }
    }

    @Test
    fun tickerMustMatchExactly() {
        listOf(
            "TMVX 매수 1주", // 다른 티커
            "tmv 매수 1주", // 소문자
            "ATMV 매수 1주", // 단어 일부
            "TMV-3X 매수 1주", // 하이픈 이어짐
            "TMV2 매수 1주",
            "Direxion Daily 20+ Year Treasury Bear 3X 매수 1주", // 종목명으로는 매칭 안 함
            "삼성전자 매수 1주",
            "TSLA 매수 1주", // 미등록
        ).forEach { text ->
            assertFailsWith<ParseException>(text) { parse(text) }
        }
    }

    @Test
    fun tickerWithPunctuationAndParticlesStillMatches() {
        assertEquals("TMV", parse("[신호] TMV, 매수 1주").ticker)
        assertEquals("TMV", parse("TMV를 매수 1주").ticker)
        assertEquals("TMV", parse("매수: TMV.").ticker)
    }

    @Test
    fun multipleTickersRejected() {
        assertFailsWith<ParseException> { parse("TMV TQQQ 매수 1주") }
    }

    @Test
    fun fractionalQtyRejected() {
        assertFailsWith<ParseException> { parse("TMV 매수 0.5주 @43") }
    }

    @Test
    fun sideAmbiguityRejected() {
        assertFailsWith<ParseException> { parse("TMV 관망") }
        assertFailsWith<ParseException> { parse("TMV 매수 후 매도") }
        assertFailsWith<ParseException> { parse("") }
    }

    @Test
    fun titleAndBodyJoined() {
        val s = parse("[신호] TMV\n매수 3주 @43.2")
        assertEquals(3, s.qty)
        assertEquals(bd("43.2"), s.price)
    }

    @Test
    fun domesticSixDigitCodeAlsoWorksAsTicker() {
        val c = makeConfig("symbols" to JSONObject("""{"005930":{"name":"삼성전자","qty":1,"max_qty":3}}"""))
        val s = SignalParser.parse("005930 매수 2주 지정가 70000", c)
        assertEquals("005930", s.ticker)
        assertEquals(bd("70000"), s.price)
        assertEquals(2, s.qty)
    }

    @Test
    fun selectorsAndUiNodes() {
        val nodes = orderScreen()
        val sel = JSONObject("""{"text":"매수주문"}""")
        assertEquals("x:id/btn_go", Selector.find(nodes, sel)?.id)
        assertEquals(300, Selector.find(nodes, sel)?.centerX)
        assertNull(Selector.find(nodes, JSONObject("""{"text_contains":"없음"}""")))
        assertEquals("x:id/edit_qty", Selector.find(nodes, JSONObject("""{"id":"edit_qty"}"""))?.id)
        assertFailsWith<StepException> { Selector.find(nodes, JSONObject("{}")) }
        assertFailsWith<StepException> { Selector.find(nodes, JSONObject("""{"bogus":1}""")) }
    }
}
