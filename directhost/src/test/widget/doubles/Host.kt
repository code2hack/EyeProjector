package com.code2hack.eyeprojector.directhost
import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.code2hack.eyeprojector.protocol.ScaleState
class HostActivity : Activity()
class HostBootstrap {
    private val contextPublisher = com.code2hack.eyeprojector.directhost.activation.ActivationContextPublisher()
    fun observeRetiredBackendWitness(observer: (Status) -> Unit): java.io.Closeable = java.io.Closeable {}
    internal fun registerActivationAuthority(authority: com.code2hack.eyeprojector.directhost.activation.ActivationContextAuthority): java.io.Closeable {
        val source=contextPublisher.capture()
        contextPublisher.commit(source,value.projectionContext,value.setup==Setup.AVAILABLE && value.backend in setOf("SESSION_STARTING","STREAMING"))
        return contextPublisher.register(authority)
    }
    fun stopBeforeForget(complete:()->Unit){stop();complete()}
    fun capturePhoneHidRoute(down: com.code2hack.eyeprojector.protocol.PhoneHidEdge): com.code2hack.eyeprojector.directhost.input.PhoneHidRoute? = null
    enum class Setup { MISSING, UNAVAILABLE, UPDATE_REQUIRED, AUTHORIZATION_REQUIRED, SHELL_REQUIRED, AVAILABLE }
    data class Status(val setup: Setup, val backend: String, val detail: String? = null, val sessionEpoch: String = "", val rejectedEpoch: String? = null,
        val projectionToken: String = "") {
        val projectionContext get() = ProjectionContext(sessionEpoch, projectionToken)
    }
    val observers = mutableSetOf<(Status) -> Unit>()
    var value = Status(Setup.UNAVAILABLE, "UNKNOWN")
    var auto = false
    val starts = mutableListOf<String>()
    var stops = 0
    var refreshes = 0
    var failRefresh = false
    var failStop = false
    val panelRequests = mutableListOf<Pair<ProjectionContext, (PanelPowerState?) -> Unit>>()
    val queries = mutableListOf<(PanelPowerState?) -> Unit>()
    data class DisplayCall(val kind: String, val expected: ProjectionContext, val packageName: String?,
        val complete: (ProjectionDisplayState?) -> Unit)
    data class ScaleCall(val kind: String, val expected: ProjectionContext, val value: Double?,
        val complete: (ScaleState?) -> Unit)
    // Controlled external API calls only: recording/enqueueing does not imply execution, availability or release.
    val displayCalls = mutableListOf<DisplayCall>()
    val scaleCalls = mutableListOf<ScaleCall>()
    val callOrder = mutableListOf<Pair<String, ProjectionContext>>()
    fun observe(observer: (Status) -> Unit) { observers.add(observer); Handler(Looper.getMainLooper()).post { observer(value) } }
    fun publish(status: Status) { if (status == value) return
        val source=contextPublisher.capture()
        contextPublisher.stage(source,status.projectionContext,status.backend in setOf("SESSION_STARTING","STREAMING"))
        synchronized(contextPublisher.gate){contextPublisher.commit(source,status.projectionContext,status.setup==Setup.AVAILABLE && status.backend in setOf("SESSION_STARTING","STREAMING"));value=status}
         observers.forEach { observer -> Handler(Looper.getMainLooper()).post { observer(status) } } }
    fun status() = value
    fun start(epoch: String) { contextPublisher.bindingChanged(); starts.add(epoch); if (auto) publish(value.copy(backend = "STARTING", sessionEpoch = epoch)) }
    fun stop() { contextPublisher.retire(contextPublisher.capture(),blockBinding=true); stops++; check(!failStop) { "Modeled dispatch failure after Stop" }; if (auto) publish(value.copy(backend = "STOPPED", projectionToken = "")) }
    fun refresh() { refreshes++; check(!failRefresh) { "Modeled dispatch failure before Stop" } }
    fun launchApp(pkg: String, complete: (ProjectionDisplayState?) -> Unit) = launchApp(pkg, value.projectionContext, complete)
    fun launchApp(pkg: String, expected: ProjectionContext, complete: (ProjectionDisplayState?) -> Unit) {
        callOrder.add("launch" to expected); displayCalls.add(DisplayCall("launch", expected, pkg, complete))
    }
    fun projectionDisplayState(complete: (ProjectionDisplayState?) -> Unit) = projectionDisplayState(value.projectionContext, complete)
    fun projectionDisplayState(expected: ProjectionContext, complete: (ProjectionDisplayState?) -> Unit) {
        callOrder.add("display" to expected); displayCalls.add(DisplayCall("display", expected, null, complete))
    }
    fun requestScale(scale: Double, complete: (ScaleState?) -> Unit) = requestScale(scale, value.projectionContext, complete)
    fun requestScale(scale: Double, expected: ProjectionContext, complete: (ScaleState?) -> Unit) {
        callOrder.add("requestScale" to expected); scaleCalls.add(ScaleCall("request", expected, scale, complete))
    }
    fun scaleState(complete: (ScaleState?) -> Unit) = scaleState(value.projectionContext, complete)
    fun scaleState(expected: ProjectionContext, complete: (ScaleState?) -> Unit) {
        callOrder.add("scaleState" to expected); scaleCalls.add(ScaleCall("read", expected, null, complete))
    }
    fun requestPanelOff(complete: (PanelPowerState?) -> Unit) = requestPanelOff(value.projectionContext, complete)
    fun requestPanelOff(expected: ProjectionContext, complete: (PanelPowerState?) -> Unit) { callOrder.add("panelOff" to expected); panelRequests.add(expected to complete) }
    fun panelState(complete: (PanelPowerState?) -> Unit) { queries.add(complete) }
    companion object { var current = HostBootstrap(); fun get(context: Context) = current }
}
