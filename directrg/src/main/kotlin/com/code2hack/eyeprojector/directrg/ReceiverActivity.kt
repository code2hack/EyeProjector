package com.code2hack.eyeprojector.directrg

import android.app.Activity
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.GestureDetector
import android.view.Gravity
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
import android.widget.ScrollView
import android.widget.TextView
import com.code2hack.eyeprojector.protocol.Fit
import com.code2hack.eyeprojector.protocol.Geometry
import com.code2hack.eyeprojector.protocol.Navigation
import com.code2hack.eyeprojector.protocol.Scroll
import com.code2hack.eyeprojector.protocol.PairingPanel
import com.code2hack.eyeprojector.protocol.PeerRole
import com.code2hack.eyeprojector.protocol.SessionControl
import com.code2hack.eyeprojector.protocol.SessionPhase
import com.code2hack.eyeprojector.protocol.SessionState
import java.io.File
import org.json.JSONObject

class ReceiverActivity : Activity(), SurfaceHolder.Callback {
    private lateinit var root: FrameLayout
    private lateinit var screen: ProjectorSurface
    private lateinit var pointer: PointerOverlay
    private lateinit var connectionView: ScrollView
    private lateinit var pairingPanel: PairingPanel
    private lateinit var connectButton: Button
    private val presentation = SessionPresentation()
    private val attachment = SessionAttachment()
    private var sessionClient: PairedSessionClient? = null
    private var clientGeneration = 0L
    private var peerSession: SessionState? = null
    private var connectRequested = false
    private val localButtons = mutableListOf<Button>()
    private lateinit var status: TextView
    private var connectionStatus = ""
    private lateinit var settings: LocalInputSettings
    private lateinit var gestures: PadGestureRecognizer
    private lateinit var localInput: LocalPointerDispatcher
    private lateinit var longTouch: NativePadLongTouch
    private var nativeActionsSubmitted = 0L
    private var nativeActionsRejected = 0L
    private var longClassifications = 0L
    private var unsupportedHeldEvents = 0L
    private var tapDelaySamples = 0L
    private var tapDelayTotalMs = 0L
    private var tapDelayMaxMs = 0L
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
        localInput = LocalPointerDispatcher(root)
        screen = ProjectorSurface(this,
            { x, y -> remoteTap(x + screen.left, y + screen.top) },
            { x, y, pixels -> remoteScroll(x + screen.left, y + screen.top, pixels) },
        ).apply {
            contentDescription = "Projected Phone application and Phone keyboard"
            holder.addCallback(this@ReceiverActivity)
        }
        root.addView(screen, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        status = TextView(this).apply { setTextColor(Color.WHITE); textSize = 18f; gravity = Gravity.CENTER }
        connectButton = Button(this).apply {
            text = getString(R.string.connect)
            setOnClickListener { if (session != null || connectRequested || attachment.epoch != null) disconnect() else connect() }
        }
        localButtons.add(connectButton)
        pairingPanel = PairingPanel(this, PeerRole.GLASSES, { paired ->
            connectButton.visibility = if (paired) View.VISIBLE else View.GONE
        }, { complete -> disconnect(); complete() })
        pairingPanel.addView(connectButton, 2)
        localButtons.add(pairingPanel.forgetButton)
        val settingsPanel = LocalInputSettingsPanel(this, settings) {
            pointer.sensitivity(settings.pointerXGain, settings.pointerYGain)
            edgeMotion.enabled = settings.headScrollEnabled
            updateEdgeScroll()
            showStatus(connectionStatus)
        }
        localButtons.addAll(settingsPanel.buttons)
        edgeMotion.enabled = settings.headScrollEnabled
        connectionView = ScrollView(this).apply {
            isFillViewport = true; setBackgroundColor(Color.BLACK)
            addView(LinearLayout(this@ReceiverActivity).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setBackgroundColor(Color.BLACK)
                addView(pairingPanel); addView(status); addView(settingsPanel)
            })
        }
        root.addView(connectionView, FrameLayout.LayoutParams(-1, -1))
        pointer = PointerOverlay(this).apply {
            sensitivity(settings.pointerXGain, settings.pointerYGain)
            onPositionChanged = {
                if (!position.available) cancelInput()
                updateEdgeScroll()
            }
        }
        root.addView(pointer, FrameLayout.LayoutParams(-1, -1))
        gestures = PadGestureRecognizer(
            ViewConfiguration.getDoubleTapTimeout().toLong().coerceIn(100, 600),
            ViewConfiguration.getLongPressTimeout().toLong().coerceIn(200, 2000),
            ::dispatchPadAction,
        )
        longTouch = NativePadLongTouch(this) { at ->
            if (usable()) gestures.classifyLongPress(at) else gestures.cancel(at)
            schedulePadConfirmation()
        }
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

