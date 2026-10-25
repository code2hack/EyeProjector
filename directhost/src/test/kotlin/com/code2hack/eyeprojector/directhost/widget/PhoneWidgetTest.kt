package com.code2hack.eyeprojector.directhost.widget

import com.code2hack.eyeprojector.directhost.*
import com.code2hack.eyeprojector.protocol.SessionPhase
import com.code2hack.eyeprojector.protocol.SessionState
import org.junit.Assert.*
import org.junit.Test

class PhoneWidgetTest {
    private class Sessions : PhoneWidgetSession {
        var sequence = 0
        val starts = mutableListOf<String>()
        var stops = 0
        var activationId: String? = null
        val activationStops = mutableListOf<String>()
        var beforeStart: (() -> Unit)? = null
        var beforeStop: (() -> Unit)? = null
        val core = SessionController({ starts.add(it) }, { stops++ }, { "epoch-${sequence++}" })
        var paired = true
        var setup = true
        var backend = "STOPPED"
        var detail: String? = null
        var projection = ProjectionContext()
        val queries = mutableListOf<(PanelPowerState?) -> Unit>()
        val commands = mutableListOf<Pair<ProjectionContext, (PanelPowerState?) -> Unit>>()
        var refreshes = 0
        override fun snapshot() = PhoneWidgetSnapshot(core.state(), paired, setup, backend, detail, projection, activationId = activationId)
        override fun start(expectedEpoch: String): SessionState { beforeStart?.invoke(); return core.start(expectedEpoch) }
        override fun stop(expectedEpoch: String): SessionState { beforeStop?.invoke(); return core.stop(expectedEpoch) }
        override fun stopActivation(activationId: String) { activationStops.add(activationId); if(this.activationId==activationId)this.activationId=null }
        override fun requestPanelOff(expected: ProjectionContext, complete: (PanelPowerState?) -> Unit) { commands.add(expected to complete) }
        override fun panelState(complete: (PanelPowerState?) -> Unit) { queries.add(complete) }
        override fun refresh() { refreshes++ }
        fun stream(): String {
            val owner = core.start().epoch
            core.backend(owner, "READY"); core.receiverRendered(owner)
            backend = "STREAMING"; projection = ProjectionContext(owner, "token-$owner")
            return owner
        }
        fun release() {
            val owner = projection.ownerEpoch
            core.stop(); core.backend(owner, "STOPPED")
            backend = "STOPPED"; projection = ProjectionContext()
        }
    }

    @Test fun pendingWholeActivationStopDoesNotDependOnBackendAttemptEpoch() {
        val s=Sessions();s.activationId="H2"
        val actions=PhoneWidgetActions(s) { fail("Unexpected panel command") }
        assertFalse(s.snapshot().canStart)
        assertEquals(PhoneWidgetRoute.COMPLETE,actions.deliver(PhoneWidgetAction(PhoneWidgetCommand.STOP,"old-A",activationId="H2")))
        assertEquals(listOf("H2"),s.activationStops);assertEquals(0,s.stops)
        s.activationId="H3"
        assertEquals(PhoneWidgetRoute.OPEN_STATUS,actions.deliver(PhoneWidgetAction(PhoneWidgetCommand.STOP,"old-A",activationId="H2")))
        assertEquals(listOf("H2"),s.activationStops);assertEquals("H3",s.activationId)
    }

    @Test fun inactiveStartDispatchesToExistingOwnerAndDuplicateCannotToggleToStop() {
        val s = Sessions(); val actions = PhoneWidgetActions(s) { fail("Unexpected panel command") }
        val start = PhoneWidgetAction(PhoneWidgetCommand.START, s.snapshot().session.epoch)
        assertEquals(PhoneWidgetRoute.COMPLETE, actions.deliver(start))
        assertEquals(SessionPhase.STARTING, s.core.state().phase)
        actions.deliver(start)
        assertEquals(1, s.starts.size); assertEquals(0, s.stops)
    }

    @Test fun everyActivePhaseStopsThroughExistingController() {
        for (phase in listOf(SessionPhase.STARTING, SessionPhase.AVAILABLE, SessionPhase.CONNECTING, SessionPhase.STREAMING)) {
            val s = Sessions(); val owner = s.core.start().epoch
            if (phase != SessionPhase.STARTING) s.core.backend(owner, "READY")
            if (phase == SessionPhase.CONNECTING) s.core.receiverConnecting(owner)
            if (phase == SessionPhase.STREAMING) s.core.receiverRendered(owner)
            val action = PhoneWidgetAction(PhoneWidgetCommand.STOP, s.core.state().epoch)
            PhoneWidgetActions(s) {}.deliver(action)
            assertEquals(phase.toString(), 1, s.stops)
            assertEquals(SessionPhase.STOPPING, s.core.state().phase)
        }
    }

