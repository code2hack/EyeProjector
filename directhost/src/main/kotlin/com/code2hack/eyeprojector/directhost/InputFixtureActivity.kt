package com.code2hack.eyeprojector.directhost

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.AtomicFile
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.AbsListView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import org.json.JSONObject

/** Owned Android Views only. Input comes through the real Phone IME and Android input dispatch. */
class InputFixtureActivity : Activity() {
    private lateinit var field: EditText
    private lateinit var main: ScrollView
    private lateinit var nested: ListView
    private var taps = 0
    private var actions = 0
    private var active = false

    private fun record() {
        if (!::nested.isInitialized) return
        val value = JSONObject().put("displayId", display?.displayId).put("active", active)
            .put("text", field.text.toString()).put("selectionStart", field.selectionStart).put("selectionEnd", field.selectionEnd)
            .put("fieldFocused", field.hasFocus()).put("mainScrollY", main.scrollY)
            .put("nestedFirst", nested.firstVisiblePosition).put("nestedTop", nested.getChildAt(0)?.top)
            .put("taps", taps).put("editorActions", actions)
        val file = AtomicFile(File(filesDir, "input-fixture.json"))
        val output = file.startWrite()
        try { output.write(value.toString().toByteArray()); file.finishWrite(output) }
        catch (error: Exception) { file.failWrite(output); throw error }
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK); isFocusableInTouchMode = true
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        val label = TextView(this).apply { text = "EyeProjector · owned native input"; setTextColor(Color.GREEN) }
        root.addView(label)
        field = object : EditText(this) {
            override fun onSelectionChanged(start: Int, end: Int) { super.onSelectionChanged(start, end); record() }
        }.apply {
            id = R.id.input_field
            hint = "Harmless text only"; contentDescription = "Owned Phone IME target"
            setTextColor(Color.WHITE); setHintTextColor(Color.LTGRAY)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            minLines = 2; maxLines = 2
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { record() }
                override fun afterTextChanged(s: Editable?) = Unit
            })
            onFocusChangeListener = View.OnFocusChangeListener { _, _ -> record() }
            setOnEditorActionListener { _, _, _ -> actions++; record(); false }
        }
        root.addView(field, LinearLayout.LayoutParams(-1, -2))
        main = ScrollView(this).apply { id = R.id.main_scroll }
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(Button(this).apply {
            text = "Native tap: 0"
            setOnClickListener { taps++; text = "Native tap: $taps"; record() }
        })
        content.addView(TextView(this).apply { text = "Nested list · aim here, pad swipe"; setTextColor(Color.CYAN) })
        nested = ListView(this).apply {
            id = R.id.nested_list
            contentDescription = "Owned nested list"
            adapter = ArrayAdapter(this@InputFixtureActivity, android.R.layout.simple_list_item_1, (1..40).map { "Nested row $it" })
            isNestedScrollingEnabled = true
            setOnScrollListener(object : AbsListView.OnScrollListener {
                override fun onScrollStateChanged(view: AbsListView?, state: Int) = Unit
                override fun onScroll(view: AbsListView?, first: Int, visible: Int, total: Int) { record() }
            })
        }
        content.addView(nested, LinearLayout.LayoutParams(-1, dp(120)))
        for (i in 1..35) content.addView(TextView(this).apply {
            text = "Main area row $i"; setTextColor(Color.WHITE); gravity = android.view.Gravity.CENTER_VERTICAL
            minHeight = dp(40)
        })
        main.addView(content)
        main.setOnScrollChangeListener { _, _, _, _, _ -> record() }
        root.addView(main, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        root.requestFocus()
        root.post { record() }
    }
    override fun onStart() { super.onStart(); active = true; record() }
    override fun onStop() { active = false; record(); super.onStop() }
}