    private fun showStatus(value: String) {
        connectionStatus = value
        status.text = listOfNotNull(value, settings.error).joinToString("\n")
    }

    private fun remoteBack() {
        if (!usable() || !projectedInput()) return
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
        if (fit != null && projectedInput()) {
            pointer.bounds(fit.left.toFloat(), fit.top.toFloat(), (fit.left + fit.width).toFloat(), (fit.top + fit.height).toFloat(), display?.rotation ?: 0)
        } else pointer.bounds(0f, 0f, root.width.toFloat(), root.height.toFloat(), display?.rotation ?: 0)
    }

    private fun usable() = resumed && hasWindowFocus()
    private fun projectedInput() = streaming && presentation.projectedInput

    /** Shared contextual actions for native function and HID classification. */
    internal val projectionContext: Boolean get() = session != null && !presentation.panelVisible
    internal fun showControlPanel() {
        cancelInput()
        presentation.showPanel()
        connectionView.visibility = View.VISIBLE
        resize()
    }
    internal fun projectionHome() {
        if (!usable() || !projectedInput()) return
        cancelInput(); session?.navigate(Navigation.HOME)
    }
    internal fun contextualBack() {
        cancelInput()
        if (presentation.panelVisible) {
            if (presentation.back()) { connectionView.visibility = View.GONE; resize() }
            else { disconnect(); finish() }
        } else remoteBack()
    }
    internal fun exitToLauncher() { disconnect(); finish() }

    @Deprecated("Android navigation delegates to the current projection/panel context")
    override fun onBackPressed() { contextualBack() }
    private fun remoteTap(x: Float, y: Float, eventTimeMs: Long = SystemClock.uptimeMillis()): Boolean {
        if (!usable() || !projectedInput()) return false
        val size = currentGeometry ?: return false
        val target = Fit.inside(root.width, root.height, size).touch(x, y, size) ?: return false
        return session?.tap(target, eventTimeMs) == true
    }
    private fun remoteScroll(x: Float, y: Float, pixels: Int, eventTimeMs: Long = SystemClock.uptimeMillis(), headMotion: Boolean = false): Boolean {
        if (!usable() || !projectedInput() || pixels == 0) return false
        val size = currentGeometry ?: return false
        val target = Fit.inside(root.width, root.height, size).touch(x, y, size) ?: return false
        return session?.scroll(Scroll(target, pixels), eventTimeMs, headMotion) == true
    }

    private fun dispatchPadAction(action: PadGestureRecognizer.Action) {
        when (action) {
            is PadGestureRecognizer.Action.LongPress -> { longClassifications++; return }
            is PadGestureRecognizer.Action.Contact -> {
                // EP-11 receives genuine DOWN/MOVE/UP/CANCEL here; never replace a hold by a tap.
                unsupportedHeldEvents++
                return
            }
            is PadGestureRecognizer.Action.Back -> { contextualBack(); return }
            else -> Unit
        }
        if (!usable()) { nativeActionsRejected++; return }
        val submitted = when (action) {
            is PadGestureRecognizer.Action.Tap -> {
                val delay = SystemClock.uptimeMillis() - action.downTimeMs
                tapDelaySamples++; tapDelayTotalMs += delay; tapDelayMaxMs = maxOf(tapDelayMaxMs, delay)
                val point = action.position
                if (point?.available != true) false
                else if (connectionView.visibility == View.VISIBLE) localInput.tap(action)
                else remoteTap(point.x, point.y, action.downTimeMs + action.timeMs - action.upTimeMs)
            }
            is PadGestureRecognizer.Action.Scroll -> {
                val point = action.position
                if (point?.available != true) false
                else if (connectionView.visibility == View.VISIBLE) localInput.scroll(point, action.pixels)
                else remoteScroll(point.x, point.y, action.pixels, action.timeMs)
            }
            else -> false
        }
        if (submitted) nativeActionsSubmitted++ else {
            nativeActionsRejected++
            Log.w("EyeProjectorPad", "Native pointer action was not submitted")
        }
    }

