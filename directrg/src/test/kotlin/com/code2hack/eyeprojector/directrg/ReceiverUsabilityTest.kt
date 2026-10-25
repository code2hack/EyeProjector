package com.code2hack.eyeprojector.directrg

import android.os.SystemClock
import android.view.Surface
import com.code2hack.eyeprojector.protocol.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.*
import java.util.concurrent.*
import java.util.concurrent.atomic.*

/** Actual Session writer and LatestHeadPointer; controlled TLS output, no platform/injection proof. */
class ReceiverUsabilityTest {
    @get:Rule val temporary = TemporaryFolder()
    @After fun clock() { SystemClock.elapsedNs = null }
    private fun field(s: ProjectionSession, n: String) = s.javaClass.getDeclaredField(n).apply { isAccessible = true }
    private fun fact(r: Long, mode: ReceiverUsabilityMode, input: Long = 10) = ReceiverUsability("owner", ReceiverIncarnation("inc", 1), r, input, mode)
    private fun session(out: OutputStream, revision: () -> Long): ProjectionSession =
        ProjectionSession(temporary.root, Surface(), { _, _ -> }, { _, _ -> }, nextUsabilityRevision = revision).also {
            field(it,"output").set(it,DataOutputStream(out));field(it,"geometry").set(it,Geometry(100,100));field(it,"controlReady").set(it,true)
        }
    private fun await(s: ProjectionSession) {
        val lane=field(s,"scaleControls").get(s) as OrderedControlDispatcher
        val until=System.nanoTime()+TimeUnit.SECONDS.toNanos(3)
        while ((lane.snapshot().pending>0 || lane.snapshot().inFlight>0) && System.nanoTime()<until) Thread.yield()
        assertEquals(0L,lane.snapshot().pending);assertEquals(0L,lane.snapshot().inFlight)
    }
    private fun frames(bytes: ByteArrayOutputStream): List<Frame> = DataInputStream(bytes.toByteArray().inputStream()).use { input ->
        buildList { while (input.available()>0) add(Wire.readFrame(input)) }
    }
    private fun pointer(r: Long) = HeadPointerState(r,1,true,true,20f,30f,Fit(0,0,100,100),Geometry(100,100))

    @Test fun actualQueuedOldPointerSelectsLatestNoneBeforeUnavailablePoint() {
        val bytes=ByteArrayOutputStream();val revisions=AtomicLong()
        session(bytes,revisions::incrementAndGet).use { s ->
            synchronized(field(s,"outputLock").get(s)) {
                s.usability(fact(revisions.incrementAndGet(),ReceiverUsabilityMode.PROJECTED))
                assertTrue(s.headPointer(pointer(11),Long.MAX_VALUE))
                s.usability(fact(revisions.incrementAndGet(),ReceiverUsabilityMode.NONE,11))
            }
            await(s)
            val sent=frames(bytes);assertEquals(listOf(Wire.RECEIVER_USABILITY,Wire.HEAD_POINTER),sent.map {it.channel})
            assertEquals(ReceiverUsabilityMode.NONE,ReceiverUsability.from(sent[0].payload).mode)
            assertFalse(HeadPointerState.from(sent[1].payload).available)
        }
    }
    @Test fun actualMetadataOnlyChangeDuringSelectedBlockedWriteSchedulesOwnFollowup() {
        val entered=CountDownLatch(1);val release=CountDownLatch(1);val first=AtomicBoolean(true)
        val bytes=ByteArrayOutputStream();val revisions=AtomicLong()
        val out=object:OutputStream(){override fun write(b:Int){if(first.compareAndSet(true,false)){entered.countDown();check(release.await(3,TimeUnit.SECONDS))};bytes.write(b)}}
        session(out,revisions::incrementAndGet).use { s ->
            s.usability(fact(revisions.incrementAndGet(),ReceiverUsabilityMode.PROJECTED))
            assertTrue(entered.await(2,TimeUnit.SECONDS))
            // No pointer or heartbeat traffic is available to conceal a lost metadata turn.
            s.usability(fact(revisions.incrementAndGet(),ReceiverUsabilityMode.NONE))
            release.countDown();await(s)
            assertEquals(listOf(ReceiverUsabilityMode.PROJECTED,ReceiverUsabilityMode.NONE),frames(bytes).map {ReceiverUsability.from(it.payload).mode})
        }
    }
    @Test fun explicitOwnerNoneAllocatedBeforeWriterPromotionStillWinsAfterSelection() {
        val revisions=AtomicLong();val entered=CountDownLatch(1);val release=CountDownLatch(1);val gate=AtomicBoolean()
        val bytes=ByteArrayOutputStream()
        session(bytes) {
            val next=revisions.incrementAndGet()
            if(gate.compareAndSet(true,false)){entered.countDown();check(release.await(3,TimeUnit.SECONDS))}
            next
        }.use { s ->
            s.usability(fact(revisions.incrementAndGet(),ReceiverUsabilityMode.PROJECTED));await(s)
            val none=fact(revisions.incrementAndGet(),ReceiverUsabilityMode.NONE,11)
            gate.set(true);s.headPointer(pointer(11),Long.MAX_VALUE)
            assertTrue(entered.await(2,TimeUnit.SECONDS)) // Actual compose holds source selection while allocating promotion.
            val done=CountDownLatch(1)
            val owner=Thread { s.usability(none);done.countDown() };owner.start()
            release.countDown();assertTrue(done.await(2,TimeUnit.SECONDS));owner.join(1000);await(s)
            val modes=frames(bytes).filter{it.channel==Wire.RECEIVER_USABILITY}.map{ReceiverUsability.from(it.payload)}
            assertEquals(ReceiverUsabilityMode.NONE,modes.last().mode)
            s.usability(fact(revisions.incrementAndGet(),ReceiverUsabilityMode.LOCAL_PANEL,12));await(s)
            assertEquals(ReceiverUsabilityMode.LOCAL_PANEL,ReceiverUsability.from(frames(bytes).last().payload).mode)
        }
    }
    @Test fun pointerDeadlineExpiryDoesNotChangeExplicitUsableMode() {
        val bytes=ByteArrayOutputStream();val revisions=AtomicLong();SystemClock.elapsedNs=1000
        session(bytes,revisions::incrementAndGet).use { s ->
            synchronized(field(s,"outputLock").get(s)) {
                s.usability(fact(revisions.incrementAndGet(),ReceiverUsabilityMode.PROJECTED))
                s.headPointer(pointer(10),900)
            };await(s)
            val sent=frames(bytes)
            assertEquals(ReceiverUsabilityMode.PROJECTED,ReceiverUsability.from(sent.first().payload).mode)
            assertFalse(HeadPointerState.from(sent.last().payload).available)
        }
    }
    @Test fun codecWire6RoundtripAndMalformedFacts() {
        for(mode in ReceiverUsabilityMode.entries){val v=fact(17,mode);assertEquals(v,ReceiverUsability.from(v.bytes()))}
        val v=fact(1,ReceiverUsabilityMode.NONE).bytes()
        for(bad in listOf(v.copyOf(v.size-1),v+byteArrayOf(0),v.copyOf().also { it[it.lastIndex]=127 })) {
            assertTrue(runCatching { ReceiverUsability.from(bad) }.isFailure)
        }
        val bytes=ByteArrayOutputStream();Wire.writeFrame(DataOutputStream(bytes),Frame(Wire.RECEIVER_USABILITY,payload=v))
        assertArrayEquals(v,Wire.readFrame(DataInputStream(bytes.toByteArray().inputStream())).payload)
        assertEquals(6,Wire.VERSION)
    }
}
