package com.code2hack.eyeprojector.directrg

import android.app.ActivityManager
import android.app.Activity
import android.Manifest
import android.content.pm.PackageManager
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.ComponentName
import android.content.ServiceConnection
import android.os.IBinder
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
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.code2hack.eyeprojector.protocol.AnchorMessage
import com.code2hack.eyeprojector.protocol.AnchorChoice
import com.code2hack.eyeprojector.protocol.AnchorRegistrationResult
import com.code2hack.eyeprojector.protocol.ReceiverUsabilityMode
import com.code2hack.eyeprojector.protocol.Touch
import com.code2hack.eyeprojector.protocol.CapturedFunctionIntent
import com.code2hack.eyeprojector.protocol.FunctionIntent
import com.code2hack.eyeprojector.protocol.FunctionIntentContext
import com.code2hack.eyeprojector.protocol.FunctionSource
import com.code2hack.eyeprojector.protocol.HeadPointerState
import com.code2hack.eyeprojector.protocol.ReceiverInputKind
import com.code2hack.eyeprojector.protocol.Fit
import com.code2hack.eyeprojector.protocol.Geometry
import com.code2hack.eyeprojector.protocol.Navigation
import com.code2hack.eyeprojector.protocol.Scroll
import com.code2hack.eyeprojector.protocol.PairingPanel
import com.code2hack.eyeprojector.protocol.PeerRole
import com.code2hack.eyeprojector.protocol.ActivationGoal
import com.code2hack.eyeprojector.protocol.ReceiverDemand
import com.code2hack.eyeprojector.protocol.ReceiverIncarnation
import java.io.File
import java.util.UUID
import com.code2hack.eyeprojector.directrg.localcamera.forwarding.ReceiverCameraPermissionRequest
import org.json.JSONObject