    @Test fun staleStopCannotAffectLaterOwner() {
        val s = Sessions(); s.stream()
        val old = PhoneWidgetAction(PhoneWidgetCommand.STOP, s.core.state().epoch)
        s.release(); val fresh = s.stream()
        PhoneWidgetActions(s) {}.deliver(old)
        assertEquals(fresh, s.core.state().epoch); assertEquals(1, s.stops)
    }

    @Test fun controllerEpochCheckClosesStartAndStopPrecheckRaces() {
        val s = Sessions()
        val start = PhoneWidgetAction(PhoneWidgetCommand.START, s.core.state().epoch)
        s.beforeStart = { s.core.start() }
        PhoneWidgetActions(s) {}.deliver(start)
        assertEquals(1, s.starts.size)
        s.stream()
        val stop = PhoneWidgetAction(PhoneWidgetCommand.STOP, s.core.state().epoch)
        s.beforeStop = { s.release(); s.stream() }
        PhoneWidgetActions(s) {}.deliver(stop)
        assertEquals(SessionPhase.STREAMING, s.core.state().phase)
        assertEquals(1, s.stops)
    }

    @Test fun setupAndPairingRouteWithoutStarting() {
        for (pairing in listOf(false, true)) {
            val s = Sessions(); s.paired = pairing; s.setup = !pairing
            val action = PhoneWidgetAction(PhoneWidgetCommand.START, s.core.state().epoch)
            assertEquals(PhoneWidgetRoute.OPEN_STATUS, PhoneWidgetActions(s) {}.deliver(action))
            assertTrue(s.starts.isEmpty())
        }
        val s = Sessions(); s.detail = "SESSION_SETUP_REQUIRED"
        assertTrue(s.snapshot().needsSetup)
    }

    @Test fun missingSetupDoesNotPreventCurrentStop() {
        val s = Sessions(); s.stream(); s.setup = false
        assertEquals(PhoneWidgetRoute.OPEN_STATUS, PhoneWidgetActions(s) {}.deliver(
            PhoneWidgetAction(PhoneWidgetCommand.STOP, s.core.state().epoch)))
        assertEquals(1, s.stops)
    }

    @Test fun unknownStoppingAndUnreleasedFailureNeverExposeFreshStart() {
        val unknown = PhoneWidgetSnapshot(SessionState("unknown", SessionPhase.UNKNOWN), true, true, "UNKNOWN")
        assertFalse(unknown.canStart); assertEquals(PhoneWidgetCommand.STOP, unknown.primary)
        for (phase in listOf(SessionPhase.STOPPING, SessionPhase.FAILED)) {
            val pending = unknown.copy(session = SessionState("pending", phase), backend = "STOPPING")
            assertFalse(pending.canStart); assertFalse(pending.canPanelOff)
        }
        assertTrue(unknown.copy(session = SessionState("released", SessionPhase.FAILED), backend = "STOPPED").canStart)
    }

    @Test fun unknownStopRecordsCancellationAndRecoveryDoesNotStart() {
        var stopped = 0
        val core = SessionController({}, { stopped++ }, initialPhase = SessionPhase.UNKNOWN)
        val reply = core.stop(core.state().epoch)
        assertEquals(SessionPhase.UNKNOWN, reply.phase)
        assertEquals("STOP_PENDING_RECONCILIATION", reply.detail)
        core.observeBackend("recovered-owner", "STREAMING")
        assertEquals(1, stopped); assertEquals(SessionPhase.STOPPING, core.state().phase)
    }

    @Test fun panelOnlyDispatchesActualProjectionWithOriginalContext() {
        val s = Sessions(); val captured = mutableListOf<ProjectionContext>()
        val actions = PhoneWidgetActions(s) { captured.add(it) }
        actions.deliver(PhoneWidgetAction(PhoneWidgetCommand.PANEL_OFF, s.core.state().epoch))
        assertTrue(captured.isEmpty())
        s.stream()
        val current = s.snapshot()
        val action = PhoneWidgetAction(PhoneWidgetCommand.PANEL_OFF, current.session.epoch, current.projection)
        assertEquals(PhoneWidgetRoute.COMPLETE, actions.deliver(action))
        assertEquals(listOf(current.projection), captured)
        s.projection = s.projection.copy(projectionToken = "replacement")
        assertEquals(PhoneWidgetRoute.OPEN_STATUS, actions.deliver(action))
        assertEquals(1, captured.size)
    }

    private fun submitted(context: ProjectionContext) = PanelPowerState(9, PanelSubmission.SUBMITTED,
        PanelRestoration.PENDING, sessionActive = true, context = PanelContext(context.ownerEpoch, context.projectionToken))

