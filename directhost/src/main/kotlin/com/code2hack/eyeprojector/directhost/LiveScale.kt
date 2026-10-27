package com.code2hack.eyeprojector.directhost

import com.code2hack.eyeprojector.protocol.*

/** A control write is only a request. Applied numbers always come from stock session metadata. */
internal class LiveScale(
    private val resize: (Geometry) -> Unit,
    private val publish: (ScaleState) -> Unit,
) {
    private var value = unavailable()

    @Synchronized fun state(): ScaleState = value

    @Synchronized fun request(id: Long, scale: Double, publishUnavailable: Boolean = false): ScaleState {
        val observed = value.observed
        val target = LayoutScale.target(scale, observed?.let { it.width > it.height } == true)
        if (target == null || observed == null) {
            val reply = value.copy(requestId = id, requested = scale, target = target,
                status = if (target == null) ScaleStatus.REJECTED else ScaleStatus.UNAVAILABLE)
            // Keep replies ordered with observations. Local startup callers must not
            // emit a frame before the stream welcome; stream callers have read it.
            if (observed != null || publishUnavailable) publish(reply)
            return reply
        }
        value = value.copy(requestId = id, requested = scale, target = target, status = ScaleStatus.PENDING)
        publish(value)
        // Let stock's debouncer coalesce requests. Alignment can make a resize a no-op,
        // so a later request must never wait for a geometry event that may not arrive.
        // Even target == observed must be sent: it cancels a preceding pending resize.
        resize(target)
        return value
    }

    /** Lock order: this controller, publicationLock, then any input/control update in beforePublish. */
    @Synchronized fun observe(
        record: ScrcpyRecord.Session,
        publicationLock: Any = this,
        managedResize: Boolean = false,
        beforePublish: () -> Unit = {},
    ) {
        // Managed displays resize through VirtualDisplay, so stock reports a display change
        // rather than its new-display client-resize flag. Keep that raw flag unchanged.
        value = value.copy(observed = record.geometry, clientResize = record.clientResize,
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

    companion object {
        fun unavailable(id: Long = 0, scale: Double = 1.0) =
            ScaleState(id, scale, null, null, ScaleStatus.UNAVAILABLE, false)
    }
}
