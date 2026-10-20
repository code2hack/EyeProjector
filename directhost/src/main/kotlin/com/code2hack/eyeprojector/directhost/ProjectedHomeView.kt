package com.code2hack.eyeprojector.directhost

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PorterDuff
import android.os.Build
import android.view.Gravity
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.code2hack.eyeprojector.directhost.apps.*
import java.io.Closeable

/** The saved selection, with no taskbar or list-management controls on the projection. */
class ProjectedHomeView @JvmOverloads constructor(
    context: Context,
    private val store: ChosenAppStore = ChosenApps.open(context),
    private val catalog: AppCatalog = InstalledAppCatalog(context.packageManager),
) : ScrollView(context) {
    private val icons = InstalledAppCatalog(context.packageManager)
    private val grid = GridLayout(context).apply { columnCount = 3 }
    private val message = TextView(context).apply { setTextColor(Color.WHITE) }
    private var subscription: Closeable? = null
    var onAppSelected: ((ChosenApp) -> Unit)? = null
    private val packageChanges = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = refresh()
    }

    init {
        setBackgroundColor(Color.BLACK)
        isFillViewport = true
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            addView(TextView(context).apply {
                setText(R.string.projected_home_title)
                textSize = 22f
                setTextColor(Color.WHITE)
            })
            addView(message)
            addView(grid, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        })
        refresh()
    }

    fun showStatus(text: String) { message.text = text }

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
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(packageChanges, filter, Context.RECEIVER_EXPORTED)
        else context.registerReceiver(packageChanges, filter)
        refresh()
    }

    override fun onDetachedFromWindow() {
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
        val entries = store.entries(catalog)
        if (entries.isEmpty()) message.setText(R.string.projected_home_empty)
        else if (message.text == context.getString(R.string.projected_home_empty)) message.text = ""
        entries.forEach { entry ->
            grid.addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                minimumHeight = dp(112)
                setPadding(dp(4), dp(8), dp(4), dp(8))
                tag = entry.app.packageName
                val available = entry.availability == AppAvailability.AVAILABLE
                contentDescription = if (available) entry.app.label else context.getString(
                    R.string.chosen_apps_description, entry.app.label, context.getString(R.string.chosen_apps_unavailable))
                addView(ImageView(context).apply {
                    setImageDrawable(icons.icon(entry.app.packageName))
                    setColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)
                    importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
                }, LinearLayout.LayoutParams(dp(48), dp(48)))
                addView(TextView(context).apply {
                    text = entry.app.label
                    gravity = Gravity.CENTER
                    setTextColor(Color.WHITE)
                })
                if (!available) addView(TextView(context).apply {
                    setText(R.string.chosen_apps_unavailable)
                    gravity = Gravity.CENTER
                    setTextColor(Color.LTGRAY)
                })
                isFocusable = true
                if (available) setOnClickListener { onAppSelected?.invoke(entry.app) }
            }, GridLayout.LayoutParams().apply {
                width = 0
                height = ViewGroup.LayoutParams.WRAP_CONTENT
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setGravity(Gravity.FILL_HORIZONTAL)
            })
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
