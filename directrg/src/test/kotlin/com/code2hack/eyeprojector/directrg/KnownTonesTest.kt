package com.code2hack.eyeprojector.directrg

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.*
import org.junit.Test

class KnownTonesTest {
    @Test fun distinguishesOwnedSingleMixedAndExpiredSignalsWithoutRetainingPcm() {
        fun block(a: Double, b: Double): ByteBuffer = ByteBuffer.allocate(4800 * 4).order(ByteOrder.LITTLE_ENDIAN).apply {
            repeat(4800) { i ->
                val value = (32767 * (a * sin(2 * PI * 440 * i / 48_000) + b * sin(2 * PI * 1000 * i / 48_000))).toInt().toShort()
                putShort(value); putShort(value)
            }; flip()
        }
        val tones = KnownTones()
        tones.observe(block(.02, 0.0), 1_000_000_000)
        assertEquals(.02, tones.peaks(1_000_000_000).first, .001)
        assertEquals(0.0, tones.peaks(1_000_000_000).second, .001)
        tones.observe(block(.02, .025), 2_000_000_001)
        assertEquals(.02, tones.peaks(2_000_000_001).first, .001)
        assertEquals(.025, tones.peaks(2_000_000_001).second, .001)
        assertEquals(0.0 to 0.0, tones.peaks(3_000_000_002))
    }
}
