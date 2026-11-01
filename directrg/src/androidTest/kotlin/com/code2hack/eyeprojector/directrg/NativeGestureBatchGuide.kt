package com.code2hack.eyeprojector.directrg

import android.app.Activity
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import java.lang.ref.WeakReference

/** Main-thread test UI. Uses only an owned label in the existing decor drawing overlay. */
internal class NativeGestureBatchGuide private constructor(private val queue: NativeInputObservationQueue,
                                                         private val manualRepeat: Boolean) {
    private class Label(activity: Activity) : TextView(activity) {
        var promptId = ""
        var drawAttemptPromptId: String? = null
        var onDrawn: ((Label) -> Unit)? = null
        override fun onDraw(canvas: Canvas) { super.onDraw(canvas); onDrawn?.invoke(this) }
    }
    private data class Surface(val root: ViewGroup, val label: Label)
    private val plan = NativeGestureBatchGuidePlan()
    private var activity: WeakReference<Activity>? = null
    private var surface: Surface? = null
    private var resumed = false
    private var focused = false
    private var interactive = false
    private var observationActive = false
    private var interactiveSampleElapsedNs: Long? = null
    private var drawnPromptId: String? = null
    private var visible = false
    private var reportedVisible: Boolean? = null
    private var stopped = false
    private var disabled = false
    private var firstFailure: Throwable? = null
    var failureCount = 0; private set
    var uiShown = false; private set
    val mode get() = if (manualRepeat) "manual_repeat" else "automatic_six_steps"
    val completed get() = !manualRepeat && plan.snapshot.complete
    val promptId get() = if (manualRepeat) "two_single_repeat" else plan.snapshot.prompt.id
    val released get() = surface == null

    fun onResumed(receiver: Activity) = contained {
        if (activity?.get() !== receiver) {
            pause()
            removeSurface("detached")
        }
        activity = WeakReference(receiver)
        resumed = true
        record("receiver_resumed")
    }

    fun onUnavailable(receiver: Activity, lifecycle: String) = contained {
        if (activity?.get() !== receiver) return@contained // An old Activity cannot detach its successor.
        resumed = false
        pause()
        removeSurface("detached")
        activity = null
        record("receiver_$lifecycle")
    }

    /** Power is sampled on the existing instrumentation sampling loop, never by a UI callback. */
    fun tick(activeSink: Boolean, sampledInteractive: Boolean, powerReadElapsedNs: Long?) = contained {
        observationActive = activeSink
        interactive = sampledInteractive
        interactiveSampleElapsedNs = powerReadElapsedNs
        val receiver = activity?.get()
        focused = receiver?.hasWindowFocus() == true
        val root = receiver?.window?.decorView as? ViewGroup
        val available = observationActive && resumed && interactive && focused && root != null && hostVisible(root)
        if (!available) {
            pause()
            removeSurface("detached")
            return@contained
        }
        if (surface?.root !== root) {
            pause()
            removeSurface("detached")
            val label = Label(receiver!!).apply {
                setTextColor(Color.WHITE); setBackgroundColor(Color.BLACK)
                textSize = 28f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
                isFocusable = false; isFocusableInTouchMode = false
                isClickable = false; isLongClickable = false; setTextIsSelectable(false)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            surface = Surface(root!!, label) // Retain exact ownership even if add has a partial side effect.
            label.onDrawn = { drawn -> contained {
                if (surface?.label === drawn) {
                    if (drawn.drawAttemptPromptId != drawn.promptId) {
                        drawn.drawAttemptPromptId = drawn.promptId
                        record("draw_attempt") // First actual own draw per prompt on this surface, even if ineligible.
                    }
                    if (observationActive && resumed && interactive && activity?.get()?.hasWindowFocus() == true &&
                        labelVisible(drawn) && drawnPromptId != drawn.promptId) {
                        drawnPromptId = drawn.promptId
                        uiShown = true
                        setVisible(true)
                        record("shown") // Own draw callback, not optical visibility or user acknowledgment.
                    }
                }
            } }
            root.overlay.add(label)
            render()
            record("attached")
        }
        render()
        val label = surface!!.label
        setVisible(labelVisible(label) && drawnPromptId == promptId)
        if (!manualRepeat) plan.update(SystemClock.uptimeMillis(), visible).forEach { record(it.name, it.snapshot) }
        render()
    }

    private fun hostVisible(root: ViewGroup): Boolean = root.isAttachedToWindow && root.isShown &&
        root.visibility == View.VISIBLE && root.windowVisibility == View.VISIBLE && root.width > 0 && root.height > 0

    private fun intersectsHost(label: Label, root: ViewGroup): Boolean = label.width > 0 && label.height > 0 &&
        label.left < root.width && label.right > 0 && label.top < root.height && label.bottom > 0

    private fun labelVisible(label: Label): Boolean {
        val owned = surface ?: return false
        // Overlay children need not have normal ancestor isShown/global-visible semantics.
        return owned.label === label && activity?.get()?.window?.decorView === owned.root && hostVisible(owned.root) &&
            label.isAttachedToWindow && label.visibility == View.VISIBLE && label.windowVisibility == View.VISIBLE &&
            intersectsHost(label, owned.root)
    }

    private fun render() {
        val owned = surface ?: return
        val snapshot = plan.snapshot
        val text = when {
            manualRepeat -> "TWO FINGERS: ONE TAP\nRepeat 10–15 times\nAbout 3 seconds apart\nThen stop."
            snapshot.complete -> snapshot.prompt.instruction
            snapshot.step == 0 -> "${snapshot.prompt.instruction}\n${snapshot.countdownSeconds}s"
            else -> "STEP ${snapshot.step} / ${NativeGestureBatchGuidePlan.STEP_COUNT}  ·  ${snapshot.countdownSeconds}s\n" +
                "${snapshot.prompt.instruction}\nDo it once, then wait."
        }
        if (owned.label.promptId != promptId) {
            drawnPromptId = null
            owned.label.promptId = promptId
            setVisible(false)
        }
        if (owned.label.text.toString() != text) owned.label.text = text
        val margin = (16 * owned.label.resources.displayMetrics.density).toInt()
        owned.label.setPadding(margin, margin, margin, margin)
        val width = (owned.root.width - margin * 2).coerceAtLeast(0)
        val height = (owned.root.height - margin * 2).coerceAtLeast(0)
        owned.label.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.AT_MOST))
        owned.label.layout(margin, margin, margin + owned.label.measuredWidth, margin + owned.label.measuredHeight)
        owned.label.invalidate()
    }

    private fun setVisible(value: Boolean) {
        val changed = visible != value
        visible = value
        if (reportedVisible != value) { reportedVisible = value; record("visibility") }
        if (manualRepeat && changed) record(if (value) "resume" else "pause")
    }

    private fun pause() {
        setVisible(false)
        if (!manualRepeat) plan.update(SystemClock.uptimeMillis(), false).forEach { record(it.name, it.snapshot) }
    }

    private fun removeSurface(event: String) {
        val owned = surface ?: return
        owned.label.onDrawn = null
        owned.label.visibility = View.INVISIBLE
        owned.root.overlay.remove(owned.label)
        surface = null
        drawnPromptId = null
        record(event)
    }

    private fun record(event: String, snapshot: NativeGestureBatchGuidePlan.Snapshot = plan.snapshot,
                       errorType: String? = null) {
        val label = surface?.label
        val root = surface?.root
        queue.capture("guide", activity?.get()?.let(System::identityHashCode),
            SystemClock.uptimeMillis(), SystemClock.elapsedRealtimeNanos()) {
            mapOf("guideEvent" to event, "guideMode" to mode,
                "promptId" to if (manualRepeat) promptId else snapshot.prompt.id,
                "stepNumber" to if (manualRepeat) null else snapshot.step,
                "stepCount" to if (manualRepeat) null else NativeGestureBatchGuidePlan.STEP_COUNT,
                "countdownSeconds" to if (manualRepeat) null else snapshot.countdownSeconds,
                "remainingMs" to if (manualRepeat) null else snapshot.remainingMs,
                "promptElapsedMs" to if (manualRepeat) null else snapshot.promptElapsedMs,
                "activeElapsedMs" to if (manualRepeat) null else snapshot.activeElapsedMs,
                "guideVisible" to visible, "guideResumed" to resumed, "guideFocused" to focused,
                "guideInteractive" to interactive, "observationActive" to observationActive,
                "interactiveSampleElapsedNs" to interactiveSampleElapsedNs,
                "labelLeft" to label?.left, "labelTop" to label?.top,
                "labelWidth" to label?.width, "labelHeight" to label?.height, "errorType" to errorType) +
                if (event == "draw_attempt") mapOf(
                    "hostAttached" to root?.isAttachedToWindow, "hostShown" to root?.isShown,
                    "hostWindowVisible" to (root?.windowVisibility == View.VISIBLE),
                    "hostFocused" to activity?.get()?.hasWindowFocus(),
                    "labelAttached" to label?.isAttachedToWindow,
                    "labelVisibilityFlag" to (label?.visibility == View.VISIBLE),
                    "labelWindowVisible" to (label?.windowVisibility == View.VISIBLE),
                    "labelHierarchyShown" to label?.isShown,
                    "labelIntersectsHost" to (label != null && root != null && intersectsHost(label, root))
                ) else emptyMap()
        }
    }

    private fun failed(error: Throwable) {
        failureCount++
        val first = firstFailure
        if (first == null) firstFailure = error else if (first !== error) first.addSuppressed(error)
        disabled = true
        record("error", errorType = error.javaClass.simpleName)
        try { pause(); removeSurface("error_detached") }
        catch (cleanup: Throwable) {
            failureCount++
            if (cleanup !== firstFailure) firstFailure?.addSuppressed(cleanup)
        }
    }

    private inline fun contained(operation: () -> Unit) {
        if (stopped || disabled) return
        try { operation() } catch (error: Throwable) { failed(error) }
    }

    fun throwIfFailed() { firstFailure?.let { throw it } }

    fun stop() {
        if (!stopped || surface != null) {
            stopped = true // Late lifecycle/draw callbacks cannot mutate UI or record after closure.
            try {
                pause()
                removeSurface("detached")
                activity = null
                plan.close()
                record("cleanup")
            } catch (error: Throwable) { failed(error) }
        }
        throwIfFailed()
    }

    companion object {
        fun create(argument: String?, queue: NativeInputObservationQueue,
                   guideCase: String? = null): NativeGestureBatchGuide? =
            if (argument == "true") NativeGestureBatchGuide(queue, guideCase == "two-single-repeat") else null
    }
}
