package com.code2hack.eyeprojector.directrg

import android.media.MediaCodec
import android.view.Surface
import com.code2hack.eyeprojector.protocol.*
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Actual session/decoder/dispatcher code, platform codec double and in-memory TLS output. */
class ReadinessSessionTest {
    @get:Rule val temporary = TemporaryFolder()
    private val original = Geometry(480, 640)
    private val resized = Geometry(384, 512)

    @Test fun blockedStatusPersistenceDoesNotHoldActualRenderCallbackOrTerminalPublication() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        android.util.AtomicFile.beforeFinish = { file ->
            if (file.name == "status.json" && file.path.startsWith(temporary.root.path)) {
                entered.countDown(); check(release.await(5, TimeUnit.SECONDS))
            }
        }
        try {
            Fixture(render = false).use { fixture ->
                assertTrue(entered.await(2, TimeUnit.SECONDS))
                frame(fixture.session)
                await("actual render callback progresses while status persistence is blocked") {
                    fixture.states.contains("STREAMING")
                }
                fixture.session.close()
                assertTrue("terminal callback must not join blocked disk persistence", fixture.states.contains("STOPPED"))
                assertFalse(running(fixture.session))
            }
        } finally { release.countDown(); android.util.AtomicFile.beforeFinish = null }
    }

    private class Codec {
        val value = MediaCodec()
        init {
            val queued = LinkedBlockingQueue<Long>()
            var outputPts = 0L
            value.queuedInput = { pts, flags -> if (flags == 0) queued.add(pts) }
            value.output = { info -> queued.poll()?.let { info.presentationTimeUs = it; outputPts = it; 0 } ?: -1 }
            value.releasedOutput = { render ->
                if (render) value.frameListener!!.onFrameRendered(value, outputPts, 0)
            }
        }
    }

    private fun field(session: ProjectionSession, name: String) =
        session.javaClass.getDeclaredField(name).apply { isAccessible = true }
    private fun readiness(session: ProjectionSession) = field(session, "videoReadiness").get(session) as VideoReadiness
    private fun running(session: ProjectionSession) = (field(session, "running").get(session) as AtomicBoolean).get()
    private fun await(message: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (!condition() && System.nanoTime() < deadline) LockSupport.parkNanos(1_000_000)
        assertTrue(message, condition())
    }
    private fun expiry(session: ProjectionSession) = readiness(session).expiry(System.nanoTime() + 6_000_000_000)!!
    private fun closeExpired(session: ProjectionSession, expiry: VideoReadiness.Expiry) {
        val method = session.javaClass.getDeclaredMethod("closeWithState", String::class.java, VideoReadiness.Expiry::class.java)
        method.isAccessible = true
        try { method.invoke(session, "VIDEO_STALLED", expiry) }
        catch (error: InvocationTargetException) { throw error.targetException }
    }
    private fun publishRender(session: ProjectionSession, epoch: Any) {
        // Resume the actual state-publication continuation after rendered(epoch) accepted it.
        val method = session.javaClass.getDeclaredMethod("state", String::class.java, Any::class.java)
        method.isAccessible = true
        try { method.invoke(session, "STREAMING", epoch) }
        catch (error: InvocationTargetException) { throw error.targetException }
    }
    private fun frame(session: ProjectionSession) = session.receive(Frame(Wire.VIDEO, 0, byteArrayOf(1)))

    private inner class Fixture(
        render: Boolean = true,
        onGeometry: (ProjectionSession, Geometry) -> Unit = { _, _ -> },
        onState: (ProjectionSession, String) -> Unit = { _, _ -> },
    ) : AutoCloseable {
        val first = Codec().value
        val states = ConcurrentLinkedQueue<String>()
        val output = ByteArrayOutputStream()
        val session: ProjectionSession
        init {
            MediaCodec.created = first
            session = ProjectionSession(temporary.newFolder().resolve("session"), Surface(),
                { current, state -> states.add(state); onState(current, state) }, onGeometry)
            field(session, "output").set(session, DataOutputStream(output))
            session.receive(Frame(Wire.GEOMETRY, payload = original.bytes()))
            if (render) { frame(session); await("initial actual render/publication") { states.contains("STREAMING") } }
        }
        override fun close() = session.close()
    }

    private fun task(error: AtomicReference<Throwable?>, action: () -> Unit) = Thread {
        try { action() } catch (failure: Throwable) { error.compareAndSet(null, failure) }
    }.apply { start() }
    private fun joined(thread: Thread, error: AtomicReference<Throwable?>) {
        thread.join(3000)
        assertFalse("bounded test worker still running", thread.isAlive)
        error.get()?.let { throw AssertionError("test worker failed", it) }
    }

    @Test fun agedReplacementReleaseDoesNotExpireAndPingContinues() {
        Fixture().use { fixture ->
            val session = fixture.session
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val errors = AtomicReference<Throwable?>()
            fixture.first.at = { if (it == "release") { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) } }
            val next = Codec().value
            MediaCodec.created = next
            val replacement = task(errors) { session.receive(Frame(Wire.GEOMETRY, payload = resized.bytes())) }
            val heartbeat = Executors.newSingleThreadExecutor()
            try {
                assertTrue(entered.await(3, TimeUnit.SECONDS))
                assertFalse(readiness(session).ready)
                // A 28s-aged previous stream must not supply the replacement's deadline.
                heartbeat.submit { repeat(3) { session.heartbeat(System.nanoTime() + 28_000_000_000) } }.get(1, TimeUnit.SECONDS)
                assertTrue(running(session))
                assertFalse(fixture.states.contains("VIDEO_STALLED"))
                assertFalse(session.tap(Tap(1, 1, resized)))
                val input = DataInputStream(fixture.output.toByteArray().inputStream())
                repeat(3) { assertEquals(Wire.PING, Wire.readFrame(input).channel) }
                assertEquals(0, input.available())
            } finally { release.countDown(); heartbeat.shutdownNow(); joined(replacement, errors) }
            assertEquals(1, fixture.first.releases.get())
            assertEquals(resized, session.geometry)
            assertFalse(readiness(session).ready)
            frame(session)
            await("new decoder's actual render") { readiness(session).ready }
            session.heartbeat(System.nanoTime() + 28_000_000_000)
            assertTrue(running(session))
            session.close()
            assertEquals(1, next.releases.get())
        }
    }

    @Test fun legitimateUnrenderedFirstFrameWaitStillExpires() {
        Fixture(render = false).use { fixture ->
            fixture.session.heartbeat(System.nanoTime() + 6_000_000_000)
            assertFalse(running(fixture.session))
            assertTrue(fixture.states.contains("VIDEO_STALLED"))
            assertEquals(1, fixture.first.releases.get())
        }
    }

    @Test fun actualRenderRetiresComputedExpiryBeforeCancellation() {
        Fixture(render = false).use { fixture ->
            val expired = expiry(fixture.session)
            frame(fixture.session)
            await("actual first render") { readiness(fixture.session).ready }
            closeExpired(fixture.session, expired)
            assertTrue(running(fixture.session))
            assertFalse(fixture.states.contains("VIDEO_STALLED"))
            assertEquals(0, fixture.first.releases.get())
        }
    }

    @Test fun newGeometryRetiresComputedExpiryBeforeCancellation() {
        Fixture(render = false).use { fixture ->
            val expired = expiry(fixture.session)
            val next = Codec().value
            MediaCodec.created = next
            fixture.session.receive(Frame(Wire.GEOMETRY, payload = resized.bytes()))
            closeExpired(fixture.session, expired)
            assertTrue(running(fixture.session))
            assertFalse(fixture.states.contains("VIDEO_STALLED"))
            assertFalse(readiness(fixture.session).ready)
            assertEquals(1, fixture.first.releases.get())
            assertEquals(0, next.releases.get())
            frame(fixture.session)
            await("replacement render") { readiness(fixture.session).ready }
        }
    }

    @Test fun delayedOldRenderCannotPublishAfterNewGeometryWaiting() {
        Fixture().use { fixture ->
            val oldEpoch = field(fixture.session, "decoderEpoch").get(fixture.session)!!
            MediaCodec.created = Codec().value
            fixture.session.receive(Frame(Wire.GEOMETRY, payload = resized.bytes()))
            assertEquals("WAITING_FOR_VIDEO", fixture.states.last())
            val count = fixture.states.size
            publishRender(fixture.session, oldEpoch)
            assertEquals(count, fixture.states.size)
            assertEquals("WAITING_FOR_VIDEO", fixture.states.last())
            assertFalse(readiness(fixture.session).ready)
        }
    }

    @Test fun delayedOldRenderCannotPublishAfterStop() {
        Fixture().use { fixture ->
            val oldEpoch = field(fixture.session, "decoderEpoch").get(fixture.session)!!
            fixture.session.close()
            val count = fixture.states.size
            publishRender(fixture.session, oldEpoch)
            assertEquals(count, fixture.states.size)
            assertEquals("STOPPED", fixture.states.last())
        }
    }

    private fun whileStateDeliveryIsPaused(session: ProjectionSession, action: () -> Unit) {
        val publisher = field(session, "states").get(session) as StatePublisher
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val errors = AtomicReference<Throwable?>()
        val delivery = task(errors) { publisher.publish {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
        } }
        try { assertTrue(entered.await(3, TimeUnit.SECONDS)); action() }
        finally { release.countDown(); joined(delivery, errors) }
    }

    @Test fun actualRenderQueuedBehindCallbackIsRetiredByNewDecoderEpoch() {
        Fixture(render = false).use { fixture ->
            whileStateDeliveryIsPaused(fixture.session) {
                frame(fixture.session)
                await("actual old render while delivery paused") { readiness(fixture.session).ready }
                MediaCodec.created = Codec().value
                fixture.session.receive(Frame(Wire.GEOMETRY, payload = resized.bytes()))
                assertEquals(resized, fixture.session.geometry)
                assertFalse(readiness(fixture.session).ready)
            }
            assertEquals(listOf("WAITING_FOR_VIDEO", "WAITING_FOR_VIDEO"), fixture.states.toList())
            frame(fixture.session)
            await("current epoch can publish") { fixture.states.last() == "STREAMING" }
        }
    }

    @Test fun actualRenderQueuedBehindCallbackIsRetiredByStop() {
        Fixture(render = false).use { fixture ->
            whileStateDeliveryIsPaused(fixture.session) {
                frame(fixture.session)
                await("actual render while delivery paused") { readiness(fixture.session).ready }
                fixture.session.close()
                assertFalse(running(fixture.session))
                assertEquals(1, fixture.first.releases.get())
            }
            assertEquals(listOf("WAITING_FOR_VIDEO", "STOPPED"), fixture.states.toList())
        }
    }

    @Test fun renderCallbackCanWaitForConcurrentReplacementWithoutHoldingPublicationOrLifecycleLocks() {
        val callbackDone = CountDownLatch(1)
        val callbackError = AtomicReference<Throwable?>()
        Fixture(render = false, onState = { session, state -> if (state == "STREAMING") {
            MediaCodec.created = Codec().value
            val replacement = task(callbackError) { session.receive(Frame(Wire.GEOMETRY, payload = resized.bytes())) }
            try { joined(replacement, callbackError) } catch (error: Throwable) { callbackError.set(error) }
            finally { callbackDone.countDown() }
        } }).use { fixture ->
            frame(fixture.session)
            assertTrue(callbackDone.await(4, TimeUnit.SECONDS))
            callbackError.get()?.let { throw AssertionError("callback blocked replacement", it) }
            await("new waiting notification follows old render callback") { fixture.states.last() == "WAITING_FOR_VIDEO" }
            assertEquals(listOf("WAITING_FOR_VIDEO", "STREAMING", "WAITING_FOR_VIDEO"), fixture.states.toList())
            assertEquals(resized, fixture.session.geometry)
            assertFalse(readiness(fixture.session).ready)
        }
    }

    @Test fun genuineStopDuringOldReleaseCancelsBeforeNewAllocation() {
        Fixture().use { fixture ->
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val errors = AtomicReference<Throwable?>()
            fixture.first.at = { if (it == "release") { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) } }
            val next = Codec().value
            val allocations = AtomicInteger()
            next.at = { if (it == "name") allocations.incrementAndGet() }
            MediaCodec.created = next
            val replacement = task(errors) { fixture.session.receive(Frame(Wire.GEOMETRY, payload = resized.bytes())) }
            val closed = CountDownLatch(1)
            var closing: Thread? = null
            try {
                assertTrue(entered.await(3, TimeUnit.SECONDS))
                closing = task(errors) { fixture.session.close(); closed.countDown() }
                await("Stop cancels before waiting for old release") { !running(fixture.session) }
                assertFalse(readiness(fixture.session).ready)
                assertEquals(0, allocations.get())
                assertFalse("close must join old release", closed.await(60, TimeUnit.MILLISECONDS))
                assertFalse(fixture.states.contains("STOPPED"))
            } finally { release.countDown(); joined(replacement, errors); closing?.let { joined(it, errors) } }
            assertEquals(0, allocations.get())
            assertEquals(1, fixture.first.releases.get())
            assertEquals(0, next.releases.get())
        }
    }

    @Test fun stopDuringConstructionReleasesUnpublishedDecoder() {
        Fixture().use { fixture ->
            val entered = CountDownLatch(1)
            val construct = CountDownLatch(1)
            val errors = AtomicReference<Throwable?>()
            val next = Codec().value
            next.at = { if (it == "configure") { entered.countDown(); check(construct.await(5, TimeUnit.SECONDS)) } }
            MediaCodec.created = next
            val replacement = task(errors) { fixture.session.receive(Frame(Wire.GEOMETRY, payload = resized.bytes())) }
            val closed = CountDownLatch(1)
            var closing: Thread? = null
            try {
                assertTrue(entered.await(3, TimeUnit.SECONDS))
                closing = task(errors) { fixture.session.close(); closed.countDown() }
                await("Stop cancels before waiting for construction") { !running(fixture.session) }
                assertFalse(readiness(fixture.session).ready)
                assertFalse("close must join unpublished codec release", closed.await(60, TimeUnit.MILLISECONDS))
                assertFalse(fixture.states.contains("STOPPED"))
            } finally { construct.countDown(); joined(replacement, errors); closing?.let { joined(it, errors) } }
            assertNull(field(fixture.session, "decoder").get(fixture.session))
            assertEquals(1, next.releases.get())
            assertEquals("STOPPED", fixture.states.last())
        }
    }

    @Test fun geometryCallbackCanStopBeforeNewCodecAllocation() {
        Fixture(onGeometry = { session, size -> if (size == resized) session.close() }).use { fixture ->
            val next = Codec().value
            val allocations = AtomicInteger()
            next.at = { if (it == "name") allocations.incrementAndGet() }
            MediaCodec.created = next
            fixture.session.receive(Frame(Wire.GEOMETRY, payload = resized.bytes()))
            assertFalse(running(fixture.session))
            assertEquals(0, allocations.get())
            assertEquals(1, fixture.first.releases.get())
            assertEquals(0, next.releases.get())
        }
    }

    @Test fun oldCodecReleaseCanReenterStopWithoutLockInversion() {
        Fixture().use { fixture ->
            fixture.first.at = { if (it == "release") fixture.session.close() }
            val next = Codec().value
            MediaCodec.created = next
            val errors = AtomicReference<Throwable?>()
            joined(task(errors) { fixture.session.receive(Frame(Wire.GEOMETRY, payload = resized.bytes())) }, errors)
            assertFalse(running(fixture.session))
            assertEquals(1, fixture.first.releases.get())
            assertEquals(0, next.releases.get())
        }
    }

    @Test fun terminalCallbackCanReenterAndObserveReleasedCodec() {
        val first = AtomicReference<MediaCodec>()
        val callbacks = AtomicInteger()
        Fixture(onState = { session, state -> if (state == "STOPPED") {
            assertEquals(1, first.get().releases.get())
            val error = AtomicReference<Throwable?>()
            joined(task(error) { session.close() }, error)
            session.close()
            callbacks.incrementAndGet()
        } }).use { fixture ->
            first.set(fixture.first)
            fixture.session.close()
            assertEquals(1, callbacks.get())
        }
    }

    @Test fun concurrentStopAndExpiryReleaseThePublishedCodecOnce() {
        Fixture(render = false).use { fixture ->
            val expired = expiry(fixture.session)
            val go = CountDownLatch(1)
            val errors = AtomicReference<Throwable?>()
            val stop = task(errors) { check(go.await(3, TimeUnit.SECONDS)); fixture.session.close() }
            val timer = task(errors) { check(go.await(3, TimeUnit.SECONDS)); closeExpired(fixture.session, expired) }
            go.countDown(); joined(stop, errors); joined(timer, errors)
            assertFalse(running(fixture.session))
            assertEquals(1, fixture.first.releases.get())
            assertEquals(1, fixture.states.count { it == "STOPPED" || it == "VIDEO_STALLED" })
        }
    }
}
