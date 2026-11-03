package com.sinhanmacro.core

class ParseException(message: String) : Exception(message)

data class Signal(
    val side: String, // "buy" | "sell"
    val code: String,
    val qty: Int,
    val price: Int?,
    val raw: String,
) {
    override fun toString(): String {
        val p = if (price != null) "@$price" else "시장가"
        return "${if (side == "buy") "매수" else "매도"} $code ${qty}주 $p"
    }
}

/**
 * 다른 앱이 보내는 알림 텍스트 → [Signal].
 * 예) "삼성전자 매수 10주", "005930 SELL 5주 @70,000원"
 * 종목은 config.symbols 화이트리스트(코드 또는 이름)만 인정.
 */
object SignalParser {
    // 6자리 숫자 (가격 "100000원", "1,000,000" 과 혼동되지 않도록 제외)
    private val CODE_RE = Regex("""(?<![\d,])\d{6}(?![\d,]|\s*원)""")
    private val QTY_RE = Regex("""(\d[\d,]*)\s*(?:주|EA|ea)(?![식문차간])""")
    private val PRICE_RE = Regex(
        """(\d[\d,]*)\s*원|@\s*(\d[\d,]*)|(?:가격|단가|PRICE)\s*[:=]?\s*(\d[\d,]*)""",
        RegexOption.IGNORE_CASE,
    )

    private fun toInt(s: String): Int =
        s.replace(",", "").toIntOrNull() ?: throw ParseException("숫자 오류: $s")

    fun parse(text: String, cfg: Config): Signal {
        val t = text.trim().split(Regex("\\s+")).joinToString(" ")
        if (t.isEmpty()) throw ParseException("빈 텍스트")

        val up = t.uppercase()
        val buy = cfg.buyWords.any { up.contains(it.uppercase()) }
        val sell = cfg.sellWords.any { up.contains(it.uppercase()) }
        if (buy == sell) throw ParseException("매수/매도 구분 불가")
        val side = if (buy) "buy" else "sell"

        val symbols = cfg.symbols
        val codes = LinkedHashSet<String>()
        CODE_RE.findAll(t).forEach { if (symbols.containsKey(it.value)) codes.add(it.value) }
        for ((code, s) in symbols) {
            // "삼성전자우" 같은 우선주를 "삼성전자"로 오인하지 않도록 직후 '우'/영숫자 제외
            if (s.name.isNotEmpty() && Regex(Regex.escape(s.name) + "(?!우|[0-9A-Za-z])").containsMatchIn(t)) {
                codes.add(code)
            }
        }
        if (codes.isEmpty()) throw ParseException("허용 종목 없음")
        if (codes.size > 1) throw ParseException("종목 중복 인식: ${codes.sorted()}")
        val code = codes.first()

        val rest = t.replace(Regex("(?<![\\d,])$code(?![\\d,]|\\s*원)"), " ")

        val qty = QTY_RE.find(rest)?.let { toInt(it.groupValues[1]) } ?: symbols.getValue(code).qty
        if (qty <= 0) throw ParseException("수량 오류")

        val price = PRICE_RE.find(rest)?.let { m ->
            toInt(m.groupValues.drop(1).first { it.isNotEmpty() })
        }
        if (price != null && price <= 0) throw ParseException("가격 오류")

        return Signal(side, code, qty, price, t)
    }
}
