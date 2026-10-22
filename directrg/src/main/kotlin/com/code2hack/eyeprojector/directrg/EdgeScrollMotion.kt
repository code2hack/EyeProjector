package com.code2hack.eyeprojector.directrg

// Input primitive adapted from v0.0.4@03de921e2400147842ce6c56101b58d514dde77c.

/** Motion in native viewport pixels. Fractional rounding is bounded below one pixel, never a backlog. */
internal class EdgeScrollMotion {
    private var lastNs = 0L
    private var direction = 0
    private var fraction = 0L
    var enabled = true
        set(value) { field=value; if (!value) stop() }

    fun direction(point: PointerPosition, bounds: PointerBounds, expiresAtNs: Long, nowNs: Long): Int = when {
        !enabled || !point.available || nowNs >= expiresAtNs || bounds.height <= 0 -> 0
        point.y < bounds.top -> -1
        point.y >= bounds.bottom -> 1
        else -> 0
    }

    fun stop() { lastNs = 0; direction = 0; fraction = 0 }

    fun step(point: PointerPosition, bounds: PointerBounds, expiresAtNs: Long, nowNs: Long, speed: Int): Int {
        require(speed in 10..1200 && speed%10==0)
        val next = direction(point,bounds,expiresAtNs,nowNs)
        if (next == 0) { stop(); return 0 }
        if (next != direction) { stop(); direction=next; lastNs=nowNs; return 0 }
        val elapsed = (nowNs-lastNs).coerceIn(0,50_000_000)
        lastNs=nowNs
        val distance = next*speed*elapsed+fraction
        val pixels = (distance/1_000_000_000).toInt()
        fraction=distance-pixels*1_000_000_000L
        return pixels
    }
}
