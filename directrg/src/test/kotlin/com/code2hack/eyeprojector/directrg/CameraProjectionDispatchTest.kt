package com.code2hack.eyeprojector.directrg

import android.view.Surface
import com.code2hack.eyeprojector.directrg.localcamera.forwarding.*
import com.code2hack.eyeprojector.protocol.*
import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.net.Socket
import java.nio.file.Files
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLSocket

class CameraProjectionDispatchTest {
    private val ref = CameraUseRef("captured-phone", "original-session", 0)
    private val request = CameraStreamRequest(640, 480, 35, "video/avc")

    private class FailOnceOutput(private val prefixBytes: Int) : OutputStream() {
        val bytes = ByteArrayOutputStream()
        val attempts = AtomicInteger()
        val failed = CountDownLatch(1)
        val error = IOException("original projection write failed")
        private var armed = true
        override fun write(value: Int) {
            attempts.incrementAndGet()
            if (armed && bytes.size() == prefixBytes) {
                armed = false // A later write would succeed, so unchanged output proves denial.
                failed.countDown()
                throw error
            }
            bytes.write(value)
        }
    }

    private fun output(session: ProjectionSession, sink: OutputStream) {
        ProjectionSession::class.java.getDeclaredField("output").apply { isAccessible = true }
            .set(session, DataOutputStream(sink))
    }
    private fun cameraSender(session: ProjectionSession): (Frame) -> Unit =
        ReceiverCameraRoleClient::class.java.getDeclaredField("sendProjection").apply { isAccessible = true }
            .get(session.cameraUses) as (Frame) -> Unit
    private fun running(session: ProjectionSession) =
        ProjectionSession::class.java.getDeclaredField("running").apply { isAccessible = true }.get(session) as AtomicBoolean
    private fun writing(session: ProjectionSession) =
        ProjectionSession::class.java.getDeclaredField("writing").apply { isAccessible = true }.get(session) as AtomicLong
    private fun original(session: ProjectionSession): CapturedPhoneCameraUse {
        session.receive(Frame(Wire.CAMERA_USE, payload = CameraFeed.useBytes(CameraUseMessage.Prepare(ref, request))))
        return session.cameraUses.receivedUses().single()
    }
    private fun unobservedProgress() = Frame(Wire.CAMERA_PROGRESS,
        payload = CameraFeed.progressBytes(CameraProgress(ref, null, CameraStage.UNKNOWN, CameraFact.UNOBSERVED)))

    /** Actual role/ledger/source/socket ownership, held at the existing connector boundary. */
    private class PendingRole(session: ProjectionSession, original: CapturedPhoneCameraUse) {
        private val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val retired = CountDownLatch(1)
        val retires = AtomicInteger()
        val socketCloses = AtomicInteger()
        val reasons = CopyOnWriteArrayList<CameraEndReason>()
        val observations = CopyOnWriteArrayList<RoleCloseObservation>()
        val ends = CopyOnWriteArrayList<CameraMediaEnd>()
        private val socket = object : Socket() {
            override fun close() { socketCloses.incrementAndGet(); super.close() }
        }
        private val source = object : CameraOutboundMediaSource {
            override fun next(): CameraOutboundRecord? = error("Pending connector cannot supply media")
            override fun requestRetire(reason: CameraEndReason) {
                retires.incrementAndGet(); reasons.add(reason); release.countDown(); retired.countDown()
            }
        }
        val role: OwnedCameraRole
        init {
            val connector: (File, (Socket) -> Unit) -> SSLSocket = { _, opened ->
                opened(socket); entered.countDown(); release.await()
                throw IOException("captured pending connector retired")
            }
            ReceiverCameraRoleClient::class.java.getDeclaredField("connector").apply { isAccessible = true }
                .set(session.cameraUses, connector)
            role = session.cameraUses.connect(original, source, object : CameraRoleEvents {
                override fun changed(role: OwnedCameraRole, value: RoleCloseObservation) { observations.add(value) }
                override fun remoteEnd(role: OwnedCameraRole, value: CameraMediaEnd) { ends.add(value) }
            })
        }
        fun awaitEntered() { assertTrue(entered.await(2, TimeUnit.SECONDS)) }
        fun joined(): RoleCloseObservation {
            val result = AtomicReference<RoleCloseObservation>()
            val done = CountDownLatch(1)
            role.closeAsync(Executor { it.run() }) { result.set(it); done.countDown() }
            assertTrue(done.await(3, TimeUnit.SECONDS))
            return result.get()
        }
    }

