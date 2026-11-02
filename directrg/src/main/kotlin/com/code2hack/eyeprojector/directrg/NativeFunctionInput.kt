package com.code2hack.eyeprojector.directrg

import android.annotation.SuppressLint
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log

/** Foreground Function interception; ordinary touchpad/AI/key reports have independent owners. */
internal class NativeFunctionInput(
    private val context: Context,
    private val currentContext: () -> Any?,
    dispatch: (AnchorGesture) -> Unit,
    now: () -> Long = SystemClock::uptimeMillis,
    schedule: (Long, () -> Unit) -> (() -> Unit) = handlerSchedule(),
    timing: FunctionGestureRecognizer.Timing = FunctionGestureRecognizer.Timing(),
    capture: (Any) -> Any? = { it },
    captureOriginal: ((Any, Long, Long?) -> Any?)? = null,
    retireOriginal: ((Long, Any) -> Unit)? = null,
    registrationRelease: (AnchorGesture, Boolean) -> Unit = { _, _ -> },
) {
    private var registered = false
    private var retiring: MutableList<() -> Unit>? = null
    private class RawPress(val source: Any, val id: Long, var retired: Boolean)
    private var rawSource: Any? = null
    private var rawPress: RawPress? = null
    val pinsPointer: Boolean get() = rawPress?.retired == false
    private val recognizer = FunctionGestureRecognizer(now, { if (registered) currentContext() else null },
        { at, action ->
            val cancel = schedule(at, action)
            val original: () -> Unit = { retiring?.add(cancel) ?: cancel() }
            original
        }, { dispatch(it.anchorOperation()) }, timing, capture = capture, identity = identities, captureOriginal = captureOriginal,
        retireOriginal = retireOriginal?.let { retire -> { id, original ->
            val effect: () -> Unit = {retire(id,original)}
            retiring?.add(effect) ?: effect()
        } }, registrationRelease = { gesture, canceled ->
            val effect: () -> Unit = { registrationRelease(gesture.anchorOperation(), canceled) }
            retiring?.add(effect) ?: effect()
        })
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!registered) return
            val report = REPORTS[intent.action] ?: return
            if (isOrderedBroadcast && intent.flags and Intent.FLAG_RECEIVER_NO_ABORT == 0) {
                resultCode = Activity.RESULT_CANCELED
                abortBroadcast()
            }
            // OEM long handling suppresses its UP broadcast. While the existing shell reader
            // supplies real edges, broadcasts are duplicate summaries, never extra presses.
            if (rawSource != null) return
            recognizer.accept(report)
        }
    }

    fun rawSourceAvailable(source: Any) {
        if (rawSource === source) return
        rawSource = source
        rawPress = null
        cancel()
        runCatching { Log.i("EyeProjectorFunction", "raw_source connected=${System.identityHashCode(source)}") }
    }

    fun rawSourceLost(source: Any) {
        if (rawSource !== source) return
        rawSource = null
        rawPress = null
        cancel()
        runCatching { Log.i("EyeProjectorFunction", "raw_source lost=${System.identityHashCode(source)}") }
    }

    /** Original socket/press membership, not an OEM summary or a later input context. */
    fun rawEdge(source: Any, id: Long, down: Boolean, at: Long, originalContextCurrent: Boolean) {
        if (rawSource !== source) return
        if (down) {
            if (rawPress?.let { it.source === source && it.id == id } == true) return
            val previous = rawPress
            val cancelsRegistration = recognizer.awaitingRegistrationRelease
            val originalContext = if (registered && originalContextCurrent) currentContext() else null
            rawPress = null
            if (previous != null && !cancelsRegistration) cancel()
            if (rawSource !== source) return
            val press = RawPress(source, id, cancelsRegistration || originalContext == null || currentContext() != originalContext)
            rawPress = press
            runCatching { Log.i("EyeProjectorFunction", "raw_down source=${System.identityHashCode(source)} press=$id uptimeMs=$at retired=${press.retired}") }
            // A new physical press cancels an old chooser and is consumed in full. Let the
            // recognizer retire that original lease before considering any new context.
            if (!press.retired || registered && cancelsRegistration)
                recognizer.accept(FunctionGestureRecognizer.Report.DOWN, at)
        } else {
            val press = rawPress?.takeIf { it.source === source && it.id == id } ?: return
            rawPress = null
            runCatching { Log.i("EyeProjectorFunction", "raw_up source=${System.identityHashCode(source)} press=$id uptimeMs=$at retired=${press.retired}") }
            if (!press.retired && registered) recognizer.accept(FunctionGestureRecognizer.Report.UP, at)
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag") // API32 has no exported flag.
    fun start() {
        if (registered) return
        val filter = IntentFilter().apply { REPORTS.keys.forEach(::addAction); priority = Int.MAX_VALUE }
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        else {
            @Suppress("DEPRECATION")
            context.registerReceiver(receiver, filter)
        }
        registered = true
    }

    fun prepareCancel(preserveRegistration: Boolean = false): InputRetirement {
        if (!preserveRegistration || !recognizer.awaitingRegistrationRelease) rawPress?.retired = true
        val effects = mutableListOf<() -> Unit>()
        retiring = effects
        try { recognizer.cancel(preserveRegistration) } finally { retiring = null }
        val original = retirementState()
        return InputRetirement({ InputRetirement.finishAll(effects) }, { retirementState() != original })
    }

    private fun retirementState(): Any = listOf(recognizer.retirementState(), rawSource, rawPress, rawPress?.retired)

    fun cancel() = prepareCancel().finishOriginal()

    fun stop() {
        cancel()
        if (!registered) return
        registered = false
        context.unregisterReceiver(receiver)
    }

    /** Native recognition is an adapter; semantic chooser/contact owners never see OEM reports. */
    private fun FunctionGestureRecognizer.Gesture.anchorOperation() =
        AnchorGesture(kind, gestureId, sequence, original, physicalHeld)

    companion object {
        private val identities = FunctionGestureRecognizer.Identity()
        private val REPORTS = mapOf(
            "com.android.action.ACTION_SPRITE_BUTTON_DOWN" to FunctionGestureRecognizer.Report.DOWN,
            "com.android.action.ACTION_SPRITE_BUTTON_UP" to FunctionGestureRecognizer.Report.UP,
            "com.android.action.ACTION_SPRITE_BUTTON_CLICK" to FunctionGestureRecognizer.Report.CLICK,
            "com.android.action.ACTION_SPRITE_BUTTON_DOUBLE_CLICK" to FunctionGestureRecognizer.Report.DOUBLE,
            "com.android.action.ACTION_BOLON_PAIRING" to FunctionGestureRecognizer.Report.TRIPLE,
            "com.android.action.ACTION_SPRITE_BUTTON_LONG_PRESS" to FunctionGestureRecognizer.Report.LONG,
        )

        private fun handlerSchedule(): (Long, () -> Unit) -> (() -> Unit) {
            val handler = Handler(Looper.getMainLooper())
            return { deadline, action ->
                val task = Runnable(action)
                handler.postAtTime(task, deadline)
                val cancel: () -> Unit = { handler.removeCallbacks(task) }
                cancel
            }
        }
    }
}
