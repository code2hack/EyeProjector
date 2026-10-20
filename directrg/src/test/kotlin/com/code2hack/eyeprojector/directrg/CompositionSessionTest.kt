package com.code2hack.eyeprojector.directrg

import android.media.MediaCodec
import android.view.Surface
import com.code2hack.eyeprojector.protocol.*
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.concurrent.*
import java.util.concurrent.atomic.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Actual composed lifecycle with platform codec doubles; no Android/TLS/hardware claim. */
class CompositionSessionTest {
    @get:Rule val temporary = TemporaryFolder()
    private val original = Geometry(480, 640)
    private val resized = Geometry(384, 512)
    private fun field(s: ProjectionSession, n: String) = s.javaClass.getDeclaredField(n).apply { isAccessible = true }
    private fun running(s: ProjectionSession) = (field(s, "running").get(s) as AtomicBoolean).get()
    private fun await(message: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(1)
        assertTrue(message, condition())
    }
    private fun worker(errors: AtomicReference<Throwable?>, action: () -> Unit) = Thread {
        try { action() } catch (error: Throwable) { errors.compareAndSet(null, error) }
    }.apply { start() }
    private fun joined(t: Thread, errors: AtomicReference<Throwable?>) {
        t.join(4000); assertFalse("worker did not complete", t.isAlive)
        errors.get()?.let { throw AssertionError("worker failed", it) }
    }
    private fun session(state: (ProjectionSession, String) -> Unit = { _, _ -> }, geometry: (ProjectionSession, Geometry) -> Unit = { _, _ -> }) =
        ProjectionSession(temporary.newFolder().resolve("session"), Surface(), state, geometry).also {
            field(it, "output").set(it, DataOutputStream(ByteArrayOutputStream()))
        }

    @Test fun waitingDeliveryIsOutsideCloseMonitorAfterSynchronousInstallAndCanJoinReplacement() {
        val first = MediaCodec().also { MediaCodec.created = it }
        val firstWaiting = AtomicBoolean(true)
        val states = ConcurrentLinkedQueue<String>()
        session(state = { s, value ->
            states.add(value)
            assertFalse("state callback owns closeLock", Thread.holdsLock(field(s, "closeLock").get(s)))
            if (value == "WAITING_FOR_VIDEO" && firstWaiting.compareAndSet(true, false)) {
                assertNotNull("media decoder must be installed synchronously", field(s, "decoder").get(s))
                MediaCodec.created = MediaCodec()
                val errors = AtomicReference<Throwable?>()
                joined(worker(errors) { s.receive(Frame(Wire.GEOMETRY, payload = resized.bytes())) }, errors)
            }
        }).use { s ->
            s.receive(Frame(Wire.GEOMETRY, payload = original.bytes()))
            assertEquals(resized, s.geometry)
            assertNotNull(field(s, "decoder").get(s))
            assertEquals(2, states.count { it == "WAITING_FOR_VIDEO" })
        }
        assertEquals(1, first.releases.get())
    }

    @Test fun reentrantGeometryStopPublishesTerminalOutsideOuterMonitorWithoutAllocation() {
        val codec = MediaCodec().also { MediaCodec.created = it }
        val allocations = AtomicInteger(); codec.at = { if (it == "name") allocations.incrementAndGet() }
        val states = ConcurrentLinkedQueue<String>()
        session(state = { s, value ->
            assertFalse("terminal callback still owns replacement closeLock", Thread.holdsLock(field(s, "closeLock").get(s)))
            states.add(value)
        }, geometry = { s, _ -> s.close() }).use { s ->
            s.receive(Frame(Wire.GEOMETRY, payload = original.bytes()))
            assertFalse(running(s)); assertEquals(0, allocations.get())
            assertEquals(listOf("STOPPED"), states.toList())
        }
    }