class ReceiverActivity : Activity(), SurfaceHolder.Callback {
    /** Optional instrumentation observer; never owns, consumes or retains the input events. */
    internal interface NativeInputObserver {
        fun key(activityId: Int, event: KeyEvent, receiptUptimeMs: Long, receiptElapsedNs: Long)
        fun motion(activityId: Int, entry: String, event: MotionEvent, receiptUptimeMs: Long, receiptElapsedNs: Long)
        fun stage(activityId: Int, stage: String, action: PadGestureRecognizer.Action?, deadlineUptimeMs: Long?,
                  outcome: String?, local: Boolean?, generation: Long, receiptUptimeMs: Long, receiptElapsedNs: Long)
    }
    companion object {
        @Volatile internal var nativeInputObserver: NativeInputObserver? = null
    }
    private inline fun observeNativeInput(read: (NativeInputObserver) -> Unit) {
        val observer = nativeInputObserver ?: return
        try { read(observer) } catch (_: Throwable) { /* Diagnostics cannot change input handling. */ }
    }
    private fun nativeInputStage(stage: String, action: PadGestureRecognizer.Action? = null,
                                 due: Long? = null, outcome: String? = null, local: Boolean? = null) {
        observeNativeInput { it.stage(System.identityHashCode(this), stage, action, due, outcome, local,
            inputGeneration, SystemClock.uptimeMillis(), SystemClock.elapsedRealtimeNanos()) }
    }
    private lateinit var root: FrameLayout
    private lateinit var screen: ProjectorSurface
    private lateinit var pointer: PointerOverlay
    private lateinit var anchorOverlay: AnchorRegistrationOverlay
    private lateinit var anchorRegistration: AnchorRegistrationController<ProjectionSession>
    private var anchorOffer: AnchorMessage.Offer? = null
    private lateinit var connectionView: ScrollView
    private lateinit var pairingPanel: PairingPanel
    private lateinit var connectButton: Button
    private val presentation = SessionPresentation()
    private var owner: ReceiverSessionOwner? = null
    private var bound = false
    private var serviceBinder: ReceiverSessionService.LocalBinder? = null
    // One opaque identity per actual Activity instance; request codes never reconstruct it.
    private val cameraPermissionHost = Any()
    private val cameraPermissionRequests = mutableMapOf<Int,
        Pair<ReceiverSessionService.LocalBinder, ReceiverCameraPermissionRequest>>()
    private lateinit var launchScope: ReceiverLaunchScope
    private var visibleActivation: String? = null
    private var bindingValid = true
    private val registeredActivations = mutableSetOf<String>()
    private var connectRequested = false
    private var disconnectBeforeBinding = false
    private var connectBeforeBinding = false
    private var pendingForget: (() -> Unit)? = null
    private var pendingExit: Pair<ReceiverLaunchScope, String?>? = null
    private var appliedDemand: ReceiverDemand? = null
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val local = binder as ReceiverSessionService.LocalBinder
            serviceBinder = local; owner = local.owner
            pendingExit?.let { original ->
                val done = pendingForget; pendingForget = null
                owner?.disconnect(complete = done, scope = original.first, activationId = original.second)
                return
            }
            if (!bindingValid) return
            val requestedWake = intent.getStringExtra(ReceiverWake.ACTIVATION)
            if (requestedWake != null && !validWake(local, intent)) {
                bindingValid = false; finish(); return
            }
            registerTask(launchScope, visibleActivation)
            val forget = pendingForget; pendingForget = null
            if (forget != null) owner?.disconnect(complete = forget, scope = launchScope, activationId = visibleActivation)
            else if (disconnectBeforeBinding) owner?.disconnect(scope = launchScope, activationId = visibleActivation)
            else if (launchScope.trigger.isNotEmpty()) owner?.launch(launchScope)
            val canceledBeforeBinding = forget != null || disconnectBeforeBinding
            disconnectBeforeBinding = false; connectBeforeBinding = false
            if (resumed && !canceledBeforeBinding) { attachCameraPermissionHost(); attachPresentation() }
            // A recreated view needs its first attachment even when goal selection was restored.
            if (!canceledBeforeBinding && requestedWake != null) ReceiverWake.readIntent(intent)?.let(::applyReceiverDemand)
        }
        override fun onServiceDisconnected(name: ComponentName) {
            serviceBinder?.detachCameraPermissionHost(cameraPermissionHost)
            owner = null; serviceBinder = null
            showSnapshot(ReceiverSessionOwner.Snapshot(null, null, null, false, "DISCONNECTED", null))
        }
    }
    private val sessionView = object : ReceiverSessionOwner.View {
        override fun state(snapshot: ReceiverSessionOwner.Snapshot) { if (resumed) showSnapshot(snapshot) }
        override fun input(source: ProjectionSession, event: com.code2hack.eyeprojector.protocol.ReceiverInput) {
            if (session === source && usable()) completedInput.accept(event)
        }
        override fun anchor(source: ProjectionSession, message: AnchorMessage): Boolean = anchorMessage(source, message)
    }
    private fun attachPresentation() {
        if (!bindingValid || !resumed || !hasWindowFocus()) return
        owner?.attach(sessionView, screen.holder.surface.takeIf { it.isValid })
        owner?.presentation(sessionView, usable(), localInputVisible())
    }
    private fun attachCameraPermissionHost() {
        val originalBinder = serviceBinder ?: return
        if (!resumed || !bindingValid) return
        originalBinder.attachCameraPermissionHost(cameraPermissionHost) { originalRequest ->
            runOnUiThread {
                if (serviceBinder !== originalBinder || !resumed ||
                    originalRequest.hostIdentity !== cameraPermissionHost ||
                    !originalBinder.isCameraPermissionRequestCurrent(originalRequest)) return@runOnUiThread
                cameraPermissionRequests[originalRequest.requestCode] = originalBinder to originalRequest
                try { requestPermissions(arrayOf(Manifest.permission.CAMERA), originalRequest.requestCode) }
                catch (error: Throwable) {
                    cameraPermissionRequests.remove(originalRequest.requestCode)
                    originalBinder.cameraPermissionRequestFailed(originalRequest, error)
                }
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val captured = cameraPermissionRequests.remove(requestCode) ?: return
        val index = permissions.indexOf(Manifest.permission.CAMERA)
        val granted = index >= 0 && index < grantResults.size && grantResults[index] == PackageManager.PERMISSION_GRANTED
        captured.first.cameraPermissionResult(captured.second, granted)
    }
    private fun validWake(local: ReceiverSessionService.LocalBinder, delivered: Intent): Boolean {
        val d = local.feature.directive ?: return false
        return bindingValid && pendingExit == null && local.feature.permits(d) &&
            ReceiverWake.readIntent(delivered) == local.feature.demand()
    }
    private fun applyReceiverDemand(demand: ReceiverDemand) {
        val source = owner ?: return
        if (!bindingValid || pendingExit != null || source.currentDemand() != demand || appliedDemand == demand) return
        if (registrationActive()) cancelInput()
        appliedDemand = demand // Before any callback-producing UI work; repeated intents cannot reset the redraw.
        if (demand.goal == ActivationGoal.PROJECTED && presentation.panelVisible) {
            // Fence hidden projected output before cancellation/layout can publish another UI fact.
            source.presentation(sessionView, false, false)
            source.attachSurface(sessionView, null)
            if (!bindingValid || pendingExit != null || owner !== source || source.currentDemand() != demand) return
            presentation.requestProjected()
            connectionView.visibility = View.GONE
            cancelInput(); resize()
            if (resumed && source.currentDemand() == demand) attachPresentation()
        } else if (demand.goal == ActivationGoal.PROJECTED && source.snapshot().state == "STREAMING") {
            // Already current projected pixels can satisfy a new demand without a fresh encoded frame.
            source.presentation(sessionView, usable(), false)
        } else if (resumed && source.currentDemand() == demand) attachPresentation()
    }
    private fun registerTask(scope: ReceiverLaunchScope, activation: String?) {
        if (scope.taskId != taskId) return // Retain unknown old association; never reconstruct it from numeric reuse.
        val task = getSystemService(ActivityManager::class.java).appTasks.firstOrNull {
            runCatching { it.taskInfo?.taskId == taskId }.getOrDefault(false)
        }
        serviceBinder?.register(scope, activation, task)
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val local = serviceBinder ?: return
        if (!validWake(local, intent)) return
        setIntent(intent) // This is delivery state, not an attempt to rewrite the framework task root.
        ReceiverWake.readIntent(intent)?.let(::applyReceiverDemand)
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("activationScope", launchScope.id); outState.putString("activationTrigger", launchScope.trigger)
        outState.putInt("activationTask", launchScope.taskId); outState.putString("visibleActivation", visibleActivation)
        outState.putBoolean("panelVisible", presentation.panelVisible); outState.putBoolean("hadLive", presentation.live)
        appliedDemand?.let { d ->
            outState.putString("appliedH", d.activationId); outState.putString("appliedA", d.ownerEpoch)
            outState.putLong("appliedWake", d.wakeRevision); outState.putString("appliedGoal", d.goal.name)
            outState.putString("appliedInc", d.incarnation.id); outState.putLong("appliedIncSequence", d.incarnation.sequence)
        }
        super.onSaveInstanceState(outState)
    }
    private val localButtons = mutableListOf<Button>()
    private lateinit var status: TextView
    private var connectionStatus = ""
    private lateinit var settings: LocalInputSettings
    private lateinit var gestures: PadGestureRecognizer
    private lateinit var localInput: LocalPointerDispatcher
    private lateinit var longTouch: NativePadLongTouch
    private lateinit var heldInput: HeldPointerDispatcher
    private lateinit var padContact: NativePadContact
    private lateinit var shellPadContact: NativePadShellContact
    private lateinit var hidInput: HidKeyInput
    private lateinit var hidDevices: AndroidHidInput
    private lateinit var hidPanel: HidBindingsPanel
    private lateinit var mainControls: LinearLayout
    private lateinit var heldOwner: HeldInputOwner
    private lateinit var classifiedPadLongPress: ClassifiedPadLongPress
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTurnScreenOn(true)
        val restored = savedInstanceState?.getString("activationScope")
        launchScope = ReceiverLaunchScope(restored ?: UUID.randomUUID().toString(),
            savedInstanceState?.getString("activationTrigger") ?: if (intent.hasExtra(ReceiverWake.ACTIVATION)) "" else UUID.randomUUID().toString(),
            if (restored != null) savedInstanceState.getInt("activationTask") else taskId)
        visibleActivation = savedInstanceState?.getString("visibleActivation")
        if (savedInstanceState != null) {
            presentation.restoreSelection(savedInstanceState.getBoolean("panelVisible", true), savedInstanceState.getBoolean("hadLive", false))
            val h = savedInstanceState.getString("appliedH")
            val a = savedInstanceState.getString("appliedA")
            val i = savedInstanceState.getString("appliedInc")
            val goal = ActivationGoal.entries.firstOrNull { it.name == savedInstanceState.getString("appliedGoal") }
            if (h != null && a != null && i != null && goal != null) appliedDemand = ReceiverDemand(h,a,
                savedInstanceState.getLong("appliedWake"), ReceiverIncarnation(i,savedInstanceState.getLong("appliedIncSequence")),goal)
        }
        val preferences = getSharedPreferences("projector-input", MODE_PRIVATE)
        settings = LocalInputSettings({ preferences.getString(it, null) }, { k, v -> preferences.edit().putString(k, v).commit() })
        val hidBindings = HidBindings({ preferences.getString("hid.bindings", null) },
            { value -> preferences.edit().putString("hid.bindings", value).commit() })
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        anchorRegistration = AnchorRegistrationController { source, id, action ->
            val offer = anchorOffer?.takeIf { it.transactionId == id }
            offer != null && source.chooseAnchorRegistration(offer, when (action) {
                AnchorRegistrationAction.CLICK -> AnchorChoice.CLICK
                AnchorRegistrationAction.HOLD -> AnchorChoice.HOLD
                AnchorRegistrationAction.CANCEL -> AnchorChoice.CANCEL
            })
        }
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
            setOnClickListener { if (session != null || connectRequested) disconnect() else connect() }
        }
        localButtons.add(connectButton)
        pairingPanel = PairingPanel(this, PeerRole.GLASSES, { paired ->
            connectButton.visibility = if (paired) View.VISIBLE else View.GONE
        }, { complete ->
            cancelInput()
            val source = owner
            if (source == null) pendingForget = complete else source.disconnect(complete = complete, scope = launchScope, activationId = visibleActivation)
        }, ReceiverPairingOwner.get(this).pairing)
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
        anchorOverlay = AnchorRegistrationOverlay(this, ::chooseAnchorRegistration)
        root.addView(anchorOverlay, FrameLayout.LayoutParams(-1, -1))
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
            else if (localInputVisible()) classifiedPadLongPress.route { event ->
                if (event.phase == Touch.Phase.DOWN && ::completedInput.isInitialized) completedInput.cancel()
                localInput.touch(event)
            }
            else {
                val source = session
                val size = currentGeometry
                if (source == null || size == null || !projectedInput()) null
                else classifiedPadLongPress.route { event ->
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
        classifiedPadLongPress = ClassifiedPadLongPress(heldOwner, SystemClock::uptimeMillis,
            // The Phone's measured timeout is 500 ms; leave time for its long-press callback.
            maxOf(500L, ViewConfiguration.getLongPressTimeout().toLong()) + 100L,
            { deadline, action ->
                val task = Runnable { action(); updateEdgeScroll() }
                root.postDelayed(task, (deadline - SystemClock.uptimeMillis()).coerceAtLeast(0))
                val cancel: () -> Unit = { root.removeCallbacks(task) }
                cancel
            })
        completedInput = CompletedReceiverInput(SystemClock::uptimeMillis, { inputGeneration },
            { usable() && localInputVisible() },
            { heldOwner.cancel(SystemClock.uptimeMillis()) }, localInput::touch, localInput::scroll, localInput::cancel,
            ::completedNavigation)
        shellPadContact = NativePadShellContact(heldOwner,
            { if (usable() && !padContact.holding) Triple(inputGeneration, session,
                Pair(currentGeometry, localInputVisible())) else null },
            { pointer.inputPosition() },
            {
                // The raw long-key source omits short taps: retire this ambiguity without
                // enabling the recognizer's global continuous-contact report mode.
                gestures.cancel(SystemClock.uptimeMillis())
                schedulePadConfirmation()
                classifiedPadLongPress.cancel()
                completedInput.cancel()
            }, ::updateEdgeScroll)
        padContact = NativePadContact(this, { pointer.inputPosition() }, { event ->
            if (usable()) gestures.accept(event, SystemClock.uptimeMillis()) else cancelInput()
            schedulePadConfirmation()
        }, ::cancelInput)
        longTouch = NativePadLongTouch(this) { at ->
            observeNativeInput { it.stage(System.identityHashCode(this), "ai_callback", null, at, null, null,
                inputGeneration, SystemClock.uptimeMillis(), SystemClock.elapsedRealtimeNanos()) }
            if (usable()) gestures.classifyLongPress(at) else gestures.cancel(at)
            schedulePadConfirmation()
        }
        functionInput = NativeFunctionInput(this,
            ::functionContext,
            { gesture -> dispatchFunction(FunctionSource.GLASSES_NATIVE, gesture) },
            capture = ::captureFunction,
        )
        hidInput = HidKeyInput(hidBindings, SystemClock::uptimeMillis,
            ::functionContext,
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
            { gesture -> dispatchFunction(FunctionSource.GLASSES_HID, gesture) },
            { if (::hidPanel.isInitialized) hidPanel.refresh() },
            ::hoveredHidAction,
            functionCapture = ::captureFunction,
            navigation = ::dispatchNavigation,
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
        shellPadContact.start()
        showStatus(getString(R.string.disconnected))
        val service = Intent(this, ReceiverSessionService::class.java)
        startForegroundService(service)
        bound = bindService(service, serviceConnection, BIND_AUTO_CREATE)
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
        updatePointerBounds()
        publishHeadPointer()
    }

    private fun updatePointerBounds() {
        val fit = currentGeometry?.let { Fit.inside(root.width, root.height, it) }
        if (fit != null && projectedInput()) {
            pointer.bounds(fit.left.toFloat(), fit.top.toFloat(), (fit.left + fit.width).toFloat(), (fit.top + fit.height).toFloat(), display?.rotation ?: 0)
        } else pointer.bounds(0f, 0f, root.width.toFloat(), root.height.toFloat(), display?.rotation ?: 0)
    }

    /** Passive snapshot: inputPosition() publishes too, so never call it from this producer. */
    private fun publishHeadPointer() {
        owner?.presentation(sessionView, usable(), localInputVisible())
        val source = session ?: return
        if (!::pointer.isInitialized || root.width <= 0 || root.height <= 0) return
        val position = pointer.position
        val size = currentGeometry
        val fit = size?.let { Fit.inside(root.width, root.height, it) }
        val projected = !localInputVisible()
        val available = usable() && position.available && (!projected || projectedInput())
        source.headPointer(HeadPointerState(inputGeneration, 0, available, projected,
            position.x, position.y, fit, size), pointer.expiresAtNs)
    }

    private data class FunctionCapture(val source: ProjectionSession, val context: FunctionIntentContext)

    private fun functionContext(): Any? = if (usable() && !registrationActive()) Triple(inputGeneration, session, projectionContext) else null

    /** Read the original hover once. Pointer callbacks may retire ownership, so recheck that capture. */
    private fun captureFunction(expected: Any): FunctionCapture? {
        val source = session ?: return null
        val original = functionContext() ?: return null
        if (original != expected) return null
        val position = pointer.inputPosition()
        publishHeadPointer()
        val size = currentGeometry
        val fit = size?.takeIf { root.width > 0 && root.height > 0 }?.let { Fit.inside(root.width, root.height, it) }
        val projected = !localInputVisible()
        val point = HeadPointerState(inputGeneration, 0, usable() && position.available && (!projected || fit != null && projectedInput()),
            projected, position.x, position.y, fit, if (fit != null) size else null)
        if (functionContext() != original || session !== source) return null
        val captured = source.captureFunctionContext(point) ?: return null
        return FunctionCapture(source, captured)
    }

    private fun dispatchFunction(source: FunctionSource, gesture: FunctionGestureRecognizer.Gesture) {
        val captured = gesture.original as FunctionCapture
        if (!usable() || session !== captured.source || inputGeneration != captured.context.pointer.revision) return
        val intent = FunctionIntent(gesture.kind, source, gesture.gestureId, gesture.sequence, captured.context)
        captured.source.functionIntent(CapturedFunctionIntent(intent, captured.source))
    }

    private fun usable() = resumed && hasWindowFocus()
    private fun registrationActive() = ::anchorRegistration.isInitialized && anchorRegistration.active
    private fun localInputVisible() = connectionView.visibility == View.VISIBLE || registrationActive()
    private fun projectedInput() = streaming && presentation.projectedInput && !registrationActive()

    private fun currentRegistrationIntent(source: ProjectionSession, original: FunctionIntentContext): Boolean {
        if (!usable() || session !== source || !projectedInput() ||
            original.pointer.geometry != currentGeometry || root.width <= 0 || root.height <= 0 ||
            original.pointer.fit != currentGeometry?.let { Fit.inside(root.width, root.height, it) }) return false
        val current = source.captureFunctionContext(original.pointer)?.usability ?: return false
        return current.mode == ReceiverUsabilityMode.PROJECTED && current.ownerEpoch == original.usability.ownerEpoch &&
            current.incarnation == original.usability.incarnation && current.inputRevision == original.usability.inputRevision &&
            inputGeneration == original.usability.inputRevision
    }

    private fun anchorMessage(source: ProjectionSession, message: AnchorMessage): Boolean {
        when (message) {
            is AnchorMessage.Offer -> {
                val old = anchorOffer
                if (registrationActive() && anchorRegistration.offer?.source === source &&
                    old?.transactionId == message.transactionId && old.intent == message.intent) return true
                if (!currentRegistrationIntent(source, message.intent.context)) {
                    source.chooseAnchorRegistration(message, AnchorChoice.CANCEL)
                    return true
                }
                anchorOffer = message
                val bounds = message.highlight
                if (!anchorRegistration.show(source, message.transactionId, message.intent.context,
                        AnchorRegistrationHighlight(bounds.left, bounds.top, bounds.right, bounds.bottom))) {
                    anchorOffer = null
                    source.chooseAnchorRegistration(message, AnchorChoice.CANCEL)
                    return true
                }
                // Reserve before cancellation; advanceInputRevision allocates without publication.
                cancelInputState(publish = false)
                val incarnation = serviceBinder?.feature?.incarnation
                if (!usable() || session !== source || incarnation == null ||
                    !anchorRegistration.enteredChooser(source, incarnation, currentGeometry, inputGeneration) ||
                    owner?.openAnchorRegistration(source, message, inputGeneration) != true) {
                    cancelInput()
                    return true
                }
                anchorRegistration.offer?.let { anchorOverlay.show(it.highlight) }
                // OPEN is registered before bounds callbacks can publish the exact new local mode.
                updatePointerBounds()
                publishHeadPointer()
                return true
            }
            is AnchorMessage.Result -> {
                val offer = anchorOffer
                if (offer?.intent == message.intent && anchorRegistration.completed(source, message.transactionId)) {
                    anchorOffer = null
                    anchorOverlay.dismiss()
                    cancelInputState()
                    updatePointerBounds()
                    when (message.result) {
                        AnchorRegistrationResult.SAVED_CLICK -> Toast.makeText(this, "Anchor saved: Click", Toast.LENGTH_SHORT).show()
                        AnchorRegistrationResult.SAVED_HOLD -> Toast.makeText(this, "Anchor saved: Hold", Toast.LENGTH_SHORT).show()
                        AnchorRegistrationResult.UNAVAILABLE -> Toast.makeText(this, "Anchor unavailable", Toast.LENGTH_SHORT).show()
                        AnchorRegistrationResult.CANCELED -> Unit
                    }
                } else if (message.result == AnchorRegistrationResult.UNAVAILABLE &&
                    currentRegistrationIntent(source, message.intent.context)) {
                    Toast.makeText(this, "Anchor unavailable", Toast.LENGTH_SHORT).show()
                }
                return true
            }
            else -> return false
        }
    }

    private fun chooseAnchorRegistration(action: AnchorRegistrationAction) {
        if (!registrationActive()) return
        anchorRegistration.choose(action)
        if (registrationActive()) anchorOverlay.waiting()
        else {
            anchorOffer = null
            anchorOverlay.dismiss()
            cancelInputState()
            updatePointerBounds()
        }
    }

    private fun cancelAnchorRegistration(): Boolean {
        val active = registrationActive()
        if (active) anchorRegistration.cancel()
        anchorOffer = null
        if (::anchorOverlay.isInitialized) anchorOverlay.dismiss()
        return active
    }

    /** Dedicated destinations shared by native and HID source adapters. */
    internal fun dispatchNavigation(action: ContextualNavigationAction) {
        when (action) {
            ContextualNavigationAction.BACK -> contextualBack()
            ContextualNavigationAction.HOME -> contextualHome()
            ContextualNavigationAction.LOCK -> sleepGlasses()
        }
    }

    private fun completedNavigation(kind: ReceiverInputKind) {
        when (kind) {
            ReceiverInputKind.PANEL -> showControlPanel()
            ReceiverInputKind.BACK -> dispatchNavigation(ContextualNavigationAction.BACK)
            ReceiverInputKind.HOME -> dispatchNavigation(ContextualNavigationAction.HOME)
            ReceiverInputKind.LOCK -> dispatchNavigation(ContextualNavigationAction.LOCK)
            ReceiverInputKind.EXIT -> exitToLauncher()
            ReceiverInputKind.FUNCTION_DOUBLE, ReceiverInputKind.SLEEP -> Unit // Reserved legacy IDs.
            else -> Unit
        }
    }

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
    internal fun contextualHome() {
        if (registrationActive()) cancelInput()
        if (!usable()) return
        if (projectionContext) {
            if (!projectedInput()) return
            cancelInput()
            session?.navigate(Navigation.CONTEXTUAL_HOME)
        } else if (::hidPanel.isInitialized && hidPanel.visibility == View.VISIBLE) {
            showControlPanel()
        } else exitToLauncher()
    }
    private fun sleepGlasses() {
        if (!usable()) return
        cancelInput()
        // The existing shell reader owns power injection; Activity pause retains the session.
        if (!shellPadContact.sleep()) Log.w("EyeProjectorPad", "Glasses sleep needs the local shell reader")
    }
    internal fun contextualBack() {
        if (registrationActive()) { cancelInput(); return }
        cancelInput()
        if (presentation.panelVisible && ::hidPanel.isInitialized && hidPanel.visibility == View.VISIBLE) { showControlPanel(); return }
        if (presentation.panelVisible) {
            if (presentation.back()) { connectionView.visibility = View.GONE; resize() }
            else { finishProjection() }
        } else remoteBack()
    }
    private fun finishProjection() {
        val original = visibleActivation
        val scope = launchScope
        resumed = false; bindingValid = false; cancelInput()
        cancelOriginalOnExit(scope, original)
        finish()
    }
    internal fun exitToLauncher() {
        // End local interception and invalidate peer callbacks before leaving this Activity.
        resumed = false
        cancelInput()
        hidDevices.stop(); functionInput.stop(); longTouch.stop(); padContact.stop(); pointer.stop()
        val original = visibleActivation
        val scope = launchScope
        bindingValid = false
        cancelOriginalOnExit(scope, original)
        startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }

    private fun cancelOriginalOnExit(scope: ReceiverLaunchScope, activation: String?) {
        pendingExit = scope to activation
        val source = owner
        if (source != null) source.disconnect(scope = scope, activationId = activation)
        else {
            // Delivery survives unbinding/Activity destruction and carries only this original scope.
            startForegroundService(ReceiverSessionService.cancelIntent(this, scope, activation))
        }
    }

    @Deprecated("Android navigation delegates to the current projection/panel context")
    override fun onBackPressed() { contextualBack() }
    private fun remoteTap(x: Float, y: Float, eventTimeMs: Long = SystemClock.uptimeMillis()): Boolean {
        if (registrationActive()) return true
        if (!usable() || !projectedInput()) return false
        val size = currentGeometry ?: return false
        val target = Fit.inside(root.width, root.height, size).touch(x, y, size) ?: return false
        return session?.tap(target, eventTimeMs) == true
    }
    private fun remoteScroll(x: Float, y: Float, pixels: Int, eventTimeMs: Long = SystemClock.uptimeMillis(), headMotion: Boolean = false): Boolean {
        if (registrationActive()) return true
        if (!usable() || !projectedInput() || pixels == 0) return false
        val size = currentGeometry ?: return false
        val target = Fit.inside(root.width, root.height, size).touch(x, y, size) ?: return false
        return session?.scroll(Scroll(target, pixels), eventTimeMs, headMotion) == true
    }

    private fun dispatchPadAction(action: PadGestureRecognizer.Action) {
        nativeInputStage("dispatch_enter", action)
        when (action) {
            is PadGestureRecognizer.Action.LongPress -> {
                longClassifications++
                // Real sustained input already owns its DOWN and eventual physical release.
                if (padContact.holding || shellPadContact.holding) {
                    nativeInputStage("dispatch_result", action, outcome = "sustained-contact")
                    return
                }
                if (action.markerDownTimeMs == null || action.position?.available != true) {
                    nativeActionsRejected++
                    nativeInputStage("dispatch_result", action, outcome = "rejected-no-marker-target")
                    return
                }
            }
            is PadGestureRecognizer.Action.Contact -> {
                if (action.event.phase == PadGestureRecognizer.Phase.DOWN && usable()) {
                    classifiedPadLongPress.cancel()
                    shellPadContact.cancel()
                }
                val accepted = if (action.event.phase == PadGestureRecognizer.Phase.CANCEL || usable())
                    heldOwner.acceptNative(action.event) else false
                if (accepted) nativeActionsSubmitted++ else nativeActionsRejected++
                nativeInputStage("dispatch_result", action, outcome = if (accepted) "submitted-to-held-owner" else "rejected")
                updateEdgeScroll(); return
            }
            is PadGestureRecognizer.Action.Back -> {
                contextualBack()
                nativeInputStage("dispatch_result", action, outcome = "delegated-contextual-back")
                return
            }
            else -> Unit
        }
        if (!usable()) {
            nativeActionsRejected++
            nativeInputStage("dispatch_result", action, outcome = "rejected-unusable")
            return
        }
        val admittedGeneration = inputGeneration
        val admittedSession = session
        val admittedGeometry = currentGeometry
        val admittedLocal = localInputVisible()
        // Pointer publication and old-target CANCEL may synchronously replace the view/session.
        pointer.inputPosition()
        if (inputGeneration != admittedGeneration || !usable() || session !== admittedSession ||
            currentGeometry != admittedGeometry || localInputVisible() != admittedLocal) {
            nativeInputStage("dispatch_result", action, outcome = "canceled-context-change", local = admittedLocal)
            return
        }
        if (::completedInput.isInitialized) completedInput.cancel()
        classifiedPadLongPress.cancel()
        heldOwner.cancel(action.timeMs)
        if (inputGeneration != admittedGeneration || !usable() || session !== admittedSession ||
            currentGeometry != admittedGeometry || localInputVisible() != admittedLocal) {
            nativeInputStage("dispatch_result", action, outcome = "canceled-context-change", local = admittedLocal)
            return
        }
        val submitted = when (action) {
            is PadGestureRecognizer.Action.LongPress -> classifiedPadLongPress.start(action) {
                inputGeneration == admittedGeneration && usable() && session === admittedSession &&
                    currentGeometry == admittedGeometry &&
                    localInputVisible() == admittedLocal
            }
            is PadGestureRecognizer.Action.Tap -> {
                val delay = SystemClock.uptimeMillis() - action.downTimeMs
                tapDelaySamples++; tapDelayTotalMs += delay; tapDelayMaxMs = maxOf(tapDelayMaxMs, delay)
                if (tapDelaySamples <= 64) Log.d("EyeProjectorPad",
                    "tapDispatch ageMs=$delay fallbackMs=${action.timeMs - action.upTimeMs}")
                val point = action.position
                if (point?.available != true) false
                else if (localInputVisible()) localInput.tap(action)
                else remoteTap(point.x, point.y, action.downTimeMs + action.timeMs - action.upTimeMs)
            }
            is PadGestureRecognizer.Action.Scroll -> {
                val point = action.position
                if (point?.available != true) false
                else if (localInputVisible()) localInput.scroll(point, action.pixels)
                else remoteScroll(point.x, point.y, action.pixels, action.timeMs)
            }
            else -> false
        }
        if (submitted) nativeActionsSubmitted++ else {
            nativeActionsRejected++
            Log.w("EyeProjectorPad", "Native pointer action was not submitted")
        }
        nativeInputStage("dispatch_result", action, outcome = if (submitted) "submitted" else "rejected", local = admittedLocal)
        if (action is PadGestureRecognizer.Action.LongPress) updateEdgeScroll()
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
        val deadline = gestures.deadline
        nativeInputStage("deadline", due = deadline)
        deadline?.let { root.postDelayed(confirmTap, (it - SystemClock.uptimeMillis()).coerceAtLeast(0)) }
    }
    private fun cancelInput() {
        val dismissed = cancelAnchorRegistration()
        cancelInputState()
        if (dismissed && root.width > 0 && root.height > 0) updatePointerBounds()
    }

    /** Opening uses this body before its correlated OPEN, without publishing the new revision. */
    private fun cancelInputState(publish: Boolean = true) {
        nativeInputStage("cancel")
        inputGeneration = owner?.advanceInputRevision() ?: (inputGeneration + 1)
        if (::hidInput.isInitialized) hidInput.cancel()
        if (::functionInput.isInitialized) functionInput.cancel()
        if (::classifiedPadLongPress.isInitialized) classifiedPadLongPress.cancel()
        if (::shellPadContact.isInitialized) shellPadContact.cancel()
        if (::gestures.isInitialized) gestures.cancel(SystemClock.uptimeMillis())
        if (::heldOwner.isInitialized) heldOwner.cancel(SystemClock.uptimeMillis())
        else if (::heldInput.isInitialized) heldInput.cancel(SystemClock.uptimeMillis())
        if (::padContact.isInitialized) padContact.clear()
        if (::screen.isInitialized) screen.cancelTouch()
        if (::localInput.isInitialized) localInput.cancel()
        if (::completedInput.isInitialized) completedInput.cancel()
        session?.cancelPendingInput()
        root.removeCallbacks(confirmTap); root.removeCallbacks(edgeTick); edgeScheduled = false; edgeMotion.stop()
        if (publish) publishHeadPointer()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        observeNativeInput { it.motion(System.identityHashCode(this), "touch_motion", event,
            SystemClock.uptimeMillis(), SystemClock.elapsedRealtimeNanos()) }
        return super.dispatchTouchEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        observeNativeInput { it.motion(System.identityHashCode(this), "generic_motion", event,
            SystemClock.uptimeMillis(), SystemClock.elapsedRealtimeNanos()) }
        if (::padContact.isInitialized && padContact.accept(event)) return true
        return super.dispatchGenericMotionEvent(event)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        observeNativeInput { it.key(System.identityHashCode(this), event,
            SystemClock.uptimeMillis(), SystemClock.elapsedRealtimeNanos()) }
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

    /** Test-only holder: original references stay private and never resolve a successor at teardown. */
    internal interface FixtureTeardownTarget {
        val sessionStartedMonoNs: Long
        fun disconnect(onCompletion: () -> Unit)
        fun observe(): FixtureTeardownObservation
    }
    internal data class FixtureTeardownObservation(
        val readStartMonoNs: Long,
        val readEndMonoNs: Long,
        val originalSessionStartedMonoNs: Long,
        val ownerState: String,
        val ownerSessionPresent: Boolean,
        val ownerSessionMatchesOriginal: Boolean,
        val currentSessionStartedMonoNs: Long?,
        val currentActivationMatchesOriginal: Boolean?,
        val originalTriggerCaptured: Boolean,
        val originalActivationCaptured: Boolean,
        val originalTriggerCanceled: Boolean?,
        val originalTriggerAcknowledged: Boolean?,
        val originalActivationCancellationRecorded: Boolean?,
        val originalActivationPendingCancel: Boolean?,
    )

    /** Main-thread, fixture-local capture; absent/inconsistent provenance is diagnostic UNKNOWN. */
    internal fun captureFixtureTeardownTarget(expectedSessionStartedMonoNs: Long): FixtureTeardownTarget? {
        val capturedOwner = owner ?: return null
        val capturedBinder = serviceBinder ?: return null
        if (capturedBinder.owner !== capturedOwner || !::launchScope.isInitialized) return null
        val capturedSession = session ?: return null
        val memory = capturedOwner.snapshot()
        if (memory.session !== capturedSession || capturedSession.observationStartedMonoNs != expectedSessionStartedMonoNs) return null
        val capturedScope = launchScope.copy()
        val capturedActivation = visibleActivation
        val capturedFeature = capturedBinder.feature
        val trigger = capturedScope.trigger.takeIf { it.isNotEmpty() }
        val capturedTrigger = trigger?.let { capturedFeature.state.triggers[it] }
        if (trigger != null && capturedTrigger?.scope != capturedScope) return null
        val mappedActivation = capturedTrigger?.activation
        if (mappedActivation != null && capturedActivation != null && mappedActivation != capturedActivation) return null
        if (mappedActivation != null && memory.activationId != null && mappedActivation != memory.activationId) return null
        if (capturedActivation != null && memory.activationId != capturedActivation) return null
        if (trigger == null && capturedActivation == null) return null
        // An empty wake trigger is absence of T, not a trigger awaiting acknowledgment.
        // Pass null explicitly with captured H; never use the owner's currentScope default.
        val dispatchScope = capturedScope.takeIf { trigger != null }
        val capturedStart = capturedSession.observationStartedMonoNs
        return object : FixtureTeardownTarget {
            override val sessionStartedMonoNs = capturedStart
            override fun disconnect(onCompletion: () -> Unit) {
                capturedOwner.disconnect(notifyPeer = true, complete = {
                    try { onCompletion() } catch (_: Throwable) { /* Observation cannot break reconciliation. */ }
                }, scope = dispatchScope, activationId = capturedActivation)
            }
            override fun observe(): FixtureTeardownObservation {
                val begin = System.nanoTime()
                val current = capturedOwner.snapshot()
                val state = capturedFeature.state
                val originalTrigger = trigger?.let { state.triggers[it] }?.takeIf { it.scope == capturedScope }
                return FixtureTeardownObservation(begin, System.nanoTime(), capturedStart, current.state,
                    current.session != null, current.session === capturedSession,
                    current.session?.observationStartedMonoNs,
                    capturedActivation?.let { current.activationId == it },
                    trigger != null, capturedActivation != null, originalTrigger?.canceled, originalTrigger?.acknowledged,
                    capturedActivation?.let { it in state.canceled }, capturedActivation?.let { it in state.pendingCancel })
            }
        }
    }

    /** Main-thread diagnostic reads only; no pairing/config I/O or activation/presentation mutation. */
    internal fun startupDiagnosticSnapshot(): Map<String, Any?> {
        val memory = owner?.snapshot()
        val feature = serviceBinder?.feature
        val directive = feature?.directive
        val reported = feature?.presentation
        val screenReady = ::screen.isInitialized
        return mapOf(
            "bound" to bound, "binderAvailable" to (serviceBinder != null), "bindingValid" to bindingValid,
            "resumed" to resumed, "focused" to hasWindowFocus(), "finishing" to isFinishing, "destroyed" to isDestroyed,
            "screenInitialized" to screenReady, "surfaceValid" to if (screenReady) screen.holder.surface.isValid else null,
            "connectRequested" to connectRequested, "connectBeforeBinding" to connectBeforeBinding,
            "disconnectBeforeBinding" to disconnectBeforeBinding,
            "ownerState" to memory?.state, "ownerRequested" to memory?.requested,
            "sessionPresent" to memory?.let { it.session != null }, "peerPhase" to memory?.peer?.phase?.name,
            "peerDetail" to memory?.peer?.detail, "visibleActivation" to visibleActivation,
            "currentActivation" to memory?.activationId, "activationDesired" to directive?.desired,
            "activationGoal" to directive?.goal?.name, "reportedPresentationMode" to reported?.mode?.name,
            "reportedPresentationDetail" to reported?.detail, "panelSelected" to presentation.panelVisible,
            "presentationLive" to presentation.live, "pairedAvailable" to null,
            "pairedAvailabilityScope" to "UNEXPORTED_NO_MEMORY_GETTER")
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
        launchScope = ReceiverLaunchScope(UUID.randomUUID().toString(), UUID.randomUUID().toString(), taskId)
        val source = owner
        if (source == null) {
            connectBeforeBinding = true; disconnectBeforeBinding = false; connectRequested = true
            connectButton.text = getString(R.string.disconnect); showStatus(getString(R.string.connecting))
        } else {
            source.launch(launchScope); registerTask(launchScope, null)
        }
    }

    private fun showSnapshot(snapshot: ReceiverSessionOwner.Snapshot) {
        if (usable() && snapshot.activationId != null) {
            visibleActivation = snapshot.activationId
            if (registeredActivations.add(snapshot.activationId)) registerTask(
                ReceiverLaunchScope(UUID.randomUUID().toString(), "", taskId), snapshot.activationId)
        }
        val oldSession = session
        val oldGeometry = currentGeometry
        val wasStreaming = streaming
        if (oldSession !== snapshot.session || snapshot.state != "STREAMING") cancelInput()
        session = snapshot.session
        currentGeometry = snapshot.geometry
        connectRequested = snapshot.requested
        streaming = snapshot.state == "STREAMING"
        if (streaming) presentation.streaming()
        else if (session == null) presentation.disconnected()
        connectionView.visibility = if (presentation.panelVisible) View.VISIBLE else View.GONE
        connectButton.text = getString(if (session != null || connectRequested) R.string.disconnect else R.string.connect)
        showStatus(when (snapshot.state) {
            "STREAMING" -> getString(R.string.connected)
            "CONNECTING", "WAITING_FOR_VIDEO", "DISPLAY_DETACHED" -> getString(R.string.connecting)
            "PHONE_SETUP_REQUIRED" -> "Phone setup required: ${snapshot.peer?.detail}. Open EyeProjector on the Phone."
            "RETRYING_PEER" -> "Retrying paired Phone; check its setup and network"
            "CANCEL_PENDING" -> "Saving cancellation; projection is stopping"
            "ACTIVATION_STORAGE_PENDING" -> "Saving connection request; retrying"
            "PEER_UNAVAILABLE" -> "Paired Phone unavailable. Open EyeProjector on the Phone and check its setup and network."
            "DISCONNECTED", "STOPPED" -> getString(R.string.disconnected)
            else -> "Projection stopped: ${snapshot.state}"
        })
        if (oldSession !== session || oldGeometry != currentGeometry || wasStreaming != streaming) {
            pointer.stop(); if (resumed && hasWindowFocus()) pointer.start()
            resize()
        }
    }

    internal fun disconnect(notifyPeer: Boolean = true) {
        cancelInput()
        connectBeforeBinding = false
        val source = owner
        if (source == null) disconnectBeforeBinding = true
        else source.disconnect(notifyPeer, scope = launchScope, activationId = visibleActivation)
    }
    override fun onResume() {
        super.onResume(); resumed = true; pointer.start(); longTouch.start(); padContact.start()
        hidDevices.start()
        if (hasWindowFocus()) functionInput.start()
        attachCameraPermissionHost()
        attachPresentation()
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (::root.isInitialized) {
            if (!hasFocus) { cancelInput(); pointer.stop(); if (::longTouch.isInitialized) longTouch.stop(); if (::padContact.isInitialized) padContact.stop(); if (::functionInput.isInitialized) functionInput.stop() }
            else if (resumed) { pointer.start(); longTouch.start(); padContact.start(); functionInput.start(); attachPresentation() }
            owner?.presentation(sessionView, usable(), localInputVisible())
        }
    }
    override fun onPause() {
        resumed = false; cancelInput()
        serviceBinder?.detachCameraPermissionHost(cameraPermissionHost)
        hidDevices.stop(); functionInput.stop(); longTouch.stop(); padContact.stop(); pointer.stop()
        owner?.detach(sessionView)
        super.onPause()
    }
    override fun onDestroy() {
        cancelInput()
        shellPadContact.stop()
        serviceBinder?.detachCameraPermissionHost(cameraPermissionHost)
        cameraPermissionRequests.clear()
        hidDevices.stop(); functionInput.stop(); longTouch.stop(); padContact.stop()
        owner?.detach(sessionView)
        if (isFinishing && bindingValid) {
            // Original view/task scope only. A delayed destructor never resolves a newer singleton H.
            owner?.disconnect(scope = launchScope, activationId = visibleActivation)
        }
        if (bound) { unbindService(serviceConnection); bound = false }
        owner = null; serviceBinder = null; pairingPanel.close(); super.onDestroy()
    }
    override fun surfaceCreated(holder: SurfaceHolder) { if (resumed) owner?.attachSurface(sessionView, holder.surface) }
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) { resize() }
    override fun surfaceDestroyed(holder: SurfaceHolder) {
        streaming = false; cancelInput()
        owner?.attachSurface(sessionView, null)
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
