package com.code2hack.eyeprojector.directrg

import android.app.Activity
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.GestureDetector
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.code2hack.eyeprojector.protocol.Fit
import com.code2hack.eyeprojector.protocol.Geometry
import com.code2hack.eyeprojector.protocol.Navigation
import com.code2hack.eyeprojector.protocol.Scroll
import java.io.File
import org.json.JSONObject

class ReceiverActivity : Activity(), SurfaceHolder.Callback {
    private lateinit var root: FrameLayout
    private lateinit var screen: ProjectorSurface
    private lateinit var pointer: PointerOverlay
    private lateinit var connectionView: LinearLayout
    private val localButtons = mutableListOf<Button>()
    private lateinit var status: TextView
    private var connectionStatus = ""
    private lateinit var settings: LocalInputSettings
    private lateinit var gestures: PadGestureRecognizer
    private val edgeMotion = EdgeScrollMotion()
    private var session: ProjectionSession? = null
    private var currentGeometry: Geometry? = null
    private var streaming = false
    private var resumed = false
    private var edgeScheduled = false
    private var padUps = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val preferences = getSharedPreferences("projector-input", MODE_PRIVATE)
        settings = LocalInputSettings({ preferences.getString(it, null) }, { k, v -> preferences.edit().putString(k, v).commit() })
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        screen = ProjectorSurface(this,
            { x, y -> remoteTap(x + screen.left, y + screen.top) },
            { x, y, pixels -> remoteScroll(x + screen.left, y + screen.top, pixels) },
        ).apply {
            contentDescription = "Projected Phone application and Phone keyboard"
            holder.addCallback(this@ReceiverActivity)
        }
        root.addView(screen, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        status = TextView(this).apply { setTextColor(Color.WHITE); textSize = 18f; gravity = Gravity.CENTER }
        val connectButton = Button(this).apply { text = getString(R.string.connect); setOnClickListener { connect() } }
        localButtons.add(connectButton)
        connectionView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setBackgroundColor(Color.BLACK)
            addView(status)
            addView(settingsRow("Pointer", { "%.1f×".format(settings.pointerGain) },
                { if (it < 0) settings.pointerTenths > 1 else settings.pointerTenths < 60 }, settings::stepPointer))
            addView(settingsRow("Scroll", { "${settings.scrollSpeed} nominal px/s" },
                { if (it < 0) settings.scrollSpeed > 10 else settings.scrollSpeed < 1200 }, settings::stepScroll))
            addView(connectButton)
        }
        root.addView(connectionView, FrameLayout.LayoutParams(-1, -1))
        pointer = PointerOverlay(this).apply {
            sensitivity(settings.pointerGain)
            onPositionChanged = { updateEdgeScroll() }
        }
        root.addView(pointer, FrameLayout.LayoutParams(-1, -1))
        gestures = PadGestureRecognizer(
            ViewConfiguration.getDoubleTapTimeout().toLong().coerceIn(100, 600),
            ViewConfiguration.getLongPressTimeout().toLong().coerceIn(200, 2000),
            { if (usable()) activatePointer() }, { remoteBack() }, { pixels ->
                if (usable()) pointer.inputPosition().let { remoteScroll(it.x, it.y, pixels) }
            }, { if (usable() && streaming) disconnect() },
        )
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> resize() }
        setContentView(root)
        showStatus(getString(R.string.disconnected))
        root.post {
            window.insetsController?.apply {
                hide(WindowInsets.Type.systemBars())
                systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
            resize()
        }
    }

    private fun settingsRow(label: String, value: () -> String, enabled: (Int) -> Boolean, step: (Int) -> Boolean): LinearLayout {
        val number = TextView(this).apply { setTextColor(Color.WHITE); gravity = Gravity.CENTER }
        val minus = Button(this).apply { text = "−"; contentDescription = "Decrease $label" }
        val plus = Button(this).apply { text = "+"; contentDescription = "Increase $label" }
        fun refresh() {
            number.text = "$label ${value()}"
            minus.isEnabled = enabled(-1); plus.isEnabled = enabled(1)
        }
        for ((button, direction) in listOf(minus to -1, plus to 1)) {
            localButtons.add(button)
            button.setOnClickListener {
                step(direction)
                pointer.sensitivity(settings.pointerGain)
                refresh()
                showStatus(connectionStatus)
            }
        }
        refresh()
        return LinearLayout(this).apply {
            gravity = Gravity.CENTER
            addView(minus); addView(number, LinearLayout.LayoutParams(0, -2, 1f)); addView(plus)
        }
    }

    private fun showStatus(value: String) {
        connectionStatus = value
        status.text = listOfNotNull(value, settings.error).joinToString("\n")
    }

    private fun remoteBack() {
        if (!usable() || !streaming) return
        cancelInput()
        session?.navigate(Navigation.BACK)
    }

    private fun resize() {
        if (root.width <= 0 || root.height <= 0) return
        cancelInput()
        val geometry = currentGeometry
        val fit = geometry?.let { Fit.inside(root.width, root.height, it) }
        if (fit != null) {
            File(filesDir, "display.json").writeText(JSONObject().put("drawableWidth", root.width).put("drawableHeight", root.height)
                .put("rotation", display?.rotation).put("videoWidth", geometry.width).put("videoHeight", geometry.height)
                .put("fitLeft", fit.left).put("fitTop", fit.top).put("fitWidth", fit.width).put("fitHeight", fit.height).toString())
            val old = screen.layoutParams
            if (old.width != fit.width || old.height != fit.height) screen.layoutParams = FrameLayout.LayoutParams(fit.width, fit.height, Gravity.CENTER)
        }
        if (fit != null && streaming) {
            pointer.bounds(fit.left.toFloat(), fit.top.toFloat(), (fit.left + fit.width).toFloat(), (fit.top + fit.height).toFloat(), display?.rotation ?: 0)
        } else pointer.bounds(0f, 0f, root.width.toFloat(), root.height.toFloat(), display?.rotation ?: 0)
    }

    private fun usable() = resumed && hasWindowFocus()
    private fun remoteTap(x: Float, y: Float): Boolean {
        if (!usable() || !streaming) return false
        val size = currentGeometry ?: return false
        val target = Fit.inside(root.width, root.height, size).touch(x, y, size) ?: return false
        return session?.tap(target) == true
    }
    private fun remoteScroll(x: Float, y: Float, pixels: Int): Boolean {
        if (!usable() || !streaming || pixels == 0) return false
        val size = currentGeometry ?: return false
        val target = Fit.inside(root.width, root.height, size).touch(x, y, size) ?: return false
        return session?.scroll(Scroll(target, pixels)) == true
    }

    private fun activatePointer() {
        val point = pointer.inputPosition()
        if (!point.available) return
        if (connectionView.visibility == View.VISIBLE) {
            val rect = Rect()
            for (button in localButtons) {
                if (!button.isShown || !button.isEnabled) continue
                button.getDrawingRect(rect)
                root.offsetDescendantRectToMyCoords(button, rect)
                if (rect.contains(point.x.toInt(), point.y.toInt())) { button.performClick(); break }
            }
        } else remoteTap(point.x, point.y)
    }

    private fun updateEdgeScroll() {
        if (!usable() || !streaming ||
            edgeMotion.direction(pointer.position, pointer.visibleBounds, pointer.expiresAtNs, SystemClock.elapsedRealtimeNanos()) == 0) {
            root.removeCallbacks(edgeTick); edgeScheduled = false; edgeMotion.stop()
        } else if (!edgeScheduled) { edgeScheduled = true; root.post(edgeTick) }
    }
    private val edgeTick = object : Runnable {
        override fun run() {
            edgeScheduled = false
            val point = pointer.inputPosition()
            if (!usable() || !streaming) { edgeMotion.stop(); return }
            val now = SystemClock.elapsedRealtimeNanos()
            val bounds = pointer.visibleBounds
            if (edgeMotion.direction(point, bounds, pointer.expiresAtNs, now) == 0) { edgeMotion.stop(); return }
            val pixels = edgeMotion.step(point, bounds, pointer.expiresAtNs, now, settings.scrollSpeed)
            if (pixels != 0) remoteScroll(point.x, (bounds.top + bounds.bottom) / 2, pixels)
            root.removeCallbacks(this); edgeScheduled = true; root.postDelayed(this, 50)
        }
    }
    private val confirmTap = Runnable {
        if (usable()) gestures.confirm(SystemClock.uptimeMillis()) else gestures.cancel()
    }
    private fun cancelInput() {
        if (::gestures.isInitialized) gestures.cancel(SystemClock.uptimeMillis())
        if (::screen.isInitialized) screen.cancelTouch()
        root.removeCallbacks(confirmTap); root.removeCallbacks(edgeTick); edgeScheduled = false; edgeMotion.stop()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val padKey = nativePadKey(event.device?.name, event.source, event.keyCode)
        // Retired pad summaries must be consumed even after Disconnect. Only an
        // ordinary non-pad Back falls through to the disconnected Activity.
        if (event.keyCode == KeyEvent.KEYCODE_BACK && !streaming && padKey == null) return super.dispatchKeyEvent(event)
        val key = padKey
            ?: if (event.keyCode == KeyEvent.KEYCODE_BACK) PadGestureRecognizer.Key.BACK
            else return super.dispatchKeyEvent(event)
        if (!usable() || event.metaState != 0) { cancelInput(); return true }
        val phase = when {
            event.isCanceled || event.isLongPress -> PadGestureRecognizer.Phase.CANCEL
            event.action == KeyEvent.ACTION_DOWN -> PadGestureRecognizer.Phase.DOWN
            event.action == KeyEvent.ACTION_UP -> PadGestureRecognizer.Phase.UP
            else -> PadGestureRecognizer.Phase.CANCEL
        }
        if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) {
            padUps++
            // Bounded, text-free input diagnostic: prove which native reports
            // actually reach this Activity, rather than assuming kernel delivery.
            if (padKey != null && padUps <= 64) Log.d("EyeProjectorPad", "key=${event.keyCode} up=${event.eventTime} down=${event.downTime}")
        }
        gestures.accept(PadGestureRecognizer.Event(key, phase, event.eventTime, event.downTime, event.repeatCount), SystemClock.uptimeMillis())
        root.removeCallbacks(confirmTap)
        gestures.deadline?.let { root.postDelayed(confirmTap, (it - SystemClock.uptimeMillis()).coerceAtLeast(0)) }
        return true
    }

    internal fun inputSnapshot(): JSONObject = JSONObject()
        .put("sensor", pointer.sourceDescription).put("sourceRegistered", pointer.sourceRegistered)
        .put("acceptedSensorSamples", pointer.acceptedSamples).put("localCursorDraws", pointer.drawCount)
        .put("lastSensorReceiptNs", pointer.lastSampleReceiptNs).put("lastCursorDrawNs", pointer.lastDrawElapsedNs)
        .put("cursorX", pointer.position.x).put("cursorY", pointer.position.y).put("pointerAvailable", pointer.position.available)
        .put("hardwarePadUps", padUps).put("tapContinuationMs", gestures.doubleTapMs)
        .put("pointerGain", settings.pointerGain).put("nominalScrollPixelsPerSecond", settings.scrollSpeed)
        .put("streaming", streaming).put("opticalOrErgonomicPass", false)

    private fun connect() {
        cancelInput()
        pointer.stop(); if (resumed && hasWindowFocus()) pointer.start()
        if (!screen.holder.surface.isValid) { showStatus(getString(R.string.display_unavailable)); return }
        session?.close()
        streaming = false; currentGeometry = null; connectionView.visibility = View.VISIBLE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val next = ProjectionSession(File(filesDir, "session"), screen.holder.surface,
            { source, state -> runOnUiThread {
                if (session === source) {
                    val wasStreaming = streaming
                    streaming = state == "STREAMING"
                    connectionView.visibility = if (streaming) View.GONE else View.VISIBLE
                    showStatus(when (state) {
                        "STREAMING" -> getString(R.string.connected)
                        "CONNECTING", "WAITING_FOR_VIDEO", "WAITING_FOR_AUDIO", "WAITING_FOR_AUDIO_CLOCK" -> getString(R.string.connecting)
                        else -> "Projection stopped: $state"
                    })
                    if (!streaming) cancelInput()
                    if (state !in listOf("STREAMING", "CONNECTING", "WAITING_FOR_VIDEO", "WAITING_FOR_AUDIO", "WAITING_FOR_AUDIO_CLOCK")) window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    // Audio publishes STREAMING every second; telemetry must not cancel pad continuations.
                    if (wasStreaming != streaming) resize()
                }
            } },
            { source, geometry -> runOnUiThread { if (session === source) {
                currentGeometry = geometry
                pointer.stop(); if (resumed && hasWindowFocus()) pointer.start()
                resize()
            } } }, applicationContext)
        session = next; next.start()
    }

    internal fun disconnect() {
        cancelInput()
        val old = session; session = null; old?.close()
        streaming = false; currentGeometry = null; connectionView.visibility = View.VISIBLE
        showStatus(getString(R.string.disconnected))
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        resize()
    }
    override fun onResume() { super.onResume(); resumed = true; pointer.start() }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (::root.isInitialized) {
            if (!hasFocus) { cancelInput(); pointer.stop() }
            else if (resumed) pointer.start()
        }
    }
    override fun onPause() { resumed = false; pointer.stop(); disconnect(); super.onPause() }
    override fun onDestroy() { disconnect(); super.onDestroy() }
    override fun surfaceCreated(holder: SurfaceHolder) = Unit
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) { resize() }
    override fun surfaceDestroyed(holder: SurfaceHolder) { disconnect() }
}

