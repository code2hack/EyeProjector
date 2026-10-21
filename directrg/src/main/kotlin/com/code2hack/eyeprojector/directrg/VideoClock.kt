package com.code2hack.eyeprojector.directrg

/** One video stream's PTS origin mapped to the Receiver's monotonic clock. */
internal class VideoClock {
    private var originPtsUs: Long? = null
    private var originNs = 0L

    @Synchronized fun start(ptsUs: Long, receivedNs: Long) {
        val origin = originPtsUs
        if (origin == null) {
            originPtsUs = ptsUs
            originNs = receivedNs
        } else {
            val elapsedNs = Math.multiplyExact(Math.subtractExact(ptsUs, origin), 1000L)
            // A later receipt can reveal excess delay in the first sample. Delayed
            // packets must never move presentation later than the earliest offset.
            originNs = minOf(originNs, Math.subtractExact(receivedNs, elapsedNs))
        }
    }

    @Synchronized fun timeNs(ptsUs: Long): Long? = originPtsUs?.let {
        // Source PTS is a duration within this stream, never a cross-device wall clock.
        Math.addExact(originNs, Math.multiplyExact(Math.subtractExact(ptsUs, it), 1000L))
    }
}
