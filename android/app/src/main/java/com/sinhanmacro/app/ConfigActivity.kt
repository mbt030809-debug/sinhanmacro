package com.sinhanmacro.app

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/** config.json 편집기 (저장 시 문법/값 검증). */
class ConfigActivity : Activity() {
    private lateinit var editor: EditText
    private lateinit var error: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (12 * resources.displayMetrics.density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }

        root.addView(TextView(this).apply {
            text = "설정(JSON) — 저장 전에 문법/값을 검사합니다"
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
        })

        error = TextView(this).apply {
            setTextColor(0xFFC62828.toInt())
            textSize = 13f
        }
        root.addView(error)

        editor = EditText(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 12f
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setHorizontallyScrolling(true)
            setText(Engine.configText())
        }
        root.addView(editor, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(button("저장") { save() }, weight())
        row.addView(button("클립보드 붙여넣기") { pasteFromClipboard() }, weight())
        row.addView(button("예시로 초기화") {
            editor.setText(Engine.exampleConfigText())
            error.text = "예시로 채웠습니다 — 저장해야 적용됩니다"
        }, weight())
        root.addView(row)

        setContentView(root)
    }

    private fun weight() = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        textSize = 12f
        setOnClickListener { onClick() }
    }

    private fun save() {
        val err = Engine.saveConfigText(editor.text.toString())
        if (err == null) {
            error.text = ""
            Toast.makeText(this, "저장됨", Toast.LENGTH_SHORT).show()
        } else {
            error.text = err
        }
    }

    private fun pasteFromClipboard() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
        if (text.isNullOrBlank()) {
            error.text = "클립보드가 비어 있습니다"
        } else {
            editor.setText(text)
            error.text = "붙여넣었습니다 — 저장해야 적용됩니다"
        }
    }
}
