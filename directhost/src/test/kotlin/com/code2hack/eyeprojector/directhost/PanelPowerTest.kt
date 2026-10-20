package com.code2hack.eyeprojector.directhost

import com.code2hack.eyeprojector.protocol.*
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.Assert.*
import org.junit.Test

class PanelPowerTest {
    @Test fun fixedStockCommandsAndLocalResultNeverClaimPhysicalObservation() {
        assertArrayEquals(byteArrayOf(10, 0), Scrcpy.displayPower(false))
        assertArrayEquals(byteArrayOf(10, 1), Scrcpy.displayPower(true))
        val written = mutableListOf<ByteArray>()
        val panel = SessionPanelPower(Any(), { true }, written::add)
        val off = panel.requestOff(42)
        assertEquals(PanelSubmission.SUBMITTED, off.off)
        assertEquals(PanelRestoration.PENDING, off.restoration)
        assertEquals(PanelObservation.UNVERIFIED, off.observation)
        assertEquals(off, PanelPowerState.from(off.bytes()))
        panel.close()
        assertEquals(PanelRestoration.SUBMITTED, panel.state().restoration)
        assertEquals(PanelObservation.UNVERIFIED, panel.state().observation)
        assertFalse(panel.state().sessionActive)
        assertEquals(2, written.size)
        assertArrayEquals(byteArrayOf(10, 0, 10, 1), written.flatMap { it.toList() }.toByteArray())
        panel.close()
        assertEquals(PanelSubmission.UNAVAILABLE, panel.requestOff(43).off)
        assertEquals(2, written.size)
    }

    @Test fun inactiveAndNeverBlankedSessionsDoNotChangePanelPower() {
        var active = false
        val written = mutableListOf<ByteArray>()
        val panel = SessionPanelPower(Any(), { active }, written::add)
        assertEquals(PanelSubmission.UNAVAILABLE, panel.requestOff(1).off)
        active = true
        assertEquals(PanelSubmission.NOT_REQUESTED, panel.state().off)
        panel.close()
        assertEquals(PanelRestoration.NOT_NEEDED, panel.state().restoration)
        assertTrue(written.isEmpty())
        val next = SessionPanelPower(Any(), { true }, written::add)
        assertEquals(PanelSubmission.NOT_REQUESTED, next.state().off)
        assertEquals(PanelRestoration.NOT_NEEDED, next.state().restoration)
    }

    @Test fun partialOffFailureStillOwesRestorationAndRestoreFailureIsRetained() {
        val attempts = mutableListOf<ByteArray>()
        val panel = SessionPanelPower(Any(), { true }) { attempts.add(it); throw IOException("partial write") }
        assertEquals(PanelSubmission.FAILED, panel.requestOff(17).off)
        assertEquals(PanelRestoration.PENDING, panel.state().restoration)
        panel.close()
        assertEquals(PanelRestoration.FAILED, panel.state().restoration)
        assertEquals(PanelObservation.UNVERIFIED, panel.state().observation)
        assertArrayEquals(Scrcpy.displayPower(true), attempts.last())
        assertEquals(2, attempts.size)
        panel.close()
        assertEquals(2, attempts.size)
    }

    @Test fun offSerializesBehindWholeInputAndCloseThenRejectsLaterOff() {
        val lock = Any()
        val entered = CountDownLatch(1)
        val finishTap = CountDownLatch(1)
        val output = ByteArrayOutputStream()
        val target = Tap(10, 20, Geometry(480, 640))
        val touch = PhoneTouchDispatcher(lock) { payload ->
            output.write(payload, 0, 1)
            entered.countDown()
            check(finishTap.await(2, TimeUnit.SECONDS))
            output.write(payload, 1, payload.size - 1)
        }
        touch.geometry(target.geometry)
        val panel = SessionPanelPower(lock, { true }) { output.write(it) }
        val tap = thread { touch.tap(target) }
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        val off = thread { panel.requestOff(11) }
        try { assertEquals(PanelSubmission.NOT_REQUESTED, panel.state().off) }
        finally { finishTap.countDown() }
        tap.join(2000); off.join(2000)
        assertFalse(tap.isAlive); assertFalse(off.isAlive)
        panel.close()
        assertArrayEquals(Scrcpy.tap(target) + Scrcpy.displayPower(false) + Scrcpy.displayPower(true), output.toByteArray())
        assertEquals(PanelSubmission.UNAVAILABLE, panel.requestOff(12).off)
    }

    @Test fun repeatedOffRetainsOneSessionRestorationAndReportsPendingWithoutWaitingForWriter() {
        val entered = CountDownLatch(1)
        val finish = CountDownLatch(1)
        var writes = 0
        val panel = SessionPanelPower(Any(), { true }) {
            if (++writes == 2) { entered.countDown(); check(finish.await(2, TimeUnit.SECONDS)) }
        }
        panel.requestOff(1)
        val second = thread { panel.requestOff(2) }
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        try {
            assertEquals(2L, panel.state().requestId)
            assertEquals(PanelSubmission.PENDING, panel.state().off)
            assertEquals(PanelRestoration.PENDING, panel.state().restoration)
        } finally { finish.countDown() }
        second.join(2000)
        panel.close()
        assertEquals(3, writes)
        assertEquals(PanelRestoration.SUBMITTED, panel.state().restoration)
    }

    @Test fun localResultRejectsMalformedPayloads() {
        val payload = PanelPowerState.unavailable().bytes()
        for (size in listOf(0, 23, 25)) {
            assertThrows(IllegalArgumentException::class.java) { PanelPowerState.from(payload.copyOf(size)) }
        }
        payload[23] = 2
        assertThrows(IllegalArgumentException::class.java) { PanelPowerState.from(payload) }
    }
}