    @Test fun capturedProjectionFailureRetiresOriginalRolesAndDeniesLaterWrites() {
        for (prefixBytes in listOf(0, 7)) {
            val root = Files.createTempDirectory("camera-projection-write-").toFile()
            val states = CopyOnWriteArrayList<Pair<ProjectionSession, String>>()
            val session = ProjectionSession(File(root, "original/session").apply { mkdirs() }, Surface(),
                { owner, state -> states.add(owner to state) }, { _, _ -> }, expectedPhoneOwnerEpoch = "captured-phone")
            val successor = ProjectionSession(File(root, "successor/session").apply { mkdirs() }, Surface(),
                { _, _ -> }, { _, _ -> }, expectedPhoneOwnerEpoch = "captured-phone")
            var firstRole: PendingRole? = null; var nextRole: PendingRole? = null
            try {
                val firstUse = original(session); val nextUse = original(successor)
                firstRole = PendingRole(session, firstUse); firstRole.awaitEntered()
                nextRole = PendingRole(successor, nextUse); nextRole.awaitEntered()
                val sink = FailOnceOutput(prefixBytes); output(session, sink)
                val nextSink = ByteArrayOutputStream(); output(successor, nextSink)
                val capturedSend = cameraSender(session)
                assertSame(sink.error, assertThrows(IOException::class.java) { capturedSend(unobservedProgress()) })
                assertEquals(prefixBytes, sink.bytes.size())
                assertEquals(listOf(session to "DISCONNECTED"), states.toList())
                assertFalse(running(session).get()); assertEquals(0L, writing(session).get())
                val stats = ProjectionSession::class.java.getDeclaredField("stats").apply { isAccessible = true }.get(session).toString()
                assertTrue(stats.contains("errorType")); assertTrue(stats.contains("IOException"))
                assertFalse(firstUse.live); assertTrue(firstRole.retired.await(3, TimeUnit.SECONDS))
                val closed = firstRole.joined()
                assertEquals(CameraEndReason.PROJECTION_CLOSED, closed.failure)
                assertTrue(closed.writerReturned); assertTrue(closed.writerJoined)
                assertEquals(true, closed.sourceRetireReturned); assertEquals(true, closed.socketCloseReturned)
                assertEquals(1, firstRole.retires.get()); assertEquals(1, firstRole.socketCloses.get())
                assertEquals(listOf(CameraEndReason.PROJECTION_CLOSED), firstRole.reasons.toList())
                val attempts = sink.attempts.get()
                assertThrows(IllegalStateException::class.java) { capturedSend(unobservedProgress()) }
                assertThrows(IllegalStateException::class.java) { session.heartbeat() }
                assertEquals(attempts, sink.attempts.get()); assertEquals(prefixBytes, sink.bytes.size())
                assertTrue(nextUse.live); assertTrue(running(successor).get())
                assertFalse(nextRole.role.snapshot().retirementRequested); assertEquals(0, nextRole.retires.get())
                cameraSender(successor)(unobservedProgress())
                assertTrue(nextSink.size() > 0); assertTrue(nextUse.live)
            } finally {
                try { session.close() } finally {
                    try { successor.close() } finally {
                        firstRole?.release?.countDown(); nextRole?.release?.countDown()
                        try { firstRole?.joined() } finally {
                            try { nextRole?.joined() } finally { root.deleteRecursively() }
                        }
                    }
                }
            }
        }
    }

    @Test fun failedProjectionReleasesWriterLockBeforeFailureDiagnostics() {
        val root = Files.createTempDirectory("camera-projection-lock-").toFile()
        val session = ProjectionSession(File(root, "session").apply { mkdirs() }, Surface(), { _, _ -> }, { _, _ -> },
            expectedPhoneOwnerEpoch = "captured-phone")
        val sink = FailOnceOutput(7); output(session, sink)
        val workers = Executors.newFixedThreadPool(2)
        try {
            val capturedSend = cameraSender(session)
            val failed = synchronized(session) {
                val pending = workers.submit(Callable<Throwable?> { try { capturedSend(unobservedProgress()); null } catch (error: Throwable) { error } })
                assertTrue(sink.failed.await(2, TimeUnit.SECONDS))
                val rejected = workers.submit(Callable<Throwable?> { try { session.heartbeat(); null } catch (error: Throwable) { error } })
                assertTrue(rejected.get(1, TimeUnit.SECONDS) is IllegalStateException)
                assertEquals(7, sink.bytes.size()); assertEquals(8, sink.attempts.get())
                pending
            }
            assertSame(sink.error, failed.get(2, TimeUnit.SECONDS))
        } finally { workers.shutdownNow(); session.close(); root.deleteRecursively() }
    }

    @Test fun terminalObserverFailureCannotMaskOriginalProjectionWriteFailure() {
        val root = Files.createTempDirectory("camera-projection-observer-").toFile()
        val observerFailure = AssertionError("terminal observer failure")
        val session = ProjectionSession(File(root, "session").apply { mkdirs() }, Surface(), { _, state ->
            if (state == "DISCONNECTED") throw observerFailure
        }, { _, _ -> }, expectedPhoneOwnerEpoch = "captured-phone")
        try {
            val use = original(session); val sink = FailOnceOutput(7); output(session, sink)
            assertSame(sink.error, assertThrows(IOException::class.java) { cameraSender(session)(unobservedProgress()) })
            assertTrue(sink.error.suppressed.any { it === observerFailure })
            assertFalse(use.live); assertFalse(running(session).get())
        } finally { session.close(); root.deleteRecursively() }
    }