    @Test fun olderQueryCannotOverwriteNewerOffReply() {
        val s = Sessions(); s.stream(); val panel = PhoneWidgetPanel(s) {}
        panel.reconcile(s.snapshot()); panel.query(); panel.off(s.projection)
        assertTrue(panel.submitting)
        s.commands.single().second(submitted(s.projection))
        s.queries.single()(PanelPowerState(context = PanelContext(s.projection.ownerEpoch, s.projection.projectionToken)))
        assertFalse(panel.submitting); assertEquals(PanelSubmission.SUBMITTED, panel.result?.off)
        assertEquals(PanelObservation.UNVERIFIED, panel.result?.observation)
        assertEquals(PanelRestoration.PENDING, panel.result?.restoration)
    }

    @Test fun replacementStopAndRemovalInvalidatePanelReplies() {
        for (transition in 0..2) {
            val s = Sessions(); s.stream(); val panel = PhoneWidgetPanel(s) {}
            panel.reconcile(s.snapshot()); panel.off(s.projection)
            val reply = submitted(s.projection)
            when (transition) {
                0 -> { s.projection = s.projection.copy(projectionToken = "new"); panel.reconcile(s.snapshot()) }
                1 -> { s.core.stop(); panel.reconcile(s.snapshot()) }
                2 -> panel.close()
            }
            s.commands.single().second(reply)
            assertNull(panel.result)
        }
    }

    @Test fun nullAndMismatchedPanelRepliesNeverBecomeSubmission() {
        val s = Sessions(); s.stream(); val panel = PhoneWidgetPanel(s) {}
        panel.reconcile(s.snapshot()); panel.off(s.projection)
        s.commands.last().second(null)
        assertNull(panel.result); assertFalse(panel.submitting)
        panel.off(s.projection)
        s.commands.last().second(submitted(ProjectionContext("stale", "token")))
        assertNull(panel.result); assertFalse(panel.submitting)
        panel.off(s.projection)
        s.commands.last().second(submitted(s.projection).copy(off = PanelSubmission.FAILED))
        assertEquals(PanelSubmission.FAILED, panel.result?.off)
    }

    @Test fun processSnapshotNeverTreatsUnknownAsPersistedInactive() {
        val s = Sessions(); s.stream()
        val recovering = PhoneWidgetSnapshot(SessionState("new-process", SessionPhase.UNKNOWN), true, true,
            "STREAMING", projection = s.projection)
        assertFalse(recovering.canStart); assertFalse(recovering.canPanelOff)
        val restored = SessionController({}, {}, initialPhase = SessionPhase.UNKNOWN)
        restored.observeBackend(s.projection.ownerEpoch, "STREAMING")
        assertEquals(SessionPhase.CONNECTING, restored.state().phase)
        assertFalse(recovering.copy(session = restored.state()).canPanelOff)
        restored.receiverRendered(restored.state().epoch)
        assertTrue(recovering.copy(session = restored.state()).canPanelOff)
    }
    @Test fun transientPanelLossAtCallbackDoesNotLeaveSubmissionStuck() {
        val s = Sessions(); s.stream(); val panel = PhoneWidgetPanel(s) {}
        panel.reconcile(s.snapshot()); panel.off(s.projection)
        val old = submitted(s.projection)
        s.backend = "STOPPING"
        s.commands.single().second(old)
        assertFalse(panel.submitting); assertNull(panel.result)
        s.backend = "STREAMING"; panel.reconcile(s.snapshot()); panel.off(s.projection)
        assertEquals(2, s.commands.size)
    }

    @Test fun actionTurnWaitsForSameOwnerReconciliationThenDispatchesOnce() {
        val s = Sessions(); val owner = s.stream()
        var actual = s.snapshot().copy(session = SessionState("new-process", SessionPhase.UNKNOWN))
        val rendered = PhoneWidgetAction(PhoneWidgetCommand.STOP, owner)
        var dispatches = 0; var completions = 0
        val turn = PhoneWidgetActionTurn({ actual }, {
            dispatches++; PhoneWidgetActions(s) {}.deliver(rendered)
        }, { completions++ })
        turn.advance(); turn.advance(); assertEquals(0, dispatches)
        actual = s.snapshot(); turn.advance(); turn.advance()
        assertTrue(turn.finished); assertEquals(1, dispatches); assertEquals(1, completions); assertEquals(1, s.stops)
    }

    @Test fun actionTurnCannotRetargetRecoveredReplacementOrOldInactiveStart() {
        for (command in listOf(PhoneWidgetCommand.STOP, PhoneWidgetCommand.START)) {
            val s = Sessions()
            val rendered = PhoneWidgetAction(command, s.core.state().epoch)
            if (command == PhoneWidgetCommand.STOP) s.stream()
            val results = mutableListOf<PhoneWidgetRoute>()
            val turn = PhoneWidgetActionTurn(s::snapshot, { PhoneWidgetActions(s) {}.deliver(rendered) }, results::add)
            // An old inactive epoch has no retained association after process reconstruction.
            if (command == PhoneWidgetCommand.START) {
                s.core.start(); s.core.stop(); s.core.backend(s.starts.last(), "STOPPED")
            }
            turn.advance()
            assertEquals(listOf(PhoneWidgetRoute.OPEN_STATUS), results)
            assertEquals(if (command == PhoneWidgetCommand.START) 1 else 0, s.stops)
        }
    }

