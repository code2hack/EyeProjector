package com.code2hack.eyeprojector.directrg

/** Maps Phone media PTS to the RG AudioTrack frame clock. No Phone/RG wall-clock subtraction. */
internal class PlaybackClock {
    private data class Anchor(val frame: Long, val ptsUs: Long)
    private val anchors = ArrayDeque<Anchor>()
    private var timestampFrame = 0L
    private var timestampNs = 0L

    @Synchronized fun append(frame: Long, ptsUs: Long) {
        require(frame >= 0 && ptsUs >= 0)
        require(anchors.lastOrNull()?.frame?.let { frame >= it } != false)
        anchors.addLast(Anchor(frame, ptsUs))
        while (anchors.size > 64) anchors.removeFirst()
    }

    @Synchronized fun update(frame: Long, timeNs: Long, nowNs: Long): Boolean {
        if (frame < 0 || timeNs <= 0 || nowNs - timeNs !in 0..500_000_000L) return false
        if (timestampNs != 0L && (frame < timestampFrame || timeNs < timestampNs)) return false
        timestampFrame = frame; timestampNs = timeNs
        return true
    }

    @Synchronized fun ptsAt(nowNs: Long): Long? {
        if (timestampNs == 0L || nowNs - timestampNs !in 0..500_000_000L) return null
        val frame = timestampFrame + (nowNs - timestampNs) * 48_000 / 1_000_000_000
        val anchor = anchors.lastOrNull { it.frame <= frame } ?: return null
        return anchor.ptsUs + (frame - anchor.frame) * 1_000_000 / 48_000
    }

    fun videoTimeNs(ptsUs: Long, nowNs: Long): Long? = ptsAt(nowNs)?.let { nowNs + (ptsUs - it) * 1000 }
}
