package com.code2hack.eyeprojector.directhost

import com.code2hack.eyeprojector.protocol.*

/** A control write is only a request. Applied numbers come from observed layout metadata. */
internal class LiveScale(
    private val resize: (Geometry) -> Unit,
    private val publish: (ScaleState) -> Unit,
    private val resizePrecise: ((Geometry, Double) -> Unit)? = null,
    private val beforeRequestPublish: (() -> Unit)? = null,
) {
    class RequestEffects(val current: () -> Boolean, val beforePublish: () -> Boolean, val publish: (ScaleState) -> Boolean,
        val resize: (Geometry, Double) -> Unit)
    private var value = unavailable()

    @Synchronized fun state(): ScaleState = value

    @Synchronized fun request(id: Long, scale: Double, publishUnavailable: Boolean = false, effects: RequestEffects? = null): ScaleState {
        if (effects != null && !effects.current()) return unavailable(id, scale)
        val observed = value.observed
        val target = LayoutScale.target(scale, observed?.let { it.width > it.height } == true)
        if (target == null || observed == null) {
            val reply = value.copy(requestId = id, requested = scale, target = target,
                status = if (target == null) ScaleStatus.REJECTED else ScaleStatus.UNAVAILABLE)
            // Keep replies ordered with observations. Local startup callers must not
            // emit a frame before the stream welcome; stream callers have read it.
            if (observed != null || publishUnavailable) { if (effects != null) effects.publish(reply) else publish(reply) }
            return reply
        }
        if (effects != null && !effects.beforePublish()) return unavailable(id, scale)
        val previous = value
        value = value.copy(requestId = id, requested = scale, target = target, status = ScaleStatus.PENDING)
        if (effects == null) { beforeRequestPublish?.invoke(); publish(value) }
        else if (!effects.publish(value)) { value = previous; return unavailable(id, scale) }
        // Let stock's debouncer coalesce requests. Alignment can make a resize a no-op,
        // so a later request must never wait for a geometry event that may not arrive.
        // Even target == observed must be sent: it cancels a preceding pending resize.
        if (effects != null) effects.resize(target, scale)
        else if (resizePrecise != null) resizePrecise.invoke(target, scale) else resize(target)
        return value
    }

    /** Lock order: this controller, publicationLock, then any input/control update in beforePublish. */
    @Synchronized fun observe(
        record: ScrcpyRecord.Session,
        publicationLock: Any = this,
        managedResize: Boolean = false,
        observedGeometry: Geometry = record.geometry,
        beforePublish: () -> Unit = {},
    ) {
        // Managed layout observations are display changes, not stock client-resize events.
        value = value.copy(observed = observedGeometry, clientResize = record.clientResize,
            status = when {
                value.target == null -> ScaleStatus.READY
                !record.clientResize && !managedResize && value.status == ScaleStatus.PENDING -> ScaleStatus.INTERRUPTED
                else -> ScaleStatus.OBSERVED
            })
        synchronized(publicationLock) {
            // Commit input geometry and send GEOMETRY after updating the model, while
            // requests/state reads wait. Keep its scale feedback in the same output turn.
            beforePublish()
            // Stock has no request IDs. This is an actual geometry observation, not an
            // acknowledgment that the latest requested target was applied exactly.
            publish(value)
        }
    }

    @Synchronized fun observeManagedLayout(
        observed: Geometry,
        publicationLock: Any = this,
        sourceChanged: Boolean = false,
        beforePublish: () -> Unit = {},
    ): Boolean {
        val changed = sourceChanged || value.observed != observed
        observe(ScrcpyRecord.Session(observed, clientResize = false), publicationLock,
            managedResize = true, beforePublish = { if (changed) beforePublish() })
        return changed
    }

    companion object {
        fun unavailable(id: Long = 0, scale: Double = 1.0) =
            ScaleState(id, scale, null, null, ScaleStatus.UNAVAILABLE, false)
    }
}
