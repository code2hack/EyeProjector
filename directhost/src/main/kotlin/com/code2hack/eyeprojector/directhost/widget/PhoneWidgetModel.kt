package com.code2hack.eyeprojector.directhost.widget

import com.code2hack.eyeprojector.directhost.PanelPowerState
import com.code2hack.eyeprojector.directhost.ProjectionContext
import com.code2hack.eyeprojector.protocol.SessionPhase
import com.code2hack.eyeprojector.protocol.SessionState

/** Values read from the existing owners, never a persisted guess at session lifetime. */
internal data class PhoneWidgetSnapshot(
    val session: SessionState,
    val paired: Boolean,
    val setupAvailable: Boolean,
    val backend: String,
    val backendDetail: String? = null,
    val projection: ProjectionContext = ProjectionContext(),
    val initialSetupPending: Boolean = false,
) {
    val needsSetup: Boolean get() = !paired || !setupAvailable ||
        session.detail == "SESSION_SETUP_REQUIRED" || backendDetail == "SESSION_SETUP_REQUIRED" ||
        backendDetail in setOf("FORGET_PENDING", "RESTORE_SHIZUKU_TO_VERIFY_STOP")
    // FAILED may still own resources while teardown is pending. Only STOPPED confirms release.
    val canStart: Boolean get() = session.phase in setOf(SessionPhase.INACTIVE, SessionPhase.FAILED) && backend == "STOPPED"
    val canPanelOff: Boolean get() = !needsSetup && session.phase == SessionPhase.STREAMING && backend == "STREAMING" && projection.available
    val primary: PhoneWidgetCommand get() = if (canStart) PhoneWidgetCommand.START else PhoneWidgetCommand.STOP
}

internal enum class PhoneWidgetCommand { START, STOP, PANEL_OFF, STATUS }
internal data class PhoneWidgetAction(
    val command: PhoneWidgetCommand,
    val epoch: String,
    val projection: ProjectionContext = ProjectionContext(),
)

/** This port delegates to PhoneSessionController; it never creates an owner or backend. */
internal interface PhoneWidgetSession {
    fun snapshot(): PhoneWidgetSnapshot
    fun start(expectedEpoch: String): SessionState
    fun stop(expectedEpoch: String): SessionState
    fun requestPanelOff(expected: ProjectionContext, complete: (PanelPowerState?) -> Unit)
    fun panelState(complete: (PanelPowerState?) -> Unit)
    fun refresh()
}

internal enum class PhoneWidgetRoute { COMPLETE, OPEN_STATUS }

internal class PhoneWidgetActions(
    private val sessions: PhoneWidgetSession,
    private val panelOff: (ProjectionContext) -> Unit,
) {
    fun deliver(action: PhoneWidgetAction): PhoneWidgetRoute {
        val actual = sessions.snapshot()
        sessions.refresh()
        if (action.command == PhoneWidgetCommand.STATUS) return PhoneWidgetRoute.OPEN_STATUS
        // Re-read at delivery, then let the controller atomically check this epoch again.
        if (action.epoch != actual.session.epoch) return PhoneWidgetRoute.OPEN_STATUS
        return when (action.command) {
            PhoneWidgetCommand.START -> {
                if (actual.needsSetup || !actual.canStart) PhoneWidgetRoute.OPEN_STATUS
                else {
                    val result = sessions.start(action.epoch)
                    if (result.phase == SessionPhase.STARTING || result.phase.active) PhoneWidgetRoute.COMPLETE
                    else PhoneWidgetRoute.OPEN_STATUS
                }
            }
            PhoneWidgetCommand.STOP -> {
                if (actual.canStart) PhoneWidgetRoute.OPEN_STATUS
                else {
                    val result = sessions.stop(action.epoch)
                    if (actual.needsSetup || result.phase == SessionPhase.UNKNOWN) PhoneWidgetRoute.OPEN_STATUS
                    else PhoneWidgetRoute.COMPLETE
                }
            }
            PhoneWidgetCommand.PANEL_OFF -> {
                if (actual.needsSetup || !actual.canPanelOff || actual.projection != action.projection) PhoneWidgetRoute.OPEN_STATUS
                else { panelOff(action.projection); PhoneWidgetRoute.COMPLETE }
            }
            PhoneWidgetCommand.STATUS -> PhoneWidgetRoute.OPEN_STATUS
        }
    }
}
