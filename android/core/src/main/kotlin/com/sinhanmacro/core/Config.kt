package com.sinhanmacro.core

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

val KST: ZoneOffset = ZoneOffset.ofHours(9)

fun nowKst(): ZonedDateTime = ZonedDateTime.now(KST)

class ConfigException(message: String) : Exception(message)

/** [ticker] 는 신호에 정확히(대소문자 포함) 그대로 적혀야 매칭됨. [name]/[exchange] 는 화면 검증·표시용. */
data class Symbol(
    val ticker: String,
    val name: String,
    val exchange: String,
    val qty: Int,
    val maxQty: Int,
)

data class Flow(val steps: List<JSONObject>, val success: JSONObject?, val verify: List<JSONObject>)

object ConfigDefaults {
    val JSON = """
    {
      "dry_run": true,
      "app": { "package": "", "extra_packages": [], "launch_wait_sec": 6, "login_check": {} },
      "signal": {
        "source_packages": [],
        "title_regex": "",
        "buy_words": ["매수", "BUY"],
        "sell_words": ["매도", "SELL"]
      },
      "symbols": {},
      "order": { "default_type": "limit", "allowed_types": ["limit", "market"], "type_labels": {} },
      "confirm": { "required": true, "timeout_sec": 120 },
      "safety": { "require_verify": true },
      "risk": {
        "max_orders_per_day": 5,
        "dedupe_minutes": 30,
        "enforce_trading_hours": true,
        "timezone": "America/New_York",
        "trading_hours": ["09:30", "16:00"]
      },
      "flows": {},
      "telegram": { "token": "", "chat_id": "" }
    }
    """.trimIndent()
}

fun deepMerge(base: JSONObject, over: JSONObject): JSONObject {
    val out = JSONObject(base.toString())
    for (k in over.keys()) {
        val v = over.get(k)
        val b = out.opt(k)
        if (v is JSONObject && b is JSONObject) out.put(k, deepMerge(b, v)) else out.put(k, v)
    }
    return out
}

private fun JSONArray?.strings(): List<String> =
    if (this == null) emptyList() else (0 until length()).map { get(it).toString() }

/** 사용자 JSON + 기본값을 병합한 설정. 항상 [parse] 로 생성 (검증 포함). */
class Config private constructor(val root: JSONObject) {
    val dryRun: Boolean get() = root.getBoolean("dry_run")

    private val app: JSONObject get() = root.getJSONObject("app")
    val appPackage: String get() = app.optString("package", "")
    val extraPackages: List<String> get() = app.optJSONArray("extra_packages").strings()
    val launchWaitSec: Double get() = app.optDouble("launch_wait_sec", 6.0)
    val loginCheck: JSONObject? get() = app.optJSONObject("login_check")?.takeIf { it.length() > 0 }

    private val signal: JSONObject get() = root.getJSONObject("signal")
    val sourcePackages: List<String> get() = signal.optJSONArray("source_packages").strings()
    val titleRegex: Regex? get() = signal.optString("title_regex", "").takeIf { it.isNotEmpty() }?.let { Regex(it) }
    val buyWords: List<String> get() = signal.optJSONArray("buy_words").strings()
    val sellWords: List<String> get() = signal.optJSONArray("sell_words").strings()

    val symbols: Map<String, Symbol> by lazy {
        val s = root.getJSONObject("symbols")
        val out = LinkedHashMap<String, Symbol>()
        for (ticker in s.keys()) {
            if (ticker.startsWith("_")) continue // "_note" 같은 주석 키
            val o = s.getJSONObject(ticker)
            out[ticker] = Symbol(
                ticker = ticker,
                name = o.optString("name", ""),
                exchange = o.optString("exchange", ""),
                qty = o.optInt("qty", 1),
                maxQty = o.optInt("max_qty", 1),
            )
        }
        out
    }

    // --- 주문유형 ---
    private val order: JSONObject get() = root.getJSONObject("order")
    val defaultOrderType: OrderType
        get() = order.optString("default_type", "limit").let {
            OrderType.fromKey(it) ?: throw ConfigException("order.default_type 알 수 없음: $it")
        }
    val allowedOrderTypes: Set<OrderType>
        get() = order.optJSONArray("allowed_types").strings()
            .map { OrderType.fromKey(it) ?: throw ConfigException("order.allowed_types 알 수 없음: $it") }
            .toSet()

