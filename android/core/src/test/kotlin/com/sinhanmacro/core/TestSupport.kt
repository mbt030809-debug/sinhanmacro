package com.sinhanmacro.core

import org.json.JSONObject
import java.math.BigDecimal
import java.time.ZoneOffset
import java.time.ZonedDateTime

const val SHINHAN = "com.test.shinhan"

val BASE_CONFIG = """
{
  "dry_run": true,
  "app": { "package": "$SHINHAN", "launch_wait_sec": 0 },
  "signal": { "source_packages": ["com.test.signal"] },
  "symbols": {
    "TMV": { "name": "Direxion Daily 20+ Year Treasury Bear 3X", "exchange": "아멕스", "qty": 2, "max_qty": 5 },
    "TQQQ": { "name": "ProShares UltraPro QQQ", "qty": 1, "max_qty": 2 }
  },
  "order": { "default_type": "limit", "allowed_types": ["limit", "market", "loo", "loc", "stop_limit"] },
  "confirm": { "required": false },
  "risk": { "enforce_trading_hours": false },
  "flows": {
    "buy": {
      "steps": [
        { "do": "tap", "sel": { "text": "주식주문" } },
        { "do": "tap", "sel": { "text": "{order_type_label}" } },
        { "do": "type", "sel": { "id_contains": "qty" }, "text": "{qty}" },
        { "do": "type", "when": "priced", "sel": { "id_contains": "price" }, "text": "{price}" },
        { "do": "type", "when": "stop_limit", "sel": { "id_contains": "stop" }, "text": "{stop_price}" },
        { "do": "tap", "sel": { "text": "매수주문" }, "final": true }
      ],
      "verify": [
        { "label": "티커", "sel": { "text_regex": "^{ticker_re}(\\s|${'$'})" } },
        { "label": "수량", "sel": { "id_contains": "qty", "text": "{qty}" } },
        { "label": "가격", "when": "priced", "sel": { "id_contains": "price", "text": "{price}" } }
      ],
      "success": { "text_contains": "접수" }
    }
  }
}
""".trimIndent()

fun makeConfig(vararg patch: Pair<String, Any?>): Config = Config.parse(patchedJson(*patch))

fun patchedJson(vararg patch: Pair<String, Any?>): String {
    val o = JSONObject(BASE_CONFIG)
    for ((path, v) in patch) {
        val keys = path.split(".")
        var cur = o
        for (k in keys.dropLast(1)) cur = cur.optJSONObject(k) ?: JSONObject().also { cur.put(k, it) }
        cur.put(keys.last(), v)
    }
    return o.toString()
}

fun node(text: String, id: String, l: Int, t: Int, r: Int, b: Int, pkg: String = SHINHAN) =
    UiNode(text, id, "", "android.widget.Button", pkg, l, t, r, b, clickable = true, enabled = true)

fun bd(s: String) = BigDecimal(s)

val MON_10AM_KST: ZonedDateTime = ZonedDateTime.of(2026, 11, 2, 10, 0, 0, 0, ZoneOffset.ofHours(9)) // 월요일

fun kst(day: Int, hour: Int, minute: Int = 0): ZonedDateTime =
    ZonedDateTime.of(2026, 11, day, hour, minute, 0, 0, ZoneOffset.ofHours(9))

class FakeDevice(var screen: List<UiNode> = emptyList(), var fg: String? = SHINHAN) : Device {
    var clock = 0L
    val events = ArrayList<String>()
    val taps = ArrayList<Pair<Int, Int>>()
    val texts = ArrayList<String>()
    var launchOk = true
    var ignoreText = false

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

    /** 실제 입력처럼 화면의 해당 노드 텍스트를 바꿈 (verify 가 입력 결과를 읽을 수 있게). */
    override fun setText(node: UiNode, text: String): Boolean {
        texts.add(text)
        if (ignoreText) return true
        screen = screen.map {
            if (it === node) UiNode(text, it.id, it.desc, it.cls, it.pkg, it.left, it.top, it.right, it.bottom, it.clickable, it.enabled) else it
        }
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

/** 주문 화면 + 주문유형 선택 시트를 흉내낸 가짜 화면. 수량/가격 입력란은 비어 있음. */
fun orderScreen(withTicker: Boolean = true): List<UiNode> = buildList {
    add(node("주식주문", "x:id/menu", 0, 0, 100, 100))
    if (withTicker) add(node("TMV 아멕스", "x:id/ticker", 0, 110, 100, 190))
    add(node("지정가", "x:id/opt_limit", 0, 1000, 100, 1100))
    add(node("시장가", "x:id/opt_market", 0, 1100, 100, 1200))
    add(node("LOO", "x:id/opt_loo", 0, 1200, 100, 1300))
    add(node("LOC", "x:id/opt_loc", 0, 1300, 100, 1400))
    add(node("Stop limit", "x:id/opt_sl", 0, 1400, 100, 1500))
    add(node("", "x:id/edit_qty", 0, 200, 100, 300))
    add(node("", "x:id/edit_price", 0, 400, 100, 500))
    add(node("", "x:id/edit_stop", 0, 500, 100, 600))
    add(node("매수주문", "x:id/btn_go", 200, 600, 400, 700))
    add(node("주문이 접수되었습니다", "x:id/msg", 0, 800, 100, 900))
}

class FakeConfirmer(var answer: Boolean = true, var onConfirm: () -> Unit = {}) : Confirmer {
    val requests = ArrayList<ConfirmRequest>()
    override fun confirm(req: ConfirmRequest): Boolean {
        requests.add(req)
        onConfirm()
        return answer
    }
}
