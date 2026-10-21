package com.code2hack.eyeprojector.directhost

import android.content.Context
import com.code2hack.eyeprojector.protocol.Geometry
import com.code2hack.eyeprojector.protocol.ScaleState
import com.code2hack.eyeprojector.protocol.ScaleStatus

class PanelPowerState

/** Controlled facade. Actual Binder admission is covered by the separate adapter/server suites. */
class HostBootstrap(initialContext: ProjectionContext, initialPublishedContext: ProjectionContext = initialContext) {
    enum class Setup { MISSING, UNAVAILABLE, UPDATE_REQUIRED, AUTHORIZATION_REQUIRED, SHELL_REQUIRED, AVAILABLE }
    data class Status(val setup: Setup, val backend: String, val detail: String? = null,
        val sessionEpoch: String = "", val rejectedEpoch: String? = null, val projectionToken: String = "") {
        val projectionContext get() = ProjectionContext(sessionEpoch, projectionToken)
    }
    data class Call(val kind: String, val expected: ProjectionContext, val packageName: String? = null,
        val value: Double? = null, val foregroundAtDispatch: String? = null, val complete: (Any?) -> Unit)
    var actualContext = initialContext
    var foregroundPackage: String? = null
    var stops = 0
    var starts = 0
    val pending = ArrayDeque<Call>()
    val history = mutableListOf<Call>()
    private val observers = mutableSetOf<(Status) -> Unit>()
    private var current = Status(Setup.AVAILABLE, "STREAMING", sessionEpoch = initialPublishedContext.ownerEpoch,
        projectionToken = initialPublishedContext.projectionToken)
    private var scale = ScaleState(0, 1.0, Geometry(480, 640), null, ScaleStatus.READY, false)

    fun requestPanelOff(complete: (PanelPowerState?) -> Unit) { complete(null) }
    fun panelState(complete: (PanelPowerState?) -> Unit) { complete(null) }
    fun status() = current
    fun observe(observer: (Status) -> Unit) { observers.add(observer); observer(current) }
    fun removeObserver(observer: (Status) -> Unit) { observers.remove(observer) }
    fun emit(context: ProjectionContext, backend: String = "STREAMING", updateActual: Boolean = true) {
        if (updateActual) actualContext = context
        current = Status(Setup.AVAILABLE, backend, sessionEpoch = context.ownerEpoch, projectionToken = context.projectionToken)
        observers.toList().forEach { it(current) }
    }
    fun start(epoch: String) { starts++ }
    fun stop() { stops++; emit(ProjectionContext(current.sessionEpoch), "STOPPING") }
    fun refresh() { observers.toList().forEach { it(current) } }
    fun requestAuthorization() {}
    fun stopBeforeForget(complete: () -> Unit) { stop(); complete() }

    private fun enqueue(call: Call) { history.add(call); pending.addLast(call) }
    fun launchApp(packageName: String, complete: (ProjectionDisplayState?) -> Unit) =
        launchApp(packageName, current.projectionContext, complete)
    fun launchApp(packageName: String, expected: ProjectionContext, complete: (ProjectionDisplayState?) -> Unit) {
        enqueue(Call("launch", expected, packageName, foregroundAtDispatch = foregroundPackage) { complete(it as ProjectionDisplayState?) })
    }
    fun projectionDisplayState(complete: (ProjectionDisplayState?) -> Unit) =
        projectionDisplayState(current.projectionContext, complete)
    fun projectionDisplayState(expected: ProjectionContext, complete: (ProjectionDisplayState?) -> Unit) {
        enqueue(Call("display", expected, foregroundAtDispatch = foregroundPackage) { complete(it as ProjectionDisplayState?) })
    }
    fun requestScale(value: Double, complete: (ScaleState?) -> Unit) = requestScale(value, current.projectionContext, complete)
    fun requestScale(value: Double, expected: ProjectionContext, complete: (ScaleState?) -> Unit) {
        enqueue(Call("request", expected, value = value, foregroundAtDispatch = foregroundPackage) { complete(it as ScaleState?) })
    }
    fun scaleState(complete: (ScaleState?) -> Unit) = scaleState(current.projectionContext, complete)
    fun scaleState(expected: ProjectionContext, complete: (ScaleState?) -> Unit) {
        enqueue(Call("read", expected, foregroundAtDispatch = foregroundPackage) { complete(it as ScaleState?) })
    }

    /** Removing before delivery models one completion turn and exposes overlapping admissions. */
    fun finishOne(): Call {
        check(pending.size == 1) { "Overlapping coordinator operations: ${pending.map { it.kind }}" }
        val call = pending.removeFirst()
        val matching = call.expected.available && call.expected == actualContext
        val result: Any? = when (call.kind) {
            "launch" -> if (!matching) null else {
                foregroundPackage = call.packageName
                ProjectionDisplayState(generation = "${call.expected.projectionToken}:child", displayId = 77,
                    requestedPackage = call.packageName, foregroundPackage = foregroundPackage,
                    context = call.expected, status = ProjectionLaunchStatus.STARTED)
            }
            "display" -> if (!matching) null else ProjectionDisplayState(generation = "${call.expected.projectionToken}:child",
                displayId = 77, foregroundPackage = foregroundPackage,
                context = call.expected, status = ProjectionLaunchStatus.STARTED)
            "request" -> if (!matching) null else {
                scale = scale.copy(requestId = scale.requestId + 1, requested = call.value!!, status = ScaleStatus.PENDING)
                scale
            }
            "read" -> scale.takeIf { matching }
            else -> error("Unknown call kind ${call.kind}")
        }
        call.complete(result)
        return call
    }
    fun finishAll() {
        var count = 0
        while (pending.isNotEmpty()) { check(++count <= 100) { "Unbounded callback cycle" }; finishOne() }
    }
    fun finishUntilRequest() {
        var count = 0
        while (pending.firstOrNull()?.kind != "request") {
            check(pending.isNotEmpty() && ++count <= 20) { "Expected a pending request" }
            finishOne()
        }
    }
    fun requests() = history.filter { it.kind == "request" }
    companion object {
        lateinit var fixture: HostBootstrap
        fun get(context: Context): HostBootstrap = fixture
    }
}