    /** 신한앱 '주문유형 선택' 화면에 표시되는 문구 (기본: 지정가/시장가/LOO/LOC/Stop limit/Vwap 지정가/Twap 지정가). */
    fun orderTypeLabel(t: OrderType): String =
        order.optJSONObject("type_labels")?.optString(t.key, "")?.takeIf { it.isNotEmpty() } ?: t.defaultLabel

    // --- 확인 / 안전 ---
    val confirmRequired: Boolean get() = root.getJSONObject("confirm").optBoolean("required", true)
    val confirmTimeoutSec: Int get() = root.getJSONObject("confirm").optInt("timeout_sec", 120)
    val requireVerify: Boolean get() = root.getJSONObject("safety").optBoolean("require_verify", true)

    // --- 리스크 ---
    private val risk: JSONObject get() = root.getJSONObject("risk")
    val maxOrdersPerDay: Int get() = risk.optInt("max_orders_per_day", 5)
    val dedupeMinutes: Int get() = risk.optInt("dedupe_minutes", 30)
    val enforceTradingHours: Boolean get() = risk.optBoolean("enforce_trading_hours", true)
    val timezone: ZoneId get() = ZoneId.of(risk.optString("timezone", "America/New_York"))
    val tradingHours: Pair<LocalTime, LocalTime>
        get() {
            val h = risk.getJSONArray("trading_hours")
            return LocalTime.parse(h.getString(0)) to LocalTime.parse(h.getString(1))
        }

    /** 지정가류 주문의 (수량 × 가격) 상한. 없으면 검사 안 함. */
    val maxOrderValue: BigDecimal? get() = if (risk.has("max_order_value")) BigDecimal(risk.get("max_order_value").toString()) else null

    fun flow(side: String): Flow? {
        val f = root.getJSONObject("flows").optJSONObject(side) ?: return null
        val arr = f.optJSONArray("steps") ?: return null
        val steps = (0 until arr.length()).map { arr.getJSONObject(it) }
        val v = f.optJSONArray("verify")
        val verify = if (v == null) emptyList() else (0 until v.length()).map { v.getJSONObject(it) }
        return Flow(steps, f.optJSONObject("success")?.takeIf { it.length() > 0 }, verify)
    }

    val telegramToken: String get() = root.getJSONObject("telegram").optString("token", "")
    val telegramChatId: String get() = root.getJSONObject("telegram").optString("chat_id", "")

    companion object {
        private val TICKER_RE = Regex("^[A-Z0-9][A-Z0-9.\\-]{0,9}$")

        fun parse(userJson: String): Config {
            val user = try {
                JSONObject(userJson)
            } catch (e: Exception) {
                throw ConfigException("JSON 문법 오류: ${e.message}")
            }
            val cfg = Config(deepMerge(JSONObject(ConfigDefaults.JSON), user))
            try {
                validate(cfg)
            } catch (e: ConfigException) {
                throw e
            } catch (e: Exception) {
                throw ConfigException("설정 값 오류: ${e.message}")
            }
            return cfg
        }

        private fun validate(c: Config) {
            for ((ticker, s) in c.symbols) {
                if (!TICKER_RE.matches(ticker)) {
                    throw ConfigException("symbols 키는 대문자 티커여야 함 (예: TMV, BRK.B, 005930): $ticker")
                }
                if (s.qty <= 0 || s.maxQty <= 0) throw ConfigException("$ticker: qty/max_qty 는 1 이상이어야 함")
            }

            val allowed = c.allowedOrderTypes
            if (allowed.isEmpty()) throw ConfigException("order.allowed_types 가 비어 있음")
            if (c.defaultOrderType !in allowed) throw ConfigException("order.default_type 이 allowed_types 에 없음")
            if (c.confirmTimeoutSec <= 0) throw ConfigException("confirm.timeout_sec 는 1 이상")
            c.timezone
            c.tradingHours
            c.titleRegex
            c.maxOrderValue

            for (side in listOf("buy", "sell")) {
                val f = c.flow(side) ?: continue
                for (st in f.steps) {
                    if (!st.has("do")) throw ConfigException("flows.$side 의 step 에 \"do\" 가 없음: $st")
                    whenMatches(st.opt("when"), OrderType.LIMIT)
                }
                for (v in f.verify) {
                    if (!v.has("sel")) throw ConfigException("flows.$side.verify 항목에 \"sel\" 이 없음: $v")
                    whenMatches(v.opt("when"), OrderType.LIMIT)
                }
                if (c.requireVerify && f.verify.none { it.toString().contains("{ticker") }) {
                    throw ConfigException("flows.$side.verify 에 티커 검증이 필요함 (셀렉터에 {ticker} 또는 {ticker_re} 사용)")
                }
            }
        }
    }
}
