package com.sinhanmacro.core

import org.json.JSONObject
import java.time.ZoneOffset
import java.time.ZonedDateTime

const val SHINHAN = "com.test.shinhan"

val BASE_CONFIG = """
{
  "dry_run": true,
  "app": { "package": "$SHINHAN", "launch_wait_sec": 0 },
  "signal": { "source_packages": ["com.test.signal"] },
  "symbols": {
    "005930": { "name": "삼성전자", "qty": 2, "max_qty": 5 },
    "000660": { "name": "SK하이닉스", "qty": 1, "max_qty": 2 }
  },
  "risk": { "enforce_trading_hours": false },
  "flows": {
    "buy": {
      "steps": [
        { "do": "tap", "sel": { "text": "주식주문" } },
        { "do": "type", "sel": { "id_contains": "qty" }, "text": "{qty}" },
        { "do": "type", "when": "limit", "sel": { "id_contains": "price" }, "text": "{price}" },
        { "do": "tap", "sel": { "text": "매수주문" }, "final": true }
      ],
      "success": { "text_contains": "접수" }
    }
  }
}
""".trimIndent()

fun makeConfig(vararg patch: Pair<String, Any?>): Config {
    val o = JSONObject(BASE_CONFIG)
    for ((path, v) in patch) {
        val keys = path.split(".")
        var cur = o
        for (k in keys.dropLast(1)) cur = cur.optJSONObject(k) ?: JSONObject().also { cur.put(k, it) }
        cur.put(keys.last(), v)
    }
    return Config.parse(o.toString())
}

fun node(text: String, id: String, l: Int, t: Int, r: Int, b: Int, pkg: String = SHINHAN) =
    UiNode(text, id, "", "android.widget.Button", pkg, l, t, r, b, clickable = true, enabled = true)

val MON_10AM: ZonedDateTime = ZonedDateTime.of(2026, 11, 2, 10, 0, 0, 0, ZoneOffset.ofHours(9)) // 월요일

class FakeDevice(var screen: List<UiNode> = emptyList(), var fg: String? = SHINHAN) : Device {
    var clock = 0L
    val events = ArrayList<String>()
    val taps = ArrayList<Pair<Int, Int>>()
    val texts = ArrayList<String>()
    var launchOk = true

    override fun nowMs() = clock
    override fun sleep(ms: Long) {
        clock += ms
    }

    override fun wake() {
        events.add("wake")
    }

    override fun launch(pkg: String): Boolean {
        events.add("launch:$pkg")
        return launchOk
    }

    override fun foregroundPackage() = fg
    override fun nodes() = screen
    override fun tap(x: Int, y: Int): Boolean {
        taps.add(x to y)
        return true
    }

    override fun setText(node: UiNode, text: String): Boolean {
        texts.add(text)
        return true
    }

    override fun back() = true
    override fun home() = true
    override fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, ms: Long) = true
    override fun screenshot(label: String): String {
        events.add("shot:$label")
        return "$label.png"
    }

    override fun finish() {
        events.add("finish")
    }
}

val FULL_SCREEN = listOf(
    node("주식주문", "x:id/menu", 0, 0, 100, 100),
    node("", "x:id/edit_qty", 0, 200, 100, 300),
    node("", "x:id/edit_price", 0, 400, 100, 500),
    node("매수주문", "x:id/btn_go", 200, 600, 400, 700),
    node("주문이 접수되었습니다", "x:id/msg", 0, 800, 100, 900),
)
