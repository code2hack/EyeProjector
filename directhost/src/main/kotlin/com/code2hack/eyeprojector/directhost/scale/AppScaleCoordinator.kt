package com.code2hack.eyeprojector.directhost.scale

import com.code2hack.eyeprojector.directhost.ProjectionContext
import com.code2hack.eyeprojector.directhost.ProjectionDisplayState
import com.code2hack.eyeprojector.protocol.LayoutScale
import com.code2hack.eyeprojector.protocol.ScaleState
import com.code2hack.eyeprojector.protocol.ScaleStatus
import java.io.Closeable
import java.util.UUID

interface AppScaleBackend {
    fun launch(packageName: String, expected: ProjectionContext, complete: (ProjectionDisplayState?) -> Unit)
    fun display(expected: ProjectionContext, complete: (ProjectionDisplayState?) -> Unit)
    fun request(value: Double, expected: ProjectionContext, complete: (ScaleState?) -> Unit)
    fun read(expected: ProjectionContext, complete: (ScaleState?) -> Unit)
}

data class AppScaleTarget(val id: String, val packageName: String, val context: ProjectionContext)
data class AppScalePanelState(
    val target: AppScaleTarget,
    val launch: ProjectionDisplayState? = null,
    val scale: ScaleState? = null,
    val requested: Double? = null,
    val detail: String = "OPENING",
    val canPinch: Boolean = false,
) {
    val applied: Double? get() = scale?.observed?.let(LayoutScale::applied)
}

/**
 * Main-thread owner shared by both launchers and recreated Phone panels. Operations
 * on one Session drain in order: discarding a callback cannot undo a sent resize.
 * A replacement Session has its own lane and never waits for a retired callback.
 */
class AppScaleCoordinator(private val store: AppScaleStore, private val backend: AppScaleBackend) {
    private data class Work(val target: AppScaleTarget, val kind: String, val requested: Double?, val panelOwner: Any?, val cancel: () -> Unit, val run: (done: () -> Unit) -> Unit)
    private class Lane {
        val queue = ArrayDeque<Work>()
        var busy = false
        var draining = false
    }
    private val lanes = mutableMapOf<ProjectionContext, Lane>()
    private var panel: AppScaleTarget? = null
    private var panelOwner: Any? = null
    private var state: AppScalePanelState? = null
    private var dispatchedRequested: Double? = null
    private var acceptedRequested: Double? = null
    private var saveFailed = false
    private val observers = mutableSetOf<(AppScalePanelState?) -> Unit>()

    fun state(): AppScalePanelState? = state
    fun observe(observer: (AppScalePanelState?) -> Unit): Closeable {
        observers.add(observer); observer(state)
        return Closeable { observers.remove(observer) }
    }
    private fun publish(next: AppScalePanelState?) { state = next; observers.toList().forEach { it(next) } }
    private fun current(target: AppScaleTarget) = state?.target == target
    private fun enqueue(target: AppScaleTarget, kind: String, cancel: () -> Unit = {}, requested: Double? = null, owner: Any? = null, run: (done: () -> Unit) -> Unit) {
        val lane = lanes.getOrPut(target.context) { Lane() }
        lane.queue.addLast(Work(target, kind, requested, owner, cancel, run)); drain(lane)
    }
    private fun drain(lane: Lane) {
        if (lane.busy || lane.draining) return
        lane.draining = true
        try {
            while (!lane.busy && lane.queue.isNotEmpty()) {
                val work = lane.queue.removeFirst()
                if (!current(work.target) || (work.kind in setOf("pinch", "read") && (panel != work.target || panelOwner != work.panelOwner))) { work.cancel(); continue }
                lane.busy = true
                work.run { lane.busy = false; drain(lane) }
            }
        } finally {
            lane.draining = false
            if (!lane.busy && lane.queue.isNotEmpty()) drain(lane)
            if (!lane.busy && lane.queue.isEmpty()) lanes.entries.removeAll { (context, value) ->
                value === lane && state?.target?.context != context
            }
        }
    }

    fun launch(packageName: String, expected: ProjectionContext, complete: (ProjectionDisplayState?) -> Unit): AppScaleTarget {
        val target = AppScaleTarget(UUID.randomUUID().toString(), packageName, expected)
        panel = null; panelOwner = null; dispatchedRequested = null; acceptedRequested = null; saveFailed = false
        publish(AppScalePanelState(target))
        enqueue(target, "launch", { complete(null) }) { done ->
            backend.launch(packageName, expected) { result ->
                try {
                    if (current(target)) {
                        val confirmed = result?.context == expected && result.requestedPackage == packageName &&
                            result.foregroundPackage == packageName
                        publish(state!!.copy(launch = result, detail = if (confirmed) "RESTORING" else "LAUNCH_UNCONFIRMED"))
                        if (confirmed) request(target, store.read(packageName), save = false, kind = "restore")
                    }
                    complete(result.takeIf { current(target) })
                } finally { done() }
            }
        }
        return target
    }

