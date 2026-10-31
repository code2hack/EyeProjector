package com.code2hack.eyeprojector.directrg

import android.app.Activity
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import java.lang.ref.WeakReference

/** Main-thread test UI. Uses only an owned label in the existing decor drawing overlay. */
internal class NativeGestureBatchGuide private constructor(private val queue: NativeInputObservationQueue) {
    private class Label(activity: Activity) : TextView(activity) {
        var promptId = ""
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
    val completed get() = plan.snapshot.complete
    val promptId get() = plan.snapshot.prompt.id
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
        val available = observationActive && resumed && interactive && focused && root != null &&
            root.isAttachedToWindow && root.isShown && root.windowVisibility == View.VISIBLE &&
            root.width > 0 && root.height > 0
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
                if (surface?.label === drawn && observationActive && resumed && interactive &&
                    activity?.get()?.hasWindowFocus() == true && labelVisible(drawn) &&
                    drawnPromptId != drawn.promptId) {
                    drawnPromptId = drawn.promptId
                    uiShown = true
                    setVisible(true)
                    record("shown") // Own draw callback, not optical visibility or user acknowledgment.
                }
            } }
            root.overlay.add(label)
            render()
            record("attached")
        }
        render()
        val label = surface!!.label
        setVisible(labelVisible(label) && drawnPromptId == plan.snapshot.prompt.id)
        plan.update(SystemClock.uptimeMillis(), visible).forEach { record(it.name, it.snapshot) }
        render()
    }

    private fun labelVisible(label: Label): Boolean = label.isAttachedToWindow && label.isShown &&
        label.windowVisibility == View.VISIBLE && label.width > 0 && label.height > 0 &&
        label.getGlobalVisibleRect(Rect())

    private fun render() {
        val owned = surface ?: return
        val snapshot = plan.snapshot
        val text = when {
            snapshot.complete -> snapshot.prompt.instruction
            snapshot.step == 0 -> "${snapshot.prompt.instruction}\n${snapshot.countdownSeconds}s"
            else -> "STEP ${snapshot.step} / ${NativeGestureBatchGuidePlan.STEP_COUNT}  ·  ${snapshot.countdownSeconds}s\n" +
                "${snapshot.prompt.instruction}\nDo it once, then wait."
        }
        if (owned.label.promptId != snapshot.prompt.id) {
            drawnPromptId = null
            owned.label.promptId = snapshot.prompt.id
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
        visible = value
        if (reportedVisible != value) { reportedVisible = value; record("visibility") }
    }

    private fun pause() {
        setVisible(false)
        plan.update(SystemClock.uptimeMillis(), false).forEach { record(it.name, it.snapshot) }
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
        queue.capture("guide", activity?.get()?.let(System::identityHashCode),
            SystemClock.uptimeMillis(), SystemClock.elapsedRealtimeNanos()) {
            mapOf("guideEvent" to event, "promptId" to snapshot.prompt.id,
                "stepNumber" to snapshot.step, "stepCount" to NativeGestureBatchGuidePlan.STEP_COUNT,
                "countdownSeconds" to snapshot.countdownSeconds, "remainingMs" to snapshot.remainingMs,
                "promptElapsedMs" to snapshot.promptElapsedMs, "activeElapsedMs" to snapshot.activeElapsedMs,
                "guideVisible" to visible, "guideResumed" to resumed, "guideFocused" to focused,
                "guideInteractive" to interactive, "observationActive" to observationActive,
                "interactiveSampleElapsedNs" to interactiveSampleElapsedNs,
                "labelLeft" to label?.left, "labelTop" to label?.top,
                "labelWidth" to label?.width, "labelHeight" to label?.height, "errorType" to errorType)
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
        fun create(argument: String?, queue: NativeInputObservationQueue): NativeGestureBatchGuide? =
            if (argument == "true") NativeGestureBatchGuide(queue) else null
    }
}