    private fun updateEdgeScroll() {
        if (!usable() || !projectedInput() ||
            edgeMotion.direction(pointer.position, pointer.visibleBounds, pointer.expiresAtNs, SystemClock.elapsedRealtimeNanos()) == 0) {
            root.removeCallbacks(edgeTick); edgeScheduled = false; edgeMotion.stop()
            session?.cancelHeadScroll()
        } else if (!edgeScheduled) { edgeScheduled = true; root.post(edgeTick) }
    }
    private val edgeTick = object : Runnable {
        override fun run() {
            edgeScheduled = false
            val point = pointer.inputPosition()
            if (!usable() || !projectedInput()) { edgeMotion.stop(); return }
            val now = SystemClock.elapsedRealtimeNanos()
            val bounds = pointer.visibleBounds
            if (edgeMotion.direction(point, bounds, pointer.expiresAtNs, now) == 0) { edgeMotion.stop(); return }
            val pixels = edgeMotion.step(point, bounds, pointer.expiresAtNs, now, settings.scrollSpeed)
            if (pixels != 0) remoteScroll(point.x, (bounds.top + bounds.bottom) / 2, pixels, headMotion = true)
            root.removeCallbacks(this); edgeScheduled = true; root.postDelayed(this, 50)
        }
    }
    private val confirmTap = Runnable {
        if (usable()) gestures.confirm(SystemClock.uptimeMillis()) else gestures.cancel()
        schedulePadConfirmation()
    }
    private fun schedulePadConfirmation() {
        root.removeCallbacks(confirmTap)
        gestures.deadline?.let { root.postDelayed(confirmTap, (it - SystemClock.uptimeMillis()).coerceAtLeast(0)) }
    }
    private fun cancelInput() {
        if (::gestures.isInitialized) gestures.cancel(SystemClock.uptimeMillis())
        if (::screen.isInitialized) screen.cancelTouch()
        if (::localInput.isInitialized) localInput.cancel()
        session?.cancelPendingInput()
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
        gestures.accept(PadGestureRecognizer.Event(key, phase, event.eventTime, event.downTime, event.repeatCount,
            if (phase == PadGestureRecognizer.Phase.DOWN) pointer.inputPosition() else null), SystemClock.uptimeMillis())
        schedulePadConfirmation()
        return true
    }

    internal fun inputSnapshot(): JSONObject = JSONObject()
        .put("sensor", pointer.sourceDescription).put("sourceRegistered", pointer.sourceRegistered)
        .put("acceptedSensorSamples", pointer.acceptedSamples).put("localCursorDraws", pointer.drawCount)
        .put("lastSensorReceiptNs", pointer.lastSampleReceiptNs).put("lastCursorDrawNs", pointer.lastDrawElapsedNs)
        .put("cursorX", pointer.position.x).put("cursorY", pointer.position.y).put("pointerAvailable", pointer.position.available)
        .put("hardwarePadUps", padUps).put("tapContinuationMs", gestures.doubleTapMs)
        .put("nativeMarkerReplayDelayMs", PadGestureRecognizer.CLASSIFICATION_MS)
        .put("nativeActionsSubmitted", nativeActionsSubmitted).put("nativeActionsRejected", nativeActionsRejected)
        .put("longClassifications", longClassifications).put("unsupportedHeldEvents", unsupportedHeldEvents)
        .put("nativeTapNormalizationSamples", tapDelaySamples).put("nativeTapNormalizationMaxMs", tapDelayMaxMs)
        .put("nativeTapNormalizationMeanMs", if (tapDelaySamples == 0L) 0.0 else tapDelayTotalMs.toDouble() / tapDelaySamples)
        .put("pointerXGain", settings.pointerXGain).put("pointerYGain", settings.pointerYGain)
        .put("headScrollEnabled", settings.headScrollEnabled).put("nominalScrollPixelsPerSecond", settings.scrollSpeed)
        .put("streaming", streaming).put("opticalOrErgonomicPass", false)

    private fun connect() {
        if (!pairingPanel.isPaired()) { showStatus("Pair with the Phone before connecting"); return }
        if (session != null || connectRequested) return
        connectRequested = true
        connectButton.text = "Disconnect"
        showStatus(getString(R.string.connecting))
        sessionClient?.connect()
    }