    fun contextChanged(context: ProjectionContext, projectionActive: Boolean = true) {
        val old = state ?: return
        // Home may arrive before its first token poll. Only an active, unchanged owner
        // can retain this explicit pending intent; the adapter validates its original token.
        if (projectionActive && old.launch == null && context.ownerEpoch == old.target.context.ownerEpoch && context.projectionToken.isEmpty()) return
        if (!projectionActive || context != old.target.context) {
            val abandoned = lanes.values.flatMap { lane -> lane.queue.toList().also { lane.queue.clear() } }
            lanes.entries.removeAll { !it.value.busy }
            panel = null; panelOwner = null; dispatchedRequested = null; acceptedRequested = null; saveFailed = false
            publish(null)
            abandoned.forEach { it.cancel() }
        }
    }

    fun attach(target: AppScaleTarget, owner: Any = target): Boolean {
        if (!current(target)) return false
        if (panel == target && panelOwner != owner) panelOwner?.let { detach(target, it) }
        panel = target; panelOwner = owner
        return true
    }
    fun detach(target: AppScaleTarget, owner: Any = target) {
        if (panel != target || panelOwner != owner) return
        panel = null; panelOwner = null
        lanes[target.context]?.queue?.removeAll { it.target == target && it.kind in setOf("pinch", "read") }
        if (current(target)) publish(state!!.copy(requested = dispatchedRequested ?: acceptedRequested ?: state!!.applied))
    }

    fun pinch(target: AppScaleTarget, factor: Double, owner: Any = target) {
        val old = state?.takeIf { it.target == target && panel == target && panelOwner == owner && it.canPinch } ?: return
        val value = (old.requested ?: old.applied ?: return) * factor
        if (!factor.isFinite() || factor <= 0 || LayoutScale.target(value) == null) {
            publish(old.copy(detail = "INVALID_SCALE")); return
        }
        publish(old.copy(requested = value, detail = "REQUESTING"))
        lanes[target.context]?.queue?.removeAll { it.target == target && it.kind == "pinch" }
        request(target, value, save = true, kind = "pinch", owner = owner)
    }

    /** A fresh foreground observation also catches Home or app exit outside either launcher.
     * It is not an atomic package/geometry acknowledgment from the stock resize backend. */
    private fun checkForeground(target: AppScaleTarget, done: () -> Unit, action: () -> Unit) {
        backend.display(target.context) { display ->
            if (!current(target)) { done(); return@display }
            if (display?.context != target.context || display.foregroundPackage != target.packageName) {
                try { publish(state!!.copy(scale = null, canPinch = false, detail = "TARGET_CHANGED")) }
                finally { done() }
            } else action()
        }
    }

    private fun request(target: AppScaleTarget, value: Double, save: Boolean, kind: String, owner: Any? = null) {
        enqueue(target, kind, requested = value, owner = owner) { done ->
            checkForeground(target, done) {
                if (kind == "pinch" && (panel != target || panelOwner != owner)) { done(); return@checkForeground }
                dispatchedRequested = value
                backend.request(value, target.context) { result ->
                    try {
                        if (current(target)) {
                            val accepted = result != null && result.status !in setOf(ScaleStatus.UNAVAILABLE, ScaleStatus.REJECTED, ScaleStatus.UNSUPPORTED)
                            if (accepted) acceptedRequested = value
                            else dispatchedRequested = acceptedRequested
                            if (save && accepted) saveFailed = !store.write(target.packageName, value)
                            val queuedIntent = lanes[target.context]?.queue?.lastOrNull { it.target == target && it.kind == "pinch" }?.requested
                            publish(state!!.copy(scale = result,
                                requested = queuedIntent ?: acceptedRequested ?: result?.observed?.let(LayoutScale::applied),
                                canPinch = result?.observed != null && result.status != ScaleStatus.UNAVAILABLE,
                                detail = if (saveFailed) "SAVE_FAILED" else result?.status?.name ?: "UNAVAILABLE"))
                        }
                    } finally { done() }
                }
            }
        }
    }

    fun poll(target: AppScaleTarget, owner: Any = target) {
        val lane = lanes[target.context]
        if (!current(target) || panel != target || panelOwner != owner || state?.launch?.foregroundPackage != target.packageName ||
            lane?.queue?.any { it.kind == "read" && it.target == target } == true) return
        enqueue(target, "read", owner = owner) { done ->
            checkForeground(target, done) {
                if (panel != target || panelOwner != owner) { done(); return@checkForeground }
                backend.read(target.context) { result ->
                    try {
                        if (current(target)) publish(state!!.copy(scale = result,
                            canPinch = result?.observed != null && result.status != ScaleStatus.UNAVAILABLE,
                            detail = if (saveFailed) "SAVE_FAILED" else result?.status?.name ?: "UNAVAILABLE"))
                    } finally { done() }
                }
            }
        }
    }
}
