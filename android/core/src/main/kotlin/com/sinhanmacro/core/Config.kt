package com.sinhanmacro.core

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.ZonedDateTime

val KST: ZoneOffset = ZoneOffset.ofHours(9)

fun nowKst(): ZonedDateTime = ZonedDateTime.now(KST)

class ConfigException(message: String) : Exception(message)

data class Symbol(val code: String, val name: String, val qty: Int, val maxQty: Int)

data class Flow(val steps: List<JSONObject>, val success: JSONObject?)

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
      "order": { "price_mode": "market" },
      "risk": {
        "max_orders_per_day": 5,
        "dedupe_minutes": 30,
        "enforce_trading_hours": true,
        "trading_hours": ["09:00", "15:20"]
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
    if (this == null) emptyList() else (0 until length()).map { getString(it) }

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
        for (code in s.keys()) {
            val o = s.getJSONObject(code)
            out[code] = Symbol(code, o.optString("name", ""), o.optInt("qty", 1), o.optInt("max_qty", 1))
        }
        out
    }

    val priceMode: String get() = root.getJSONObject("order").optString("price_mode", "market")

    private val risk: JSONObject get() = root.getJSONObject("risk")
    val maxOrdersPerDay: Int get() = risk.optInt("max_orders_per_day", 5)
    val dedupeMinutes: Int get() = risk.optInt("dedupe_minutes", 30)
    val enforceTradingHours: Boolean get() = risk.optBoolean("enforce_trading_hours", true)
    val tradingHours: Pair<LocalTime, LocalTime>
        get() {
            val h = risk.getJSONArray("trading_hours")
            return LocalTime.parse(h.getString(0)) to LocalTime.parse(h.getString(1))
        }

    fun flow(side: String): Flow? {
        val f = root.getJSONObject("flows").optJSONObject(side) ?: return null
        val arr = f.optJSONArray("steps") ?: return null
        val steps = (0 until arr.length()).map { arr.getJSONObject(it) }
        return Flow(steps, f.optJSONObject("success")?.takeIf { it.length() > 0 })
    }

    val telegramToken: String get() = root.getJSONObject("telegram").optString("token", "")
    val telegramChatId: String get() = root.getJSONObject("telegram").optString("chat_id", "")

    companion object {
        fun parse(userJson: String): Config {
            val user = try {
                JSONObject(userJson)
            } catch (e: Exception) {
                throw ConfigException("JSON 문법 오류: ${e.message}")
            }
            val merged = deepMerge(JSONObject(ConfigDefaults.JSON), user)
            val cfg = Config(merged)
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
            for ((code, s) in c.symbols) {
                if (!(code.length == 6 && code.all { it.isDigit() })) {
                    throw ConfigException("symbols 키는 6자리 종목코드여야 함: $code")
                }
                if (s.qty <= 0 || s.maxQty <= 0) throw ConfigException("$code: qty/max_qty 는 1 이상이어야 함")
            }
            if (c.priceMode != "market" && c.priceMode != "limit_from_signal") {
                throw ConfigException("order.price_mode 는 market | limit_from_signal")
            }
            c.tradingHours
            c.titleRegex
            for (side in listOf("buy", "sell")) {
                c.flow(side)?.steps?.forEach { st ->
                    if (!st.has("do")) throw ConfigException("flows.$side 의 step 에 \"do\" 가 없음: $st")
                }
            }
        }
    }
}
