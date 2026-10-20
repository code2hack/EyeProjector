package com.code2hack.eyeprojector.directhost

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.AtomicFile
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.util.UUID
import org.json.JSONObject

/** Silent, owned responsive Android layout. It deliberately permits normal Activity recreation. */
class ScaleFixtureActivity : Activity() {
    private val instance = UUID.randomUUID().toString()
    private var creations = 1
    private var taps = 0
    private lateinit var root: LinearLayout
    private lateinit var field: EditText
    private lateinit var grid: GridLayout
    private lateinit var metrics: TextView
    private var columns = 0
    private var imeVisible = false

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun rebuildGrid(count: Int) {
        // GridLayout assigns spans during measurement; remove them before reducing columnCount.
        grid.removeAllViews()
        columns = count
        grid.columnCount = count
        repeat(12) { index ->
            grid.addView(Button(this).apply {
                text = "Target ${index + 1}"
                setOnClickListener { taps++; record() }
                if (index == 0) addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> record() }
            }, GridLayout.LayoutParams().apply { width = dp(120); height = dp(64) })
        }
    }

    private fun record() {
        if (!::grid.isInitialized) return
        val config = resources.configuration
        val label = "${config.screenWidthDp} × ${config.screenHeightDp} dp · ${config.densityDpi} dpi · $columns columns"
        if (metrics.text.toString() != label) metrics.text = label
        val first = grid.getChildAt(0)
        val location = IntArray(2).also { first?.getLocationOnScreen(it) }
        val value = JSONObject().put("instance", instance).put("creations", creations).put("displayId", display?.displayId)
            .put("widthDp", config.screenWidthDp).put("heightDp", config.screenHeightDp).put("densityDpi", config.densityDpi)
            .put("rootWidth", root.width).put("rootHeight", root.height).put("columns", columns)
            .put("text", field.text.toString()).put("taps", taps)
            .put("fieldFocused", field.hasFocus()).put("selectionStart", field.selectionStart).put("selectionEnd", field.selectionEnd)
            .put("imeVisible", imeVisible)
            .put("firstTargetX", location[0] + (first?.width ?: 0) / 2)
            .put("firstTargetY", location[1] + (first?.height ?: 0) / 2)
            .put("targetWidth", first?.width ?: 0).put("targetHeight", first?.height ?: 0)
        val file = AtomicFile(File(filesDir, "scale-fixture.json"))
        val output = file.startWrite()
        try { output.write(value.toString().toByteArray()); file.finishWrite(output) }
        catch (error: Exception) { file.failWrite(output); throw error }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        creations = (savedInstanceState?.getInt("creations") ?: 0) + 1
        taps = savedInstanceState?.getInt("taps") ?: 0
        window.setDecorFitsSystemWindows(false)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK) }
        root.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val ime = insets.getInsets(WindowInsets.Type.ime())
            imeVisible = insets.isVisible(WindowInsets.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            view.post { record() }
            insets
        }
        metrics = TextView(this).apply { setTextColor(Color.WHITE); textSize = 16f }
        root.addView(metrics)
        field = EditText(this).apply {
            id = R.id.input_field
            hint = "Owned scale/IME text"; setTextColor(Color.WHITE); setHintTextColor(Color.LTGRAY)
            setText(savedInstanceState?.getString("text") ?: "")
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { record() }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        root.addView(field)
        grid = GridLayout(this)
        rebuildGrid(1)
        root.addView(ScrollView(this).apply { addView(grid) }, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addOnLayoutChangeListener { _, left, _, right, _, _, _, _, _ ->
            val next = ((right - left - root.paddingLeft - root.paddingRight) / dp(120)).coerceAtLeast(1)
            if (columns != next) rebuildGrid(next)
            root.post { record() }
        }
        setContentView(root)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("creations", creations); outState.putInt("taps", taps); outState.putString("text", field.text.toString())
        super.onSaveInstanceState(outState)
    }
}
