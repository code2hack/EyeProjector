package com.code2hack.eyeprojector.directhost

import com.code2hack.eyeprojector.protocol.*

/** A control write is only a request. Applied numbers always come from stock session metadata. */
internal class LiveScale(
    private val resize: (Geometry) -> Unit,
    private val publish: (ScaleState) -> Unit,
) {
    private var value = unavailable()

    @Synchronized fun state(): ScaleState = value

    @Synchronized fun request(id: Long, scale: Double): ScaleState {
        val observed = value.observed
        val target = LayoutScale.target(scale, observed?.let { it.width > it.height } == true)
        if (target == null || observed == null) {
            return value.copy(requestId = id, requested = scale, target = target,
                status = if (target == null) ScaleStatus.REJECTED else ScaleStatus.UNAVAILABLE)
        }
        value = value.copy(requestId = id, requested = scale, target = target, status = ScaleStatus.PENDING)
        publish(value)
        // Let stock's debouncer coalesce requests. Alignment can make a resize a no-op,
        // so a later request must never wait for a geometry event that may not arrive.
        // Even target == observed must be sent: it cancels a preceding pending resize.
        resize(target)
        return value
    }

    @Synchronized fun observe(record: ScrcpyRecord.Session) {
        value = value.copy(observed = record.geometry, clientResize = record.clientResize,
            status = when {
                value.target == null -> ScaleStatus.READY
                !record.clientResize && value.status == ScaleStatus.PENDING -> ScaleStatus.INTERRUPTED
                else -> ScaleStatus.OBSERVED
            })
        // Stock has no request IDs. This is an actual geometry observation, not an
        // acknowledgment that the latest requested target was applied exactly.
        publish(value)
    }

    companion object {
        fun unavailable(id: Long = 0, scale: Double = 1.0) =
            ScaleState(id, scale, null, null, ScaleStatus.UNAVAILABLE, false)
    }
}
