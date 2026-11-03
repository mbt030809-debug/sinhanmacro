package com.sinhanmacro.core

import java.math.BigDecimal

class ParseException(message: String) : Exception(message)

data class Signal(
    val side: String, // "buy" | "sell"
    val ticker: String,
    val qty: Int,
    val orderType: OrderType,
    val price: BigDecimal?,
    val stopPrice: BigDecimal?,
    val raw: String,
) {
    override fun toString(): String {
        val s = if (side == "buy") "매수" else "매도"
        val sp = stopPrice?.let { " 감지단가 ${it.toPlainString()}" } ?: ""
        val p = price?.let { " @${it.toPlainString()}" } ?: ""
        return "$s $ticker ${qty}주 ${orderType.defaultLabel}$sp$p"
    }
}

/**
 * 다른 앱이 보내는 알림 텍스트 → [Signal].
 *
 * 예) "TMV 매수 10주 지정가 43.38"
 *     "TMV SELL 5주 LOC $43.50"
 *     "TMV 매수 2주 Stop limit 감지 41.5 지정가 42"
 *
 * - 티커: config.symbols 에 등록된 티커가 **대소문자까지 정확히, 독립된 토큰**으로 있어야 함
 *   (TMVX, tmv, ATMV, TMV-3X 는 불일치). 종목명으로는 매칭하지 않음
 * - 주문유형: 지정가 / 시장가 / LOO / LOC / Stop limit / Vwap / Twap (없으면 config 기본값)
 * - 수량은 정수 '주'만 허용 (소수점 수량 거부), 가격은 소수 허용 (USD)
 */
object SignalParser {
    private const val NUM = """\d[\d,]*(?:\.\d+)?"""

    // 숫자 뒤가 숫자/소수점 이어짐/수량 단위(주)이면 가격으로 보지 않음
    private const val NUM_END = """(?!\d|[.,]\d|\s*(?:주|EA|ea))"""

    private val FRACTIONAL_QTY = Regex("""\d+\.\d+\s*(?:주|EA|ea)""")
    private val QTY_RE = Regex("""(?<![\d.,])(\d[\d,]*)\s*(?:주|EA|ea)(?![식문차간])""")

    private val STOP_PRICE_RE = Regex(
        """(?:감지단가|감지|STOP|스톱|스탑)\s*[:=]?\s*\$?\s*($NUM)$NUM_END""",
        RegexOption.IGNORE_CASE,
    )
    private val PRICE_RES = listOf(
        Regex("""\$\s*($NUM)$NUM_END"""),
        Regex("""($NUM)\s*(?:USD|usd|달러|원)"""),
        Regex("""@\s*($NUM)$NUM_END"""),
        // 주문유형/가격 키워드 바로 뒤의 숫자 ("지정가 43.38", "LOC 43.38", "Vwap 지정가 43.1")
        Regex(
            """(?:가격|단가|PRICE|지정가|(?<![A-Za-z])(?:LIMIT|LOO|LOC|VWAP|TWAP))\s*[:=]?\s*\$?\s*($NUM)$NUM_END""",
            RegexOption.IGNORE_CASE,
        ),
    )

    // 우선순위 순서로 소거하며 검출 ("Vwap 지정가" 가 지정가로 중복 검출되지 않도록)
    private val TYPE_RES: List<Pair<OrderType, Regex>> = listOf(
        OrderType.STOP_LIMIT to Regex("""STOP\s*-?\s*LIMIT|스[톱탑]\s*리밋|스[톱탑]\s*지정가""", RegexOption.IGNORE_CASE),
        OrderType.VWAP to Regex("""(?<![A-Za-z])VWAP(?![A-Za-z])(?:\s*지정가)?""", RegexOption.IGNORE_CASE),
        OrderType.TWAP to Regex("""(?<![A-Za-z])TWAP(?![A-Za-z])(?:\s*지정가)?""", RegexOption.IGNORE_CASE),
        OrderType.LOO to Regex("""(?<![A-Za-z])LOO(?![A-Za-z])""", RegexOption.IGNORE_CASE),
        OrderType.LOC to Regex("""(?<![A-Za-z])LOC(?![A-Za-z])""", RegexOption.IGNORE_CASE),
        OrderType.MARKET to Regex("""시장가|(?<![A-Za-z])(?:MARKET|MKT)(?![A-Za-z])""", RegexOption.IGNORE_CASE),
        OrderType.LIMIT to Regex("""지정가|(?<![A-Za-z])LIMIT(?![A-Za-z])""", RegexOption.IGNORE_CASE),
    )

