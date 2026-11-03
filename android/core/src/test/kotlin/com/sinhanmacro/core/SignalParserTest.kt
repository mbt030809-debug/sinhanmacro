package com.sinhanmacro.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class SignalParserTest {
    private val cfg = makeConfig()

    @Test
    fun nameBuyWithQty() {
        val s = SignalParser.parse("삼성전자 매수 10주", cfg)
        assertEquals(Signal("buy", "005930", 10, null, "삼성전자 매수 10주"), s)
    }

    @Test
    fun codeSellWithPrice() {
        val s = SignalParser.parse("005930 SELL 5주 @70,000원", cfg)
        assertEquals("sell", s.side)
        assertEquals(5, s.qty)
        assertEquals(70000, s.price)
    }

    @Test
    fun defaultQty() {
        assertEquals(2, SignalParser.parse("삼성전자 매수", cfg).qty)
    }

    @Test
    fun sixDigitPriceNotConfusedWithCode() {
        val s = SignalParser.parse("삼성전자 매수 1주 100000원", cfg)
        assertEquals(1, s.qty)
        assertEquals(100000, s.price)
    }

    @Test
    fun codeDigitsNotUsedAsQty() {
        assertEquals(2, SignalParser.parse("005930 주가 상승 매수", cfg).qty)
    }

    @Test
    fun titleAndBodyJoined() {
        val s = SignalParser.parse("[신호] 삼성전자\n매수 3주", cfg)
        assertEquals("buy", s.side)
        assertEquals(3, s.qty)
    }

    @Test
    fun rejects() {
        listOf(
            "",
            "삼성전자 관망", // 방향 없음
            "삼성전자 매수 후 매도", // 방향 모호
            "카카오 매수 1주", // 화이트리스트 밖
            "삼성전자 SK하이닉스 매수", // 종목 2개
            "삼성전자우 매수 1주", // 우선주 오인 방지
            "999999 매수",
        ).forEach { text ->
            assertFailsWith<ParseException>(text) { SignalParser.parse(text, cfg) }
        }
    }

    @Test
    fun selectorsAndUiNodes() {
        val nodes = FULL_SCREEN
        val sel = org.json.JSONObject("""{"text":"매수주문"}""")
        assertEquals("x:id/btn_go", Selector.find(nodes, sel)?.id)
        assertEquals(300, Selector.find(nodes, sel)?.centerX)
        assertNull(Selector.find(nodes, org.json.JSONObject("""{"text_contains":"없음"}""")))
        assertEquals("x:id/edit_qty", Selector.find(nodes, org.json.JSONObject("""{"id":"edit_qty"}"""))?.id)
        assertFailsWith<StepException> { Selector.find(nodes, org.json.JSONObject("{}")) }
        assertFailsWith<StepException> { Selector.find(nodes, org.json.JSONObject("""{"bogus":1}""")) }
    }
}
