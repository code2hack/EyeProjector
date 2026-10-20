package com.code2hack.eyeprojector.directhost.apps

import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.code2hack.eyeprojector.directhost.R
import java.io.Closeable

/** Area 2 of the Phone panel. Launch integration supplies [onAppSelected] in EP-06. */
class ChosenAppsPanel @JvmOverloads constructor(
    context: Context,
    private val store: ChosenAppStore = ChosenApps.open(context),
    private val catalog: AppCatalog = InstalledAppCatalog(context.packageManager),
) : LinearLayout(context) {
    private val icons = InstalledAppCatalog(context.packageManager)
    private val grid = GridLayout(context).apply { columnCount = 3 }
    private var subscription: Closeable? = null
    private var dialog: AlertDialog? = null
    var onAppSelected: ((ChosenApp) -> Unit)? = null
        set(value) { field = value; refresh() }

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
        orientation = VERTICAL
        gravity = Gravity.CENTER
        minimumHeight = dp(104)
        setPadding(dp(4), dp(8), dp(4), dp(8))
        tag = entry.app.packageName
        val availability = when (entry.availability) {
            AppAvailability.AVAILABLE -> null
            AppAvailability.NOT_INSTALLED -> context.getString(R.string.chosen_apps_not_installed)
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
            dialog = AlertDialog.Builder(context).setTitle(entry.app.label)
                .setItems(arrayOf(context.getString(R.string.chosen_apps_remove))) { _, _ ->
                    store.remove(entry.app.packageName)
                    refresh()
                }.show()
            true
        }
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
