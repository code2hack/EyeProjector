package com.code2hack.eyeprojector.directhost

import com.code2hack.eyeprojector.protocol.*
import org.junit.Assert.*
import org.junit.Test
import java.io.DataInputStream
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class PhoneTouchDispatcherTest {
    private val geometry = Geometry(480, 640)
    private fun event(phase: Touch.Phase, x: Int = 10, id: Long = 1000) = Touch(phase, id, Tap(x, 20, geometry))
    @Test fun continuousHoldHasNoTimerReleaseAndEndsAtTheFinalMove() {
        val writes = mutableListOf<ByteArray>()
        val host = PhoneTouchDispatcher { writes.add(it) }; host.geometry(geometry)
        assertTrue(host.touch(event(Touch.Phase.DOWN)))
        assertTrue(host.holding); assertEquals(1, writes.size)
        assertTrue(host.touch(event(Touch.Phase.MOVE, 100)))
        assertTrue(host.touch(event(Touch.Phase.UP, 200)))
        assertFalse(host.holding)
        assertEquals(listOf(0, 2, 1), writes.map { it[1].toInt() })
        assertEquals(200, DataInputStream(writes.last().inputStream()).run { skipBytes(10); readInt() })
    }
    @Test fun resizeAndCloseCancelAndNeverReplayHeldContact() {
        val writes = mutableListOf<ByteArray>()
        val host = PhoneTouchDispatcher { writes.add(it) }; host.geometry(geometry)
        host.touch(event(Touch.Phase.DOWN, 240))
        host.geometry(Geometry(640, 480))
        assertEquals(listOf(0, 3), writes.map { it[1].toInt() }); assertFalse(host.holding)
        val cancel = DataInputStream(writes.last().inputStream()).apply { skipBytes(10) }
        assertEquals(320, cancel.readInt()); assertEquals(15, cancel.readInt())
        assertEquals(640, cancel.readUnsignedShort()); assertEquals(480, cancel.readUnsignedShort())
        assertFalse(host.touch(event(Touch.Phase.UP)))
        host.geometry(geometry); host.touch(event(Touch.Phase.DOWN, id = 2000)); host.close(); host.close()
        assertEquals(listOf(0, 3, 0, 3), writes.map { it[1].toInt() })
        assertFalse(host.touch(event(Touch.Phase.DOWN, id = 3000)))
    }
    @Test fun ordinaryControlsTerminateHeldContactBeforeReusingScrcpyPointer() {
        val writes = mutableListOf<ByteArray>()
        val host = PhoneTouchDispatcher { writes.add(it) }; host.geometry(geometry)
        host.touch(event(Touch.Phase.DOWN)); host.tap(Tap(5, 6, geometry))
        assertEquals(listOf(0, 3, 0), writes.map { it[1].toInt() })
        assertFalse(host.touch(event(Touch.Phase.UP)))
        host.touch(event(Touch.Phase.DOWN, id = 2000)); host.scroll(Scroll(Tap(5, 6, geometry), 160))
        assertEquals(3, writes[writes.size - 2][1].toInt()); assertEquals(3, writes.last()[0].toInt())
    }
    @Test fun closeWaitsForStartedDownAndSerializesCancelAfterIt() {
        val started = CountDownLatch(1); val release = CountDownLatch(1)
        val writes = Collections.synchronizedList(mutableListOf<Int>())
        val host = PhoneTouchDispatcher {
            if (it[1].toInt() == 0) { started.countDown(); check(release.await(2, TimeUnit.SECONDS)) }
            writes.add(it[1].toInt())
        }; host.geometry(geometry)
        val down = Thread { host.touch(event(Touch.Phase.DOWN)) }.apply { start() }
        assertTrue(started.await(2, TimeUnit.SECONDS))
        val close = Thread { host.close() }.apply { start() }
        release.countDown(); down.join(2000); close.join(2000)
        assertFalse(down.isAlive); assertFalse(close.isAlive); assertEquals(listOf(0, 3), writes)
    }
}