    private fun blockedReentrantRelease(activeDrainer: Boolean) {
        val codec = MediaCodec().also { MediaCodec.created = it }
        val states = ConcurrentLinkedQueue<String>()
        val s = session(state = { current, value ->
            if (value == "STOPPED") {
                assertFalse(Thread.holdsLock(field(current, "closeLock").get(current)))
                assertEquals("terminal before detached predecessor release", 1, codec.releases.get())
            }
            states.add(value)
        })
        s.receive(Frame(Wire.GEOMETRY, payload = original.bytes()))
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val drainEntered = CountDownLatch(1); val drainRelease = CountDownLatch(1)
        val errors = AtomicReference<Throwable?>()
        val publisher = field(s, "states").get(s) as StatePublisher
        val drainer = if (activeDrainer) worker(errors) { publisher.publish {
            drainEntered.countDown(); check(drainRelease.await(5, TimeUnit.SECONDS))
        } } else null
        if (activeDrainer) assertTrue(drainEntered.await(3, TimeUnit.SECONDS))
        codec.at = { if (it == "release") {
            s.close() // same-thread cancellation may return, but its outer release is not done
            entered.countDown(); check(release.await(5, TimeUnit.SECONDS))
        } }
        MediaCodec.created = MediaCodec()
        val replacing = worker(errors) { s.receive(Frame(Wire.GEOMETRY, payload = resized.bytes())) }
        var closing: Thread? = null
        val closeDone = CountDownLatch(1)
        try {
            assertTrue("old release did not reach reentry", entered.await(3, TimeUnit.SECONDS))
            assertFalse(running(s))
            if (activeDrainer) {
                drainRelease.countDown(); joined(drainer!!, errors)
                assertFalse("active drainer published staged terminal before release", states.contains("STOPPED"))
            }
            closing = worker(errors) { s.close(); closeDone.countDown() }
            assertFalse("external close bypassed detached release", closeDone.await(80, TimeUnit.MILLISECONDS))
            assertEquals(0, codec.releases.get())
        } finally {
            release.countDown(); drainRelease.countDown(); joined(replacing, errors)
            drainer?.let { joined(it, errors) }; closing?.let { joined(it, errors) }; s.close()
        }
        assertEquals(1, codec.releases.get())
        assertEquals(1, states.count { it == "STOPPED" })
        assertNull(field(s, "decoder").get(s))
    }

    @Test fun externalCloseWaitsForDetachedReleaseAfterSameThreadStop() = blockedReentrantRelease(false)
    @Test fun activeDrainerCannotConsumeReentrantTerminalBeforeOuterReleaseExit() = blockedReentrantRelease(true)

    @Test fun genuineStopCancelsBlockedConstructionThenJoinsUnpublishedRelease() {
        val codec = MediaCodec().also { MediaCodec.created = it }
        val entered = CountDownLatch(1); val construct = CountDownLatch(1)
        codec.at = { if (it == "configure") { entered.countDown(); check(construct.await(5, TimeUnit.SECONDS)) } }
        val errors = AtomicReference<Throwable?>(); val done = CountDownLatch(1)
        val states = ConcurrentLinkedQueue<String>(); val s = session(state = { _, v -> states.add(v) })
        val replacing = worker(errors) { s.receive(Frame(Wire.GEOMETRY, payload = original.bytes())) }
        var closing: Thread? = null
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            closing = worker(errors) { s.close(); done.countDown() }
            await("Stop did not immediately cancel construction") { !running(s) }
            assertFalse(done.await(60, TimeUnit.MILLISECONDS))
            assertFalse(states.contains("STOPPED"))
        } finally { construct.countDown(); joined(replacing, errors); closing?.let { joined(it, errors) }; s.close() }
        assertEquals(1, codec.releases.get()); assertNull(field(s, "decoder").get(s))
        assertEquals(listOf("STOPPED"), states.toList())
    }

    @Test fun gateHeadStopsExistingDrainerWithoutWaitingAndResumesAfterRelease() {
        val publisher = StatePublisher()
        // Reflection keeps this same regression source compilable against the naive component API.
        val gateType = Class.forName("com.code2hack.eyeprojector.directrg.StatePublisher\$Gate")
        val gate = gateType.getDeclaredConstructor().newInstance()
        val enqueue = StatePublisher::class.java.getDeclaredMethod("enqueue", gateType, kotlin.jvm.functions.Function0::class.java)
        val drain = StatePublisher::class.java.getDeclaredMethod("drain")
        val events = mutableListOf<String>()
        enqueue.invoke(publisher, gate, { events.add("staged") })
        drain.invoke(publisher)
        assertTrue(events.isEmpty())
        gateType.getDeclaredMethod("release").invoke(gate)
        drain.invoke(publisher)
        assertEquals(listOf("staged"), events)
    }
}