    private fun observePeer(state: SessionState) {
        if (state.phase == SessionPhase.UNKNOWN) {
            if (session == null) showStatus("Waiting for the Phone to verify its session state")
            return
        }
        peerSession = state
        if (streaming && attachment.epoch == state.epoch && state.phase != SessionPhase.STREAMING)
            sessionClient?.command(SessionControl.Operation.RENDERED, state.epoch)
        if (session != null && (attachment.epoch != state.epoch || !state.phase.active)) disconnect(notifyPeer = false)
        if (session == null && state.phase.active) {
            connectRequested = true
            connectButton.text = "Disconnect"
            showStatus(getString(R.string.connecting))
        }
        if (state.phase == SessionPhase.STOPPING) {
            connectRequested = false; connectButton.text = getString(R.string.connect)
            showStatus("Stopping projection")
        }
        if (state.phase in setOf(SessionPhase.INACTIVE, SessionPhase.FAILED)) {
            connectRequested = false
            if (session == null) {
                connectButton.text = getString(R.string.connect)
                showStatus(if (state.phase == SessionPhase.FAILED) "Projection failed: ${state.detail}" else getString(R.string.disconnected))
            }
        }
        if (resumed && screen.holder.surface.isValid && attachment.available(state)) {
            connectRequested = false
            startProjection(state.epoch)
        }
    }

    private fun startProjection(epoch: String) {
        cancelInput()
        pointer.stop(); if (resumed && hasWindowFocus()) pointer.start()
        streaming = false; currentGeometry = null; presentation.disconnected(); connectionView.visibility = View.VISIBLE
        connectButton.text = "Disconnect"
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        sessionClient?.command(SessionControl.Operation.CONNECTING, epoch)
        val next = ProjectionSession(File(filesDir, "session"), screen.holder.surface,
            { source, state -> runOnUiThread {
                if (session === source) {
                    val wasStreaming = streaming
                    if (state != "STREAMING") cancelInput()
                    streaming = state == "STREAMING"
                    if (streaming) {
                        presentation.streaming()
                        sessionClient?.command(SessionControl.Operation.RENDERED, epoch)
                    }
                    val terminal = state !in listOf("STREAMING", "CONNECTING", "WAITING_FOR_VIDEO")
                    if (terminal) {
                        sessionClient?.command(SessionControl.Operation.FAILED, epoch)
                        attachment.release(); session = null; presentation.disconnected()
                        connectButton.text = getString(R.string.connect)
                    }
                    connectionView.visibility = if (presentation.panelVisible) View.VISIBLE else View.GONE
                    showStatus(when (state) {
                        "STREAMING" -> getString(R.string.connected)
                        "CONNECTING", "WAITING_FOR_VIDEO" -> getString(R.string.connecting)
                        else -> "Projection stopped: $state"
                    })
                    if (!streaming) cancelInput()
                    if (terminal) window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    // Repeated state telemetry must not cancel pending input in the same context.
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

    internal fun disconnect(notifyPeer: Boolean = true) {
        cancelInput()
        val epoch = attachment.release() ?: peerSession?.takeIf { it.phase.active }?.epoch
        if (epoch != null) {
            attachment.suppress(epoch)
        }
        if (notifyPeer) sessionClient?.disconnect(epoch)?.forEach(attachment::suppress)
        connectRequested = false
        val old = session; session = null; old?.close()
        streaming = false; currentGeometry = null; presentation.disconnected(); connectionView.visibility = View.VISIBLE
        connectButton.text = getString(R.string.connect)
        showStatus(getString(R.string.disconnected))
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        resize()
    }
    override fun onResume() {
        super.onResume(); resumed = true; pointer.start(); longTouch.start()
        val generation = ++clientGeneration
        sessionClient = PairedSessionClient(File(filesDir, "session"), { state -> runOnUiThread {
            if (resumed && generation == clientGeneration) observePeer(state)
        } }, { runOnUiThread {
            if (resumed && generation == clientGeneration && session == null) {
                connectRequested = false; connectButton.text = getString(R.string.connect)
                showStatus("Paired Phone unavailable. Open EyeProjector on the Phone and check its setup and network.")
            }
        } }).also { it.start() }
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (::root.isInitialized) {
            if (!hasFocus) { cancelInput(); pointer.stop(); if (::longTouch.isInitialized) longTouch.stop() }
            else if (resumed) { pointer.start(); longTouch.start() }
        }
    }
    override fun onPause() {
        resumed = false; longTouch.stop(); pointer.stop()
        val epoch = attachment.epoch ?: peerSession?.takeIf { it.phase.active }?.epoch
        disconnect(notifyPeer = false)
        clientGeneration++; sessionClient?.close(epoch); sessionClient = null
        super.onPause()
    }
    override fun onDestroy() { longTouch.stop(); disconnect(); pairingPanel.close(); super.onDestroy() }
    override fun surfaceCreated(holder: SurfaceHolder) { peerSession?.let(::observePeer) }
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) { resize() }
    override fun surfaceDestroyed(holder: SurfaceHolder) { disconnect() }
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
