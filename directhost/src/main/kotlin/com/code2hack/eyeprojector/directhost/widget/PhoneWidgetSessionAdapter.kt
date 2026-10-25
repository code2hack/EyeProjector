package com.code2hack.eyeprojector.directhost.widget

import com.code2hack.eyeprojector.directhost.HostBootstrap
import com.code2hack.eyeprojector.directhost.PanelPowerState
import com.code2hack.eyeprojector.directhost.PhoneSessionController
import com.code2hack.eyeprojector.directhost.ProjectionContext
import com.code2hack.eyeprojector.protocol.SessionPhase

internal class PhoneWidgetSessionAdapter(private val sessions: PhoneSessionController) : PhoneWidgetSession {
    // An initial replay is not a completed setup observation. Equal unavailable refreshes retain this object.
    private val initialSetup = sessions.bootstrap.status().takeIf {
        it == HostBootstrap.Status(HostBootstrap.Setup.UNAVAILABLE, "UNKNOWN")
    }

    override fun snapshot(): PhoneWidgetSnapshot {
        val session = sessions.state()
        val backend = sessions.bootstrap.status()
        return PhoneWidgetSnapshot(session, runCatching { sessions.store.pairedPeer() != null }.getOrDefault(false),
            backend.setup == HostBootstrap.Setup.AVAILABLE, backend.backend, backend.detail, backend.projectionContext,
            initialSetupPending = initialSetup != null && backend === initialSetup && session.phase == SessionPhase.UNKNOWN,
            activationId = sessions.activationId())
    }
    override fun start(expectedEpoch: String) = sessions.start(expectedEpoch)
    override fun stop(expectedEpoch: String) = sessions.stop(expectedEpoch)
    override fun stopActivation(activationId: String) = sessions.cancelActivation(activationId)
    override fun requestPanelOff(expected: ProjectionContext, complete: (PanelPowerState?) -> Unit) = sessions.requestPanelOff(expected, complete)
    override fun panelState(complete: (PanelPowerState?) -> Unit) = sessions.panelState(complete)
    override fun refresh() = sessions.bootstrap.refresh()
}
