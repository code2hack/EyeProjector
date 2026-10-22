package com.code2hack.eyeprojector.directrg

import android.app.Activity
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
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
import com.code2hack.eyeprojector.protocol.Touch
import com.code2hack.eyeprojector.protocol.HeadPointerState
import com.code2hack.eyeprojector.protocol.ReceiverInputKind
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
    private lateinit var heldInput: HeldPointerDispatcher
    private lateinit var padContact: NativePadContact
    private lateinit var hidInput: HidKeyInput
    private lateinit var hidDevices: AndroidHidInput
    private lateinit var hidPanel: HidBindingsPanel
    private lateinit var mainControls: LinearLayout
    private lateinit var heldOwner: HeldInputOwner
    private lateinit var completedInput: CompletedReceiverInput
    private lateinit var functionInput: NativeFunctionInput
    private var nativeActionsSubmitted = 0L
    private var nativeActionsRejected = 0L
    private var longClassifications = 0L
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
    private var inputGeneration = 0L
    private var pointerSequence = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val preferences = getSharedPreferences("projector-input", MODE_PRIVATE)
        settings = LocalInputSettings({ preferences.getString(it, null) }, { k, v -> preferences.edit().putString(k, v).commit() })
        val hidBindings = HidBindings({ preferences.getString("hid.bindings", null) },
            { value -> preferences.edit().putString("hid.bindings", value).commit() })
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
        }, { complete ->
            val epoch = attachment.epoch ?: peerSession?.takeIf { it.phase.active }?.epoch
            disconnect(notifyPeer = false)
            val client = sessionClient
            if (client == null) complete()
            else client.disconnect(epoch) { runOnUiThread { complete() } }.forEach(attachment::suppress)
        })
        pairingPanel.addView(connectButton, 2)
        localButtons.add(pairingPanel.forgetButton)
        val settingsPanel = LocalInputSettingsPanel(this, settings, ::showHidBindings) {
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
                mainControls = LinearLayout(this@ReceiverActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(pairingPanel); addView(status); addView(settingsPanel)
                }
                addView(mainControls)
            })
        }
        root.addView(connectionView, FrameLayout.LayoutParams(-1, -1))
        pointer = PointerOverlay(this).apply {
            sensitivity(settings.pointerXGain, settings.pointerYGain)
            onPositionChanged = {
                if (!position.available) cancelInput()
                else if (::heldInput.isInitialized) heldInput.move(SystemClock.uptimeMillis())
                updateEdgeScroll()
                publishHeadPointer()
            }
        }
        root.addView(pointer, FrameLayout.LayoutParams(-1, -1))
        gestures = PadGestureRecognizer(
            ViewConfiguration.getDoubleTapTimeout().toLong().coerceIn(100, 600),
            ViewConfiguration.getLongPressTimeout().toLong().coerceIn(200, 2000),
            ::dispatchPadAction,
        )
        // Admission and position notifications already refresh the model; observing must not publish again.
        heldInput = HeldPointerDispatcher({ pointer.position }) {
            if (!usable()) null
            else if (connectionView.visibility == View.VISIBLE) { event ->
                if (event.phase == Touch.Phase.DOWN && ::completedInput.isInitialized) completedInput.cancel()
                localInput.touch(event)
            }
            else {
                val source = session
                val size = currentGeometry
                if (source == null || size == null || !projectedInput()) null
                else { event ->
                    val fit = Fit.inside(root.width, root.height, size)
                    val point = event.position
                    val target = if (event.phase == Touch.Phase.DOWN) fit.touch(point.x, point.y, size)
                        else fit.touch(point.x.coerceIn(fit.left.toFloat(), (fit.left + fit.width - 1).toFloat()),
                            point.y.coerceIn(fit.top.toFloat(), (fit.top + fit.height - 1).toFloat()), size)
                    target != null && source.touch(Touch(event.phase, event.contactId, target), event.timeMs)
                }
            }
        }
        heldOwner = HeldInputOwner(heldInput)
        completedInput = CompletedReceiverInput(SystemClock::uptimeMillis, { inputGeneration },
            { usable() && connectionView.visibility == View.VISIBLE },
            { heldOwner.cancel(SystemClock.uptimeMillis()) }, localInput::touch, localInput::scroll, localInput::cancel,
            { kind -> when (kind) {
                ReceiverInputKind.PANEL -> showControlPanel()
                ReceiverInputKind.BACK -> contextualBack()
                ReceiverInputKind.EXIT -> exitToLauncher()
                else -> Unit
            } })
        padContact = NativePadContact(this, { pointer.inputPosition() }, { event ->
            if (usable()) gestures.accept(event, SystemClock.uptimeMillis()) else cancelInput()
            schedulePadConfirmation()
        }, ::cancelInput)
        longTouch = NativePadLongTouch(this) { at ->
            if (usable()) gestures.classifyLongPress(at) else gestures.cancel(at)
            schedulePadConfirmation()
        }
        val functionNavigation = FunctionNavigation(::projectionHome, ::showControlPanel, ::contextualBack, ::exitToLauncher)
        functionInput = NativeFunctionInput(this,
            { if (usable()) Pair(session, projectionContext) else null },
            { action -> functionNavigation.dispatch(action, projectionContext) },
        )
        hidInput = HidKeyInput(hidBindings, SystemClock::uptimeMillis,
            { if (usable()) Pair(session, projectionContext) else null },
            { deadline, action ->
                val task = Runnable(action)
                val handler = android.os.Handler(android.os.Looper.getMainLooper())
                handler.postAtTime(task, deadline)
                val cancel: () -> Unit = { handler.removeCallbacks(task) }
                cancel
            }, { pointer.inputPosition() },
            { owner, event ->
                if (event.phase == PadGestureRecognizer.Phase.DOWN) {
                    gestures.cancel(event.timeMs)
                    schedulePadConfirmation()
                }
                if (event.phase != PadGestureRecognizer.Phase.DOWN || hidInput.owns(owner))
                    heldOwner.accept(owner, event)
                updateEdgeScroll()
            },
            { owner, at -> heldOwner.cancel(owner, at); updateEdgeScroll() },
            { pixels, point, at -> dispatchPadAction(PadGestureRecognizer.Action.Scroll(pixels, point, at)) },
            { action -> functionNavigation.dispatch(action, projectionContext) },
            { if (::hidPanel.isInitialized) hidPanel.refresh() },
            ::hoveredHidAction,
        )
        hidDevices = AndroidHidInput(this, hidInput) { event ->
            if (::hidPanel.isInitialized && hidPanel.visibility == View.VISIBLE && connectionView.visibility == View.VISIBLE)
                hidPanel.received(event)
        }
        hidPanel = HidBindingsPanel(this, hidBindings, hidInput, ::showControlPanel).apply { visibility = View.GONE }
        localButtons.addAll(hidPanel.buttons)
        (connectionView.getChildAt(0) as LinearLayout).addView(hidPanel)
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
        publishHeadPointer()
    }

    /** Passive snapshot: inputPosition() publishes too, so never call it from this producer. */
    private fun publishHeadPointer() {
        val source = session ?: return
        if (!::pointer.isInitialized || root.width <= 0 || root.height <= 0) return
        val position = pointer.position
        val size = currentGeometry
        val fit = size?.let { Fit.inside(root.width, root.height, it) }
        val projected = connectionView.visibility != View.VISIBLE
        val available = usable() && position.available && (!projected || projectedInput())
        source.headPointer(HeadPointerState(inputGeneration, ++pointerSequence, available, projected,
            position.x, position.y, fit, size), pointer.expiresAtNs)
    }

    private fun usable() = resumed && hasWindowFocus()
    private fun projectedInput() = streaming && presentation.projectedInput

    /** Shared contextual actions for native function and HID classification. */
    internal val projectionContext: Boolean get() = session != null && !presentation.panelVisible
    internal fun showControlPanel() {
        cancelInput()
        presentation.showPanel()
        if (::hidPanel.isInitialized) hidPanel.visibility = View.GONE
        mainControls.visibility = View.VISIBLE
        connectionView.visibility = View.VISIBLE
        connectionView.scrollTo(0, 0)
        resize()
    }
    private fun hoveredHidAction(trace: HidDecisionTrace.Event? = null): HidAction? {
        if (!HidDecisionTrace.observed(trace, HidDecisionTrace.Step.HOVER_USABLE_BEFORE, usable()) ||
            !HidDecisionTrace.observed(trace, HidDecisionTrace.Step.HOVER_INITIALIZED, ::hidPanel.isInitialized) ||
            HidDecisionTrace.observed(trace, HidDecisionTrace.Step.HOVER_PAGE_HIDDEN, hidPanel.visibility != View.VISIBLE) ||
            HidDecisionTrace.observed(trace, HidDecisionTrace.Step.HOVER_CONNECTION_HIDDEN, connectionView.visibility != View.VISIBLE)) return null
        val admitted = inputGeneration
        val source = session
        val projected = projectionContext
        val panel = hidPanel
        val point = pointer.inputPosition()
        trace?.record(HidDecisionTrace.Step.HOVER_POINT, point = point)
        val after = inputGeneration
        if (HidDecisionTrace.observed(trace, HidDecisionTrace.Step.HOVER_GENERATION_CHANGED, after != admitted, before = admitted, after = after) ||
            !HidDecisionTrace.observed(trace, HidDecisionTrace.Step.HOVER_USABLE_AFTER, usable()) ||
            HidDecisionTrace.observed(trace, HidDecisionTrace.Step.HOVER_SESSION_CHANGED, session !== source) ||
            HidDecisionTrace.observed(trace, HidDecisionTrace.Step.HOVER_PROJECTION_CHANGED, projectionContext != projected) ||
            HidDecisionTrace.observed(trace, HidDecisionTrace.Step.HOVER_PANEL_CHANGED, hidPanel !== panel) ||
            HidDecisionTrace.observed(trace, HidDecisionTrace.Step.HOVER_PAGE_HIDDEN_AFTER, panel.visibility != View.VISIBLE) ||
            HidDecisionTrace.observed(trace, HidDecisionTrace.Step.HOVER_CONNECTION_HIDDEN_AFTER, connectionView.visibility != View.VISIBLE)) return null
        return panel.actionAt(point, root, trace)
    }

    private fun showHidBindings() {
        cancelInput()
        mainControls.visibility = View.GONE
        hidPanel.visibility = View.VISIBLE
        hidPanel.refresh()
        connectionView.scrollTo(0, 0)
        resize()
    }
    internal fun projectionHome() {
        if (!usable() || !projectedInput()) return
        cancelInput(); session?.navigate(Navigation.HOME)
    }
    internal fun contextualBack() {
        cancelInput()
        if (presentation.panelVisible && ::hidPanel.isInitialized && hidPanel.visibility == View.VISIBLE) { showControlPanel(); return }
        if (presentation.panelVisible) {
            if (presentation.back()) { connectionView.visibility = View.GONE; resize() }
            else { disconnect(); finish() }
        } else remoteBack()
    }
    internal fun exitToLauncher() {
        // End local interception and invalidate peer callbacks before leaving this Activity.
        resumed = false
        hidDevices.stop(); functionInput.stop(); longTouch.stop(); padContact.stop(); pointer.stop()
        val epoch = attachment.epoch ?: peerSession?.takeIf { it.phase.active }?.epoch
        disconnect()
        clientGeneration++; sessionClient?.close(epoch); sessionClient = null
        startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }

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
                val accepted = if (action.event.phase == PadGestureRecognizer.Phase.CANCEL || usable())
                    heldOwner.acceptNative(action.event) else false
                if (accepted) nativeActionsSubmitted++ else nativeActionsRejected++
                updateEdgeScroll(); return
            }
            is PadGestureRecognizer.Action.Back -> { contextualBack(); return }
            else -> Unit
        }
        if (!usable()) { nativeActionsRejected++; return }
        val admittedGeneration = inputGeneration
        val admittedSession = session
        val admittedGeometry = currentGeometry
        val admittedLocal = connectionView.visibility == View.VISIBLE
        // Pointer publication and old-target CANCEL may synchronously replace the view/session.
        pointer.inputPosition()
        if (inputGeneration != admittedGeneration || !usable() || session !== admittedSession ||
            currentGeometry != admittedGeometry || (connectionView.visibility == View.VISIBLE) != admittedLocal) return
        if (::completedInput.isInitialized) completedInput.cancel()
        heldOwner.cancel(action.timeMs)
        if (inputGeneration != admittedGeneration || !usable() || session !== admittedSession ||
            currentGeometry != admittedGeometry || (connectionView.visibility == View.VISIBLE) != admittedLocal) return
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
        if (!usable() || !projectedInput() || (::heldInput.isInitialized && heldInput.holding) ||
            edgeMotion.direction(pointer.position, pointer.visibleBounds, pointer.expiresAtNs, SystemClock.elapsedRealtimeNanos()) == 0) {
            root.removeCallbacks(edgeTick); edgeScheduled = false; edgeMotion.stop()
            session?.cancelHeadScroll()
        } else if (!edgeScheduled) { edgeScheduled = true; root.post(edgeTick) }
    }
    private val edgeTick = object : Runnable {
        override fun run() {
            edgeScheduled = false
            val point = pointer.inputPosition()
            if (!usable() || !projectedInput() || (::heldInput.isInitialized && heldInput.holding)) { edgeMotion.stop(); return }
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
        inputGeneration++
        if (::hidInput.isInitialized) hidInput.cancel()
        if (::functionInput.isInitialized) functionInput.cancel()
        if (::gestures.isInitialized) gestures.cancel(SystemClock.uptimeMillis())
        if (::heldOwner.isInitialized) heldOwner.cancel(SystemClock.uptimeMillis())
        else if (::heldInput.isInitialized) heldInput.cancel(SystemClock.uptimeMillis())
        if (::padContact.isInitialized) padContact.clear()
        if (::screen.isInitialized) screen.cancelTouch()
        if (::localInput.isInitialized) localInput.cancel()
        if (::completedInput.isInitialized) completedInput.cancel()
        session?.cancelPendingInput()
        root.removeCallbacks(confirmTap); root.removeCallbacks(edgeTick); edgeScheduled = false; edgeMotion.stop()
        publishHeadPointer()
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (::padContact.isInitialized && padContact.accept(event)) return true
        return super.dispatchGenericMotionEvent(event)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val trace = HidDecisionTrace.capture {
            if (::hidPanel.isInitialized && hidPanel.visibility == View.VISIBLE && connectionView.visibility == View.VISIBLE)
                HidDecisionTrace.Event(event.deviceId, event.keyCode, event.scanCode, event.action, event.repeatCount,
                    event.isCanceled, event.downTime, event.eventTime).also { it.record(HidDecisionTrace.Step.ARRIVAL) }
            else null
        }
        if (::hidDevices.isInitialized && hidDevices.accept(event, trace)) return true
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
        .put("longClassifications", longClassifications)
        .put("heldInputAccepted", heldInput.accepted).put("heldInputRejected", heldInput.rejected)
        .put("heldInputCanceled", heldInput.canceled).put("holdingInput", heldInput.holding)
        .put("padMotionDowns", padContact.downs).put("padMotionReleases", padContact.releases)
        .put("padMotionCancels", padContact.cancels).put("padMotionDeviceLosses", padContact.deviceLosses)
        .put("padContinuousSource", if (padContact.downs > 0) "android-touchpad-edges-observed" else "not-observed")
        .put("physicalPadHoldReleaseDragPass", false)
        .put("nativeTapNormalizationSamples", tapDelaySamples).put("nativeTapNormalizationMaxMs", tapDelayMaxMs)
        .put("nativeTapNormalizationMeanMs", if (tapDelaySamples == 0L) 0.0 else tapDelayTotalMs.toDouble() / tapDelaySamples)
        .put("pointerXGain", settings.pointerXGain).put("pointerYGain", settings.pointerYGain)
        .put("headScrollEnabled", settings.headScrollEnabled).put("nominalScrollPixelsPerSecond", settings.scrollSpeed)
        .put("streaming", streaming).put("opticalOrErgonomicPass", false)

    private fun connect() {
        if (!pairingPanel.isPaired()) { showStatus("Pair with the Phone before connecting"); return }
        if (session != null || connectRequested) return
        if (!screen.holder.surface.isValid) { showStatus(getString(R.string.display_unavailable)); return }
        connectRequested = true
        connectButton.text = getString(R.string.disconnect)
        showStatus(getString(R.string.connecting))
        sessionClient?.connect()
    }

    private fun observePeer(state: SessionState) {
        if (!pairingPanel.isPaired() || pairingPanel.forgetting) return
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
            connectButton.text = getString(R.string.disconnect)
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
        connectButton.text = getString(R.string.disconnect)
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
            } } }, applicationContext, onReceiverInput = { source, event -> runOnUiThread {
                if (session === source && usable()) completedInput.accept(event)
            } })
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
        super.onResume(); resumed = true; pointer.start(); longTouch.start(); padContact.start()
        hidDevices.start()
        if (hasWindowFocus()) functionInput.start()
        val generation = ++clientGeneration
        sessionClient = PairedSessionClient(File(filesDir, "session"), { state ->
            if (resumed && generation == clientGeneration) observePeer(state)
        }, {
            if (resumed && generation == clientGeneration && session == null) {
                connectRequested = false; connectButton.text = getString(R.string.connect)
                showStatus("Paired Phone unavailable. Open EyeProjector on the Phone and check its setup and network.")
            }
        }, deliver = { action -> runOnUiThread { action() } }).also { it.start() }
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (::root.isInitialized) {
            if (!hasFocus) { cancelInput(); pointer.stop(); if (::longTouch.isInitialized) longTouch.stop(); if (::padContact.isInitialized) padContact.stop(); if (::functionInput.isInitialized) functionInput.stop() }
            else if (resumed) { pointer.start(); longTouch.start(); padContact.start(); functionInput.start() }
        }
    }
    override fun onPause() {
        resumed = false; hidDevices.stop(); functionInput.stop(); longTouch.stop(); padContact.stop(); pointer.stop()
        val epoch = attachment.epoch ?: peerSession?.takeIf { it.phase.active }?.epoch
        disconnect(notifyPeer = false)
        clientGeneration++; sessionClient?.close(epoch); sessionClient = null
        super.onPause()
    }
    override fun onDestroy() { hidDevices.stop(); functionInput.stop(); longTouch.stop(); padContact.stop(); disconnect(); pairingPanel.close(); super.onDestroy() }
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
