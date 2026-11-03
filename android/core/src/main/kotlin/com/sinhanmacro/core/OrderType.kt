package com.sinhanmacro.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * 신한앱 '주문유형 선택' 목록과 1:1 대응.
 * [defaultLabel] 은 앱 화면에 보이는 문구 (config.order.type_labels 로 덮어쓰기 가능).
 * [priced]=true 면 가격 필요 (시장가만 불필요).
 */
enum class OrderType(val key: String, val defaultLabel: String, val priced: Boolean) {
    LIMIT("limit", "지정가", true),
    MARKET("market", "시장가", false),
    LOO("loo", "LOO", true),
    LOC("loc", "LOC", true),
    STOP_LIMIT("stop_limit", "Stop limit", true),
    VWAP("vwap", "Vwap 지정가", true),
    TWAP("twap", "Twap 지정가", true);

    companion object {
        fun fromKey(k: String): OrderType? = values().firstOrNull { it.key == k.lowercase() }
    }
}

/**
 * flows 의 `when` 조건. 값: "always" | "priced"(시장가 제외) | 주문유형 키 | 위 값들의 배열.
 * 없으면 항상 true.
 */
fun whenMatches(raw: Any?, type: OrderType): Boolean = when (raw) {
    null, JSONObject.NULL -> true
    is String -> whenOne(raw, type)
    is JSONArray -> (0 until raw.length()).any { whenOne(raw.get(it).toString(), type) }
    else -> throw ConfigException("when 값 오류: $raw")
}

private fun whenOne(s: String, type: OrderType): Boolean = when (s) {
    "always" -> true
    "priced" -> type.priced
    else -> (OrderType.fromKey(s) ?: throw ConfigException("알 수 없는 when 값: $s")) == type
}
