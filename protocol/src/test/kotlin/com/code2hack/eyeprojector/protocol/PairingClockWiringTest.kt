package com.code2hack.eyeprojector.protocol

import android.os.SystemClock
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class PairingClockWiringTest {
    @Test fun productionOwnerExpiresCodeAndResetsBudgetAcrossSimulatedDeepSleep() {
        SystemClock.elapsed = 100L
        SystemClock.awake = 100L
        val owner = LanPairing(PeerStore(File("build/tmp/clock-owner-${System.nanoTime()}")), PeerRole.GLASSES, {})
        try {
            val old = owner.codes.display()
            repeat(5) { assertNotNull(owner.codes.beginAttempt()) }
            assertNull(owner.codes.beginAttempt())
            SystemClock.elapsed += 300_000L // Sleep advances elapsed time, not awake time.
            assertEquals(100L, SystemClock.uptimeMillis())
            val fresh = owner.codes.display()
            assertFalse(owner.codes.isCurrent(old))
            assertNotEquals(old.code, fresh.code)
            assertEquals(old.generation + 1, fresh.generation)
            assertEquals(300L, fresh.remainingSeconds)
            assertEquals(5, fresh.attemptsRemaining)
            owner.codes.finishAttempt(old, true)
            assertEquals(5, owner.codes.display().attemptsRemaining)
        } finally { owner.close(); SystemClock.elapsed = 0; SystemClock.awake = 0 }
    }
}
