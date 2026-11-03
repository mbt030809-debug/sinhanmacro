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

data class OrderRec(val ts: String, val side: String, val ticker: String, val qty: Int)

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
                        orders.add(OrderRec(r.getString("ts"), r.getString("side"), r.getString("ticker"), r.getInt("qty")))
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
        orders.add(OrderRec(DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(now), sig.side, sig.ticker, sig.qty))
        while (orders.size > 200) orders.removeAt(0)
        save()
    }

    private fun save() {
        val o = JSONObject()
        o.put("seen", JSONArray(seen))
        val arr = JSONArray()
        for (r in orders) {
            arr.put(JSONObject().put("ts", r.ts).put("side", r.side).put("ticker", r.ticker).put("qty", r.qty))
        }
        o.put("orders", arr)
        store.write(o.toString())
    }
}

class Risk(private val state: StateRepo) {
    fun check(cfg: Config, sig: Signal, now: ZonedDateTime, killed: Boolean) {
        if (killed) throw RiskException("킬스위치(STOP) 활성")

        if (sig.orderType !in cfg.allowedOrderTypes) {
            throw RiskException("허용되지 않은 주문유형: ${sig.orderType.defaultLabel} (order.allowed_types)")
        }

        // 거래시간은 거래소 시간대 기준 (미국장: America/New_York → 서머타임 자동 반영)
        val zone = cfg.timezone
        val local = now.withZoneSameInstant(zone)
        if (cfg.enforceTradingHours) {
            val (start, end) = cfg.tradingHours
            val t = local.toLocalTime()
            if (local.dayOfWeek.value >= 6 || t.isBefore(start) || t.isAfter(end)) {
                throw RiskException("거래시간 외 (${zone.id} 평일 $start~$end, 현재 ${local.toLocalDate()} $t)")
            }
        }

        val sym = cfg.symbols[sig.ticker] ?: throw RiskException("허용 티커 아님: ${sig.ticker}")
        if (sig.qty > sym.maxQty) throw RiskException("수량 ${sig.qty} > 종목 한도 ${sym.maxQty}")

        if (sig.orderType.priced && sig.price == null) {
            throw RiskException("${sig.orderType.defaultLabel} 주문은 가격이 필요함")
        }
        if (sig.orderType == OrderType.STOP_LIMIT && sig.stopPrice == null) {
            throw RiskException("Stop limit 주문은 감지단가가 필요함")
        }
        val maxValue = cfg.maxOrderValue
        if (maxValue != null && sig.price != null) {
            val value = sig.price.multiply(java.math.BigDecimal(sig.qty))
            if (value > maxValue) throw RiskException("주문금액 ${value.toPlainString()} > 한도 ${maxValue.toPlainString()}")
        }

        val orders = state.orderList()
        val today = local.toLocalDate()
        val todayCount = orders.count {
            ZonedDateTime.parse(it.ts).withZoneSameInstant(zone).toLocalDate() == today
        }
        if (todayCount >= cfg.maxOrdersPerDay) {
            throw RiskException("일일 주문 한도 ${cfg.maxOrdersPerDay}건 초과")
        }

        val window = Duration.ofMinutes(cfg.dedupeMinutes.toLong())
        for (o in orders.asReversed()) {
            val age = Duration.between(ZonedDateTime.parse(o.ts), now)
            if (age > window) break
            if (o.side == sig.side && o.ticker == sig.ticker) {
                throw RiskException("중복 신호 (${cfg.dedupeMinutes}분 내 동일 주문)")
            }
        }
    }
}