    private fun num(s: String): BigDecimal = BigDecimal(s.replace(",", ""))

    private fun tickerRegex(ticker: String) =
        Regex("(?<![A-Za-z0-9.\\-])" + Regex.escape(ticker) + "(?![A-Za-z0-9\\-]|\\.[A-Za-z0-9])")

    fun parse(text: String, cfg: Config): Signal {
        val t = text.trim().split(Regex("\\s+")).joinToString(" ")
        if (t.isEmpty()) throw ParseException("빈 텍스트")

        // 1) 방향
        val up = t.uppercase()
        val buy = cfg.buyWords.any { up.contains(it.uppercase()) }
        val sell = cfg.sellWords.any { up.contains(it.uppercase()) }
        if (buy == sell) throw ParseException("매수/매도 구분 불가")
        val side = if (buy) "buy" else "sell"

        // 2) 티커 — 등록된 티커가 정확히 하나만, 정확한 철자로 있어야 함
        val hits = cfg.symbols.keys.filter { tickerRegex(it).containsMatchIn(t) }
        if (hits.isEmpty()) throw ParseException("등록된 티커 없음 (대소문자까지 정확히 일치해야 함)")
        if (hits.size > 1) throw ParseException("티커 중복 인식: $hits")
        val ticker = hits.first()
        val work = t.replace(tickerRegex(ticker), " ")

        // 3) 주문유형 (가격 추출용 work 는 그대로 두고, 유형 검출용 사본만 소거)
        var typeWork = work
        val found = LinkedHashSet<OrderType>()
        for ((type, re) in TYPE_RES) {
            if (re.containsMatchIn(typeWork)) {
                found.add(type)
                typeWork = re.replace(typeWork, " ")
            }
        }
        // "Stop limit ... 지정가 42", "LOO 지정가 43" 의 '지정가'는 유형이 아니라 가격 설명
        if (OrderType.STOP_LIMIT in found || OrderType.LOO in found || OrderType.LOC in found) found.remove(OrderType.LIMIT)
        if (found.size > 1) throw ParseException("주문유형 모호: ${found.map { it.defaultLabel }}")
        val orderType = found.firstOrNull() ?: cfg.defaultOrderType

        // 4) 감지단가(Stop limit) → 가격
        var rest = work
        var stopPrice: BigDecimal? = null
        STOP_PRICE_RE.find(rest)?.let { m ->
            stopPrice = num(m.groupValues[1])
            rest = rest.removeRange(m.range)
        }
        var price: BigDecimal? = null
        for (re in PRICE_RES) {
            val m = re.find(rest) ?: continue
            price = num(m.groupValues[1])
            rest = rest.removeRange(m.range)
            break
        }
        if (price != null && price!! <= BigDecimal.ZERO) throw ParseException("가격 오류")
        if (stopPrice != null && stopPrice!! <= BigDecimal.ZERO) throw ParseException("감지단가 오류")
        if (orderType == OrderType.MARKET && price != null) {
            throw ParseException("시장가 주문인데 가격이 지정됨 (모호하여 거부)")
        }

        // 5) 수량 — 정수 '주'만
        if (FRACTIONAL_QTY.containsMatchIn(rest)) throw ParseException("소수점 수량은 지원하지 않음")
        val qty = QTY_RE.find(rest)?.let { num(it.groupValues[1]).toInt() } ?: cfg.symbols.getValue(ticker).qty
        if (qty <= 0) throw ParseException("수량 오류")

        return Signal(side, ticker, qty, orderType, price, stopPrice, t)
    }
}
