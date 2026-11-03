package com.sinhanmacro.core

import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

class RiskException(message: String) : Exception(message)

interface StateStore {
    fun read(): String?
    fun write(text: String)
}

class MemoryStateStore : StateStore {
    var text: String? = null
    override fun read(): String? = text
    override fun write(text: String) {
        this.text = text
    }
}

data class OrderRec(val ts: String, val side: String, val code: String, val qty: Int)

/** 처리한 알림 키 + 최근 주문 기록 (중복 방지 / 일일 한도). */
class StateRepo(private val store: StateStore) {
    private val seen = ArrayList<String>()
    private val orders = ArrayList<OrderRec>()

    init {
        try {
            store.read()?.let { text ->
                val o = JSONObject(text)
                o.optJSONArray("seen")?.let { a -> for (i in 0 until a.length()) seen.add(a.getString(i)) }
                o.optJSONArray("orders")?.let { a ->
                    for (i in 0 until a.length()) {
                        val r = a.getJSONObject(i)
                        orders.add(OrderRec(r.getString("ts"), r.getString("side"), r.getString("code"), r.getInt("qty")))
                    }
                }
            }
        } catch (_: Exception) {
            seen.clear() // 손상된 상태 파일은 새로 시작
            orders.clear()
        }
    }

    @Synchronized
    fun orderList(): List<OrderRec> = orders.toList()

    @Synchronized
    fun hasSeen(key: String): Boolean = seen.contains(key)

    @Synchronized
    fun markSeen(key: String) {
        if (seen.contains(key)) return
        seen.add(key)
        while (seen.size > 500) seen.removeAt(0)
        save()
    }

    @Synchronized
    fun addOrder(sig: Signal, now: ZonedDateTime) {
        orders.add(OrderRec(DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(now), sig.side, sig.code, sig.qty))
        while (orders.size > 200) orders.removeAt(0)
        save()
    }

    private fun save() {
        val o = JSONObject()
        o.put("seen", JSONArray(seen))
        val arr = JSONArray()
        for (r in orders) {
            arr.put(JSONObject().put("ts", r.ts).put("side", r.side).put("code", r.code).put("qty", r.qty))
        }
        o.put("orders", arr)
        store.write(o.toString())
    }
}

class Risk(private val state: StateRepo) {
    fun check(cfg: Config, sig: Signal, now: ZonedDateTime, killed: Boolean) {
        if (killed) throw RiskException("킬스위치(STOP) 활성")

        if (cfg.enforceTradingHours) {
            val (start, end) = cfg.tradingHours
            val t = now.toLocalTime()
            if (now.dayOfWeek.value >= 6 || t.isBefore(start) || t.isAfter(end)) {
                throw RiskException("거래시간 외 ($start~$end, 평일)")
            }
        }

        val sym = cfg.symbols[sig.code] ?: throw RiskException("허용 종목 아님: ${sig.code}")
        if (sig.qty > sym.maxQty) throw RiskException("수량 ${sig.qty} > 종목 한도 ${sym.maxQty}")

        if (cfg.priceMode == "limit_from_signal" && sig.price == null) {
            throw RiskException("지정가 모드인데 신호에 가격 없음")
        }

        val today = now.toLocalDate().toString()
        val orders = state.orderList()
        if (orders.count { it.ts.startsWith(today) } >= cfg.maxOrdersPerDay) {
            throw RiskException("일일 주문 한도 ${cfg.maxOrdersPerDay}건 초과")
        }

        val window = Duration.ofMinutes(cfg.dedupeMinutes.toLong())
        for (o in orders.asReversed()) {
            val age = Duration.between(ZonedDateTime.parse(o.ts), now)
            if (age > window) break
            if (o.side == sig.side && o.code == sig.code) {
                throw RiskException("중복 신호 (${cfg.dedupeMinutes}분 내 동일 주문)")
            }
        }
    }
}
