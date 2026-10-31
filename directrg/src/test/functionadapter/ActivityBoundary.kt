package com.code2hack.eyeprojector.directrg

import android.app.Activity
import android.os.SystemClock
import android.view.View
import com.code2hack.eyeprojector.protocol.*

/** Initialization and Function methods below are exact source; unrelated UI/contacts are boundaries. */
internal class ActivityFunctionWiring : Activity() {
    lateinit var functionInput: NativeFunctionInput
    lateinit var hidInput: HidKeyInput
    val root = Boundary()
    val pointer = Boundary()
    val heldOwner = Boundary()
    val gestures = Boundary()
    lateinit var hidPanel: Boundary
    val connectionView = Boundary()
    val hidBindings = HidBindings({ null }, { true })
    var session: ProjectionSession? = ProjectionSession()
    var inputGeneration = 1L
    var currentGeometry: Geometry? = Geometry(480, 640)
    var available = true
    var schedules = 0
    var ordinaryActions = 0
    val projectionContext get() = connectionView.visibility != View.VISIBLE
    private fun usable() = available
    private fun projectedInput() = projectionContext
    private fun publishHeadPointer() {
        session?.let { it.fact = it.fact.copy(inputRevision = inputGeneration,
            mode = if (projectionContext) ReceiverUsabilityMode.PROJECTED else ReceiverUsabilityMode.LOCAL_PANEL) }
    }
    private fun hoveredHidAction(trace: HidDecisionTrace.Event?): HidAction? = null
    private fun schedulePadConfirmation() { schedules++ }
    private fun updateEdgeScroll() = Unit
    private fun dispatchPadAction(action: PadGestureRecognizer.Action) { ordinaryActions++ }
    // ACTUAL_CAPTURE_TYPE
    init {
        hidPanel = Boundary()
        // ACTUAL_INITIALIZERS
        functionInput.start()
    }
    // ACTUAL_METHODS
}
