package com.code2hack.eyeprojector.directhost.widget

import com.code2hack.eyeprojector.directhost.HostBootstrap
import com.code2hack.eyeprojector.directhost.PanelPowerState
import com.code2hack.eyeprojector.directhost.PhoneSessionController
import com.code2hack.eyeprojector.directhost.ProjectionContext

internal class PhoneWidgetSessionAdapter(private val sessions: PhoneSessionController) : PhoneWidgetSession {
    override fun snapshot(): PhoneWidgetSnapshot {
        val session = sessions.state()
        val backend = sessions.bootstrap.status()
        return PhoneWidgetSnapshot(session, runCatching { sessions.store.pairedPeer() != null }.getOrDefault(false),
            backend.setup == HostBootstrap.Setup.AVAILABLE, backend.backend, backend.detail, backend.projectionContext)
    }
    override fun start(expectedEpoch: String) = sessions.start(expectedEpoch)
    override fun stop(expectedEpoch: String) = sessions.stop(expectedEpoch)
    override fun requestPanelOff(expected: ProjectionContext, complete: (PanelPowerState?) -> Unit) = sessions.requestPanelOff(expected, complete)
    override fun panelState(complete: (PanelPowerState?) -> Unit) = sessions.panelState(complete)
    override fun refresh() = sessions.bootstrap.refresh()
}