    @Test fun cameraObserverExceptionStaysLocalAndProjectionStillWrites() {
        val root = Files.createTempDirectory("camera-local-observer-").toFile()
        val states = CopyOnWriteArrayList<String>()
        val session = ProjectionSession(File(root, "session").apply { mkdirs() }, Surface(), { _, state -> states.add(state) },
            { _, _ -> }, expectedPhoneOwnerEpoch = "captured-phone")
        try {
            val sink = ByteArrayOutputStream(); output(session, sink)
            session.cameraUses.setUseObserver { throw IllegalStateException("local use observer failure") }
            val use = original(session)
            val events = ReceiverCameraRoleClient::class.java.getDeclaredField("events").apply { isAccessible = true }
                .get(session.cameraUses) as ExecutorService
            events.submit {}.get(2, TimeUnit.SECONDS)
            assertTrue(session.cameraUses.eventDeliveryFailed); assertTrue(use.live); assertTrue(running(session).get())
            assertFalse(states.contains("DISCONNECTED")); assertEquals(0, sink.size())
            cameraSender(session)(unobservedProgress())
            val frame = Wire.readFrame(DataInputStream(sink.toByteArray().inputStream()))
            assertEquals(Wire.CAMERA_PROGRESS, frame.channel)
            assertEquals(CameraStage.UNKNOWN, CameraFeed.readProgress(frame.payload).stage)
            assertEquals(CameraFact.UNOBSERVED, CameraFeed.readProgress(frame.payload).fact)
            assertTrue(use.live)
        } finally { session.close(); root.deleteRecursively() }
    }
    @Test fun actualProjectionReaderUsesCapturedEpochAndDispatchesCallbacksOffReader() {
        val directory = Files.createTempDirectory("camera-projection-").toFile()
        val release = CountDownLatch(1); val entered = CountDownLatch(1)
        val session = ProjectionSession(directory, Surface(), { _, _ -> }, { _, _ -> }, expectedPhoneOwnerEpoch = "captured-phone")
        try {
            session.cameraUses.setUseObserver { entered.countDown(); release.await() }
            val reader = Executors.newSingleThreadExecutor()
            try {
                reader.submit { session.receive(Frame(Wire.CAMERA_USE, payload = CameraFeed.useBytes(CameraUseMessage.Prepare(ref, request)))) }.get(1, TimeUnit.SECONDS)
                assertTrue(entered.await(2, TimeUnit.SECONDS))
                val original = session.cameraUses.receivedUses().single()
                assertTrue(original.live)
                session.receive(Frame(Wire.CAMERA_USE, payload = CameraFeed.useBytes(CameraUseMessage.Prepare(ref.copy(ownerEpoch = "current-successor", useGeneration = 1), request))))
                assertEquals(1, session.cameraUses.receivedUses().size)
                session.receive(Frame(Wire.CAMERA_PROGRESS, payload = CameraFeed.progressBytes(CameraProgress(ref, null, CameraStage.PERMISSION, CameraFact.PENDING))))
                assertEquals(CameraFact.PENDING, session.cameraUses.remoteProgress(original)!!.fact)
                session.receive(Frame(Wire.CAMERA_USE, payload = CameraFeed.useBytes(CameraUseMessage.Close(ref, CameraEndReason.ORIGINAL_INPUT_CLOSED))))
                assertFalse(original.live)
                session.receive(Frame(Wire.CAMERA_USE, payload = CameraFeed.useBytes(CameraUseMessage.Prepare(ref, request))))
                assertEquals(1, session.cameraUses.receivedUses().size)
            } finally { reader.shutdownNow() }
        } finally { release.countDown(); session.close(); directory.deleteRecursively() }
    }
    @Test fun projectionRejectsCameraSocketDirectionsAndEndRetiresExactHandles() {
        val directory = Files.createTempDirectory("camera-direction-").toFile()
        val session = ProjectionSession(directory, Surface(), { _, _ -> }, { _, _ -> }, expectedPhoneOwnerEpoch = "captured-phone")
        try {
            val prepare = CameraFeed.useBytes(CameraUseMessage.Prepare(ref, request))
            assertThrows(Exception::class.java) { session.receive(Frame(Wire.CAMERA_USE, 1, prepare)) }
            assertThrows(Exception::class.java) { session.receive(Frame(Wire.CAMERA_USE, payload = prepare + byteArrayOf(0))) }
            for (channel in listOf(Wire.CAMERA_STREAM, Wire.CAMERA_MEDIA, Wire.CAMERA_END))
                assertThrows(Exception::class.java) { session.receive(Frame(channel)) }
            session.receive(Frame(Wire.CAMERA_USE, payload = prepare))
            val original = session.cameraUses.receivedUses().single(); assertTrue(original.live)
            session.receive(Frame(Wire.END)); assertFalse(original.live)
        } finally { session.close(); directory.deleteRecursively() }
    }
}