/** Same hardware mapping as the pinned donor; desktop keyboards do not masquerade as RG pad input. */
internal fun nativePadKey(deviceName: String?, source: Int, keyCode: Int): PadGestureRecognizer.Key? {
    if (deviceName != "ROKID,PSOC-TP-R" || source and InputDevice.SOURCE_KEYBOARD != InputDevice.SOURCE_KEYBOARD) return null
    return when (keyCode) {
        KeyEvent.KEYCODE_NOTIFICATION -> PadGestureRecognizer.Key.CONTACT
        KeyEvent.KEYCODE_ENTER -> PadGestureRecognizer.Key.TAP
        291, KeyEvent.KEYCODE_BACK -> PadGestureRecognizer.Key.DOUBLE
        KeyEvent.KEYCODE_DPAD_RIGHT, 292 -> PadGestureRecognizer.Key.FORWARD
        KeyEvent.KEYCODE_DPAD_LEFT, 293 -> PadGestureRecognizer.Key.BACKWARD
        else -> null
    }
}

/** Native clicks (including accessibility activation) and drags share the same mapped input path. */
@SuppressLint("ViewConstructor") // Created in code with callbacks; never inflated from XML.
private class ProjectorSurface(
    context: Context,
    private val tap: (Float, Float) -> Unit,
    private val scroll: (Float, Float, Int) -> Unit,
) : SurfaceView(context) {
    private var clickX: Float? = null
    private var clickY: Float? = null
    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            clickX = e.x; clickY = e.y
            try { performClick() } finally { clickX = null; clickY = null }
            return true
        }
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            scroll(e2.x, e2.y, distanceY.toInt().coerceIn(-1200, 1200))
            return true
        }
    })
    @SuppressLint("ClickableViewAccessibility") // The detector calls performClick only for confirmed short taps.
    override fun onTouchEvent(event: MotionEvent): Boolean = gestures.onTouchEvent(event)
    fun cancelTouch() {
        val now = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
        try { gestures.onTouchEvent(event) } finally { event.recycle() }
    }
    override fun performClick(): Boolean {
        super.performClick()
        tap(clickX ?: width / 2f, clickY ?: height / 2f)
        return true
    }
}
