package com.code2hack.eyeprojector.directhost
import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
class HostActivity : Activity()
class ProjectionDisplayState
class HostBootstrap {
    enum class Setup { AVAILABLE, MISSING }
    data class Status(val setup: Setup, val backend: String, val detail: String? = null, val sessionEpoch: String = "", val rejectedEpoch: String? = null,
        val projectionToken: String = "") {
        val projectionContext get() = ProjectionContext(sessionEpoch, projectionToken)
    }
    val observers = mutableSetOf<(Status) -> Unit>()
    var value = Status(Setup.AVAILABLE, "STOPPED")
    var auto = false
    val starts = mutableListOf<String>()
    var stops = 0
    var refreshes = 0
    val panelRequests = mutableListOf<Pair<ProjectionContext, (PanelPowerState?) -> Unit>>()
    val queries = mutableListOf<(PanelPowerState?) -> Unit>()
    fun observe(observer: (Status) -> Unit) { observers.add(observer); Handler(Looper.getMainLooper()).post { observer(value) } }
    fun publish(status: Status) { value = status; observers.forEach { observer -> Handler(Looper.getMainLooper()).post { observer(status) } } }
    fun status() = value
    fun start(epoch: String) { starts.add(epoch); if (auto) publish(value.copy(backend = "STARTING", sessionEpoch = epoch)) }
    fun stop() { stops++; if (auto) publish(value.copy(backend = "STOPPED", projectionToken = "")) }
    fun refresh() { refreshes++ }
    fun launchApp(pkg: String, complete: (ProjectionDisplayState?) -> Unit) {}
    fun launchApp(pkg: String, expected: ProjectionContext, complete: (ProjectionDisplayState?) -> Unit) {}
    fun projectionDisplayState(complete: (ProjectionDisplayState?) -> Unit) {}
    fun requestPanelOff(complete: (PanelPowerState?) -> Unit) = requestPanelOff(value.projectionContext, complete)
    fun requestPanelOff(expected: ProjectionContext, complete: (PanelPowerState?) -> Unit) { panelRequests.add(expected to complete) }
    fun panelState(complete: (PanelPowerState?) -> Unit) { queries.add(complete) }
    companion object { var current = HostBootstrap(); fun get(context: Context) = current }
}
