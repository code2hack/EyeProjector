package com.code2hack.eyeprojector.directrg

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sqrt

/** Retains only two known fixture frequency amplitudes, never PCM or a recording. */
internal class KnownTones {
    private data class Sample(val atNs: Long, val hz440: Double, val hz1000: Double)
    private val samples = ArrayDeque<Sample>()
    @Synchronized fun observe(pcm: ByteBuffer, nowNs: Long) {
        val input = pcm.duplicate().order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val frames = input.remaining() / 2
        if (frames == 0) return
        val c440 = 2 * cos(2 * PI * 440 / 48_000)
        val c1000 = 2 * cos(2 * PI * 1000 / 48_000)
        var a1 = 0.0; var a2 = 0.0; var b1 = 0.0; var b2 = 0.0
        repeat(frames) {
            val value = (input.get().toDouble() + input.get()) / 65536
            val a = value + c440 * a1 - a2; a2 = a1; a1 = a
            val b = value + c1000 * b1 - b2; b2 = b1; b1 = b
        }
        fun amplitude(x: Double, y: Double, coefficient: Double) =
            2 * sqrt((x * x + y * y - coefficient * x * y).coerceAtLeast(0.0)) / frames
        samples.addLast(Sample(nowNs, amplitude(a1, a2, c440), amplitude(b1, b2, c1000)))
        while (samples.size > 100 || samples.first().atNs < nowNs - 1_000_000_000L) samples.removeFirst()
    }
    @Synchronized fun peaks(nowNs: Long): Pair<Double, Double> {
        val recent = samples.filter { nowNs - it.atNs in 0..1_000_000_000L }
        return (recent.maxOfOrNull { it.hz440 } ?: 0.0) to (recent.maxOfOrNull { it.hz1000 } ?: 0.0)
    }
}
