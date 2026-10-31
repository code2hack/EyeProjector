package com.code2hack.eyeprojector.directhost.apps

import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Build
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import com.code2hack.eyeprojector.directhost.R
import com.code2hack.eyeprojector.directhost.anchor.preferences.AnchorMode
import com.code2hack.eyeprojector.directhost.anchor.preferences.AppAnchorPreferences
import com.code2hack.eyeprojector.directhost.anchor.preferences.AppAnchorStore
import com.code2hack.eyeprojector.directhost.notifications.AppNotificationPreferences
import com.code2hack.eyeprojector.directhost.notifications.AppNotificationStore
import com.code2hack.eyeprojector.directhost.scale.AppScaleCoordinator
import com.code2hack.eyeprojector.directhost.scale.AppScalePanelState
import com.code2hack.eyeprojector.directhost.scale.AppScaleStore
import com.code2hack.eyeprojector.directhost.scale.AppScaleTarget
import java.io.Closeable
import java.io.IOException
import java.util.Locale
import kotlin.math.roundToInt

/** Chosen-app launcher with connected per-app scale and persistent notification options. */
class ChosenAppsPanel @JvmOverloads constructor(
    context: Context,
    private val store: ChosenAppStore = ChosenApps.open(context),
    private val catalog: AppCatalog = InstalledAppCatalog(context.packageManager),
    private val notifications: AppNotificationStore = AppNotificationPreferences.open(context),
    private val anchors: AppAnchorStore = AppAnchorPreferences.open(context),
) : LinearLayout(context) {
    private val icons = InstalledAppCatalog(context.packageManager)
    private val grid = GridLayout(context).apply { columnCount = 3 }
    private var subscription: Closeable? = null
    private var dialog: AlertDialog? = null
    private var pollMenuScale: (() -> Unit)? = null
    var appScales: AppScaleCoordinator? = null
    var onAppSelected: ((ChosenApp) -> Unit)? = null
        set(value) { field = value; refresh() }

    var onAppScale: ((ChosenApp) -> AppScaleTarget)? = null
        set(value) { dialog?.dismiss(); field = value; refresh() }

    /** Uses HostActivity's existing status tick; the menu adds no timer or worker. */
    fun pollScale() { pollMenuScale?.invoke() }

    private val packageChanges = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            dialog?.dismiss()
            refresh()
        }
    }

    init {
        orientation = VERTICAL
        addView(TextView(context).apply {
            text = context.getString(R.string.chosen_apps_title)
            textSize = 20f
            setTextColor(Color.WHITE)
        })
        addView(TextView(context).apply {
            text = context.getString(R.string.chosen_apps_hint)
            setTextColor(Color.WHITE)
        })
        addView(grid, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        refresh()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        subscription = ChosenApps.observe(context) { post { refresh() } }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(packageChanges, filter, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(packageChanges, filter)
        }
        refresh()
    }

    override fun onDetachedFromWindow() {
        dialog?.dismiss()
        subscription?.close()
        subscription = null
        context.unregisterReceiver(packageChanges)
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) refresh()
    }

    fun refresh() {
        grid.removeAllViews()
        // + is a UI affordance, never a persisted item or a catalog-dependent position.
        grid.addView(Button(context).apply {
            text = context.getString(R.string.chosen_apps_plus)
            textSize = 32f
            contentDescription = context.getString(R.string.chosen_apps_add)
            tag = ADD_TAG
            minimumHeight = dp(104)
            setOnClickListener { showPicker() }
        }, cellLayout())
        store.entries(catalog).forEach { entry -> grid.addView(appIcon(entry), cellLayout()) }
    }

    private fun appIcon(entry: ChosenAppEntry): View = LinearLayout(context).apply {
        val scaleAction = onAppScale.takeIf { entry.availability == AppAvailability.AVAILABLE }
        orientation = VERTICAL
        gravity = Gravity.CENTER
        minimumHeight = dp(104)
        setPadding(dp(4), dp(8), dp(4), dp(8))
        tag = entry.app.packageName
        val availability = when (entry.availability) {
            AppAvailability.AVAILABLE -> null
            AppAvailability.UNAVAILABLE -> context.getString(R.string.chosen_apps_unavailable)
        }
        contentDescription = if (availability == null) entry.app.label else
            context.getString(R.string.chosen_apps_description, entry.app.label, availability)
        addView(ImageView(context).apply {
            setImageDrawable(icons.icon(entry.app.packageName))
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LayoutParams(dp(48), dp(48)))
        addView(TextView(context).apply {
            text = entry.app.label
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
        })
        if (availability != null) addView(TextView(context).apply {
            text = availability
            gravity = Gravity.CENTER
            setTextColor(Color.LTGRAY)
        })
        isFocusable = true
        if (entry.availability == AppAvailability.AVAILABLE && onAppSelected != null) {
            setOnClickListener { onAppSelected?.invoke(entry.app) }
        }
        setOnLongClickListener {
            showAppMenu(entry.app, scaleAction)
            true
        }
    }

    private fun showAppMenu(app: ChosenApp, action: ((ChosenApp) -> AppScaleTarget)?) {
        dialog?.dismiss()
        val coordinator = appScales
        val connected = action != null && coordinator != null
        val owner = Any()
        var target: AppScaleTarget? = null
        var scaleSubscription: Closeable? = null
        var editingStarted = false
        var updating = false
        var dragging = false
        var requested = coordinator?.savedRequestedScale(app.packageName) ?: AppScaleStore.DEFAULT_SCALE
        var lastSubmitted: Double? = null
        fun number(value: Double) = String.format(Locale.ROOT, "%.2f", value)
        val input = EditText(context).apply {
            tag = "app-scale-value"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            imeOptions = EditorInfo.IME_ACTION_DONE
            setSingleLine()
            setSelectAllOnFocus(true)
            contentDescription = "Scale, 0.5 to 1.5 times"
            setText(number(requested))
            isEnabled = connected
        }
        val slider = SeekBar(context).apply {
            tag = "app-scale-slider"
            max = 100
            progress = ((requested - AppScaleStore.MIN_SCALE) * 100).roundToInt()
            contentDescription = "Scale, 0.5 to 1.5 times; center is 1 time"
            isEnabled = connected
        }
        val feedback = TextView(context).apply { tag = "app-scale-feedback" }
        val anchorStatus = TextView(context).apply { tag = "app-anchor-status" }
        val clearAnchor = Button(context).apply {
            tag = "app-anchor-clear"
            setText(R.string.app_anchor_clear)
        }
        fun renderAnchor() {
            try {
                val saved = anchors.read(app.packageName)
                anchorStatus.setText(when (saved?.mode) {
                    AnchorMode.CLICK -> R.string.app_anchor_saved_click
                    AnchorMode.HOLD -> R.string.app_anchor_saved_hold
                    null -> R.string.app_anchor_not_saved
                })
                clearAnchor.isEnabled = saved != null
            } catch (_: IOException) {
                anchorStatus.setText(R.string.app_anchor_read_failed)
                clearAnchor.isEnabled = false
            }
        }
        clearAnchor.setOnClickListener {
            try {
                anchors.clear(app.packageName)
                renderAnchor()
            } catch (_: IOException) {
                anchorStatus.setText(R.string.app_anchor_clear_failed)
            }
        }
        val body = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(Switch(context).apply {
                text = "Glasses notifications"
                isChecked = notifications.isEnabled(app.packageName)
                setOnCheckedChangeListener { _, enabled -> notifications.setEnabled(app.packageName, enabled) }
            })
            addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(context).apply { setText(R.string.app_scale_menu) })
                addView(input, LayoutParams(dp(96), LayoutParams.WRAP_CONTENT))
                addView(TextView(context).apply { text = "×" })
            })
            addView(slider, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            addView(LinearLayout(context).apply {
                listOf("0.5×", "1×", "1.5×").forEachIndexed { index, label ->
                    addView(TextView(context).apply {
                        text = label
                        gravity = when (index) { 0 -> Gravity.START; 1 -> Gravity.CENTER; else -> Gravity.END }
                    }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
                }
            })
            addView(feedback)
            addView(anchorStatus)
            addView(clearAnchor)
        }
        val menu = AlertDialog.Builder(context).setTitle(app.label).setView(body)
            .setNegativeButton(R.string.chosen_apps_remove) { _, _ -> store.remove(app.packageName); refresh() }
            .setPositiveButton(android.R.string.ok, null).create()

        fun preview(value: Double, forceText: Boolean = false) {
            updating = true
            val progress = ((value - AppScaleStore.MIN_SCALE) * 100).roundToInt().coerceIn(0, 100)
            if (slider.progress != progress) slider.progress = progress
            val formatted = number(value)
            if ((forceText || !input.hasFocus()) && input.text.toString() != formatted) input.setText(formatted)
            updating = false
        }
        fun render(state: AppScalePanelState?) {
            val matching = state?.takeIf { it.target == target }
            // The first commit always selects the app afresh. A stale observed target
            // must not prevent that selection; after editing begins, retain the original
            // target/context cancellation behavior.
            val canEdit = connected && (!editingStarted ||
                ((target == null || matching != null) && matching?.detail != "TARGET_CHANGED"))
            input.isEnabled = canEdit
            slider.isEnabled = canEdit
            if (!dragging && !input.hasFocus()) {
                preview(matching?.requested?.takeIf { AppScaleStore.valid(it) } ?: requested)
            }
            val saved = coordinator?.savedRequestedScale(app.packageName) ?: requested
            val observed = matching?.applied?.let { "${number(it)}×" } ?: "—"
            val detail = when {
                !connected -> "Connect to adjust scale."
                !editingStarted && target != null && (matching == null ||
                    matching.detail in setOf("TARGET_CHANGED", "LAUNCH_UNCONFIRMED", "UNAVAILABLE")) ->
                    "Adjust scale to open this app on the Glasses."
                target != null && matching == null -> "The projected selection changed."
                matching?.detail == "OPENING" -> "Opening on the Glasses…"
                matching?.detail in setOf("REQUESTING", "RESTORING", "PENDING") -> "Applying requested scale…"
                matching?.detail == "SAVE_FAILED" -> "The requested scale could not be saved."
                matching?.detail in setOf("TARGET_CHANGED", "LAUNCH_UNCONFIRMED", "UNAVAILABLE") -> "Scale is unavailable for this selection."
                matching?.detail in setOf("INVALID_SCALE", "REJECTED") -> "Use a scale from 0.5× to 1.5×."
                else -> ""
            }
            val text = "Saved requested: ${number(saved)}×\nObserved: $observed${if (detail.isEmpty()) "" else "\n$detail"}"
            if (feedback.text.toString() != text) feedback.text = text
        }
        fun attach(next: AppScaleTarget) {
            scaleSubscription?.close()
            target?.let { coordinator?.detach(it, owner) }
            target = next
            coordinator?.attach(next, owner)
            scaleSubscription = coordinator?.observe(::render)
            pollMenuScale = { coordinator?.poll(next, owner) }
        }
        fun apply(value: Double) {
            if (!connected || !AppScaleStore.valid(value) || lastSubmitted == value) return
            requested = value
            preview(value, forceText = true)
            if (!editingStarted) {
                if (catalog.find(app.packageName)?.launchable != true ||
                    store.read().none { it.packageName == app.packageName }) return
                scaleSubscription?.close()
                scaleSubscription = null
                target?.let { coordinator?.detach(it, owner) }
                target = null
                editingStarted = true
                attach(action!!.invoke(app))
            }
            target?.let { coordinator?.setScale(it, value, owner) }
            lastSubmitted = value
        }
        fun commitInput() {
            if (!menu.isShowing || updating || !input.isEnabled) return
            val value = input.text.toString().replace(',', '.').toDoubleOrNull()
            if (value == null || !AppScaleStore.valid(value)) {
                input.error = "Use 0.5 to 1.5"
            } else {
                input.error = null
                apply(value)
            }
        }
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (updating) return
                val value = s.toString().replace(',', '.').toDoubleOrNull() ?: return
                if (AppScaleStore.valid(value)) {
                    input.error = null
                    updating = true
                    slider.progress = ((value - AppScaleStore.MIN_SCALE) * 100).roundToInt()
                    updating = false
                }
            }
        })
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) { commitInput(); input.clearFocus(); true } else false
        }
        input.setOnFocusChangeListener { _, focused -> if (!focused) commitInput() }
        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(seekBar: SeekBar) { dragging = true }
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser || updating) return
                val value = AppScaleStore.MIN_SCALE + progress / 100.0
                preview(value, forceText = true)
                if (!dragging) apply(value) // Keyboard/accessibility adjustment.
            }
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                dragging = false
                apply(AppScaleStore.MIN_SCALE + seekBar.progress / 100.0)
            }
        })
        // Subscribe only for this menu's lifetime; changes from registration refresh the status.
        val anchorSubscription = anchors.observe(app.packageName) { anchorStatus.post {
            if (dialog === menu) renderAnchor()
        } }
        menu.setOnDismissListener {
            anchorSubscription.close()
            scaleSubscription?.close()
            target?.let { coordinator?.detach(it, owner) }
            if (dialog === menu) { dialog = null; pollMenuScale = null }
        }
        dialog = menu
        renderAnchor()
        menu.show()
        // Observing an already selected app does not launch it or alter notification choice.
        val existing = coordinator?.state()?.takeIf {
            connected && it.target.packageName == app.packageName && it.launch?.foregroundPackage == app.packageName
        }
        if (existing != null) attach(existing.target) else render(null)
    }

    private fun showPicker() {
        val candidates = store.candidates(catalog)
        val builder = AlertDialog.Builder(context).setTitle(R.string.chosen_apps_add)
        if (candidates.isEmpty()) {
            builder.setMessage(R.string.chosen_apps_empty_catalog).setPositiveButton(android.R.string.ok, null)
        } else {
            builder.setAdapter(object : BaseAdapter() {
                override fun getCount() = candidates.size
                override fun getItem(position: Int) = candidates[position]
                override fun getItemId(position: Int) = position.toLong()
                override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                    val app = getItem(position)
                    return LinearLayout(context).apply {
                        gravity = Gravity.CENTER_VERTICAL
                        minimumHeight = dp(64)
                        setPadding(dp(16), dp(8), dp(16), dp(8))
                        addView(ImageView(context).apply {
                            setImageDrawable(icons.icon(app.packageName))
                            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
                        }, LayoutParams(dp(40), dp(40)))
                        addView(TextView(context).apply {
                            text = "${app.label}\n${app.packageName}"
                            setPadding(dp(12), 0, 0, 0)
                            setTextColor(Color.WHITE)
                        })
                    }
                }
            }) { _, position ->
                store.add(candidates[position])
                refresh()
            }
        }
        dialog = builder.show()
    }

    private fun cellLayout() = GridLayout.LayoutParams().apply {
        width = 0
        height = ViewGroup.LayoutParams.WRAP_CONTENT
        columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
        setGravity(Gravity.FILL_HORIZONTAL)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object { const val ADD_TAG = "chosen-app-add" }
}