    @Test fun canceledOrStatusRoutedTurnCannotDispatchLater() {
        val s = Sessions()
        val unknown = s.snapshot().copy(session = SessionState("unknown", SessionPhase.UNKNOWN))
        var actual = unknown
        var dispatches = 0
        val results = mutableListOf<PhoneWidgetRoute>()
        val canceled = PhoneWidgetActionTurn({ actual }, { dispatches++; PhoneWidgetRoute.COMPLETE }, results::add)
        canceled.advance(); canceled.cancel(); actual = s.snapshot(); canceled.advance()
        assertEquals(0, dispatches); assertTrue(results.isEmpty())
        actual = unknown
        val status = PhoneWidgetActionTurn({ actual }, { dispatches++; PhoneWidgetRoute.COMPLETE }, results::add)
        status.advance(); status.openStatus(); actual = s.snapshot(); status.advance()
        assertEquals(0, dispatches); assertEquals(listOf(PhoneWidgetRoute.OPEN_STATUS), results)
    }

    @Test fun unknownWithMissingSetupRoutesImmediatelyWithoutWaiting() {
        val s = Sessions(); s.setup = false
        val unknown = s.snapshot().copy(session = SessionState("unknown", SessionPhase.UNKNOWN))
        var dispatched = 0
        val turn = PhoneWidgetActionTurn({ unknown }, { dispatched++; PhoneWidgetRoute.OPEN_STATUS }, {})
        turn.advance(); turn.advance()
        assertTrue(turn.finished); assertEquals(1, dispatched)
    }

    @Test fun initialSetupSentinelWaitsWithoutFabricatingAvailability() {
        val s = Sessions()
        var actual = s.snapshot().copy(session = SessionState("unknown", SessionPhase.UNKNOWN),
            setupAvailable = false, backend = "UNKNOWN", initialSetupPending = true)
        var dispatched = 0; var completed = 0
        val turn = PhoneWidgetActionTurn({ actual }, { dispatched++; PhoneWidgetRoute.OPEN_STATUS }, { completed++ })
        turn.advance(); turn.advance()
        assertFalse(turn.finished); assertEquals(0, dispatched); assertFalse(actual.setupAvailable)
        assertTrue(actual.needsSetup); assertFalse(actual.canPanelOff)
        actual = actual.copy(initialSetupPending = false)
        turn.advance(); turn.advance()
        assertTrue(turn.finished); assertEquals(1, dispatched); assertEquals(1, completed)
    }

    @Test fun missingPairingRoutesEvenWhileInitialSetupIsPending() {
        val s = Sessions()
        val actual = s.snapshot().copy(session = SessionState("unknown", SessionPhase.UNKNOWN),
            paired = false, setupAvailable = false, initialSetupPending = true)
        var completed = 0
        val turn = PhoneWidgetActionTurn({ actual }, { PhoneWidgetRoute.OPEN_STATUS }, { completed++ })
        turn.advance(); assertTrue(turn.finished); assertEquals(1, completed)
    }

    @Test fun panelSetupAndPairingLossCannotDispatchAnAlreadyRenderedIntent() {
        for (missingPairing in listOf(false, true)) {
            val s = Sessions(); s.stream()
            val rendered = PhoneWidgetAction(PhoneWidgetCommand.PANEL_OFF, s.core.state().epoch, s.projection)
            if (missingPairing) s.paired = false else s.setup = false
            var requests = 0
            assertFalse(s.snapshot().canPanelOff)
            assertEquals(PhoneWidgetRoute.OPEN_STATUS, PhoneWidgetActions(s) { requests++ }.deliver(rendered))
            assertEquals(0, requests)
        }
    }

    @Test fun dispatchFailureCompletesOnceWithoutReplayingAnIrreversibleCommand() {
        for (submitted in listOf(false, true)) {
            val s = Sessions()
            var attempts = 0; var commands = 0
            val results = mutableListOf<PhoneWidgetRoute>()
            val turn = PhoneWidgetActionTurn(s::snapshot, {
                attempts++; if (submitted) commands++
                throw IllegalStateException("dispatch fixture")
            }, results::add)
            turn.advance(); turn.advance(); turn.openStatus()
            assertTrue(turn.finished); assertEquals(1, attempts)
            assertEquals(if (submitted) 1 else 0, commands)
            assertEquals(listOf(PhoneWidgetRoute.OPEN_STATUS), results)
        }
    }

}
