package com.sinhanmacro.core

import org.json.JSONObject

/** 화면 요소 1개 (접근성 노드를 평탄화한 값). [handle] 은 플랫폼 노드 참조용. */
class UiNode(
    val text: String,
    val id: String,
    val desc: String,
    val cls: String,
    val pkg: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val clickable: Boolean,
    val enabled: Boolean,
    val handle: Any? = null,
) {
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2

    override fun toString(): String = "text='$text' id='$id' desc='$desc' cls='$cls' center=($centerX,$centerY)"
}

/** flows 의 셀렉터. 키: text, text_contains, text_regex, id, id_contains, desc, desc_contains, class, clickable, index */
object Selector {
    private fun meaningful(k: String) = k != "index" && !k.startsWith("_")

    fun matches(n: UiNode, sel: JSONObject): Boolean {
        for (k in sel.keys()) {
            if (!meaningful(k)) continue
            val ok = when (k) {
                "text" -> n.text == sel.getString(k)
                "text_contains" -> n.text.contains(sel.getString(k))
                "text_regex" -> Regex(sel.getString(k)).containsMatchIn(n.text)
                "id" -> sel.getString(k).let { n.id == it || n.id.endsWith("/$it") }
                "id_contains" -> n.id.contains(sel.getString(k))
                "desc" -> n.desc == sel.getString(k)
                "desc_contains" -> n.desc.contains(sel.getString(k))
                "class" -> sel.getString(k).let { n.cls == it || n.cls.endsWith(".$it") }
                "clickable" -> n.clickable == sel.getBoolean(k)
                else -> throw StepException("알 수 없는 셀렉터 키: $k")
            }
            if (!ok) return false
        }
        return true
    }

    fun find(nodes: List<UiNode>, sel: JSONObject): UiNode? {
        if (sel.keys().asSequence().none { meaningful(it) }) throw StepException("빈 셀렉터")
        val hits = nodes.filter { matches(it, sel) }
        val idx = sel.optInt("index", 0)
        return hits.getOrNull(idx)
    }
}
