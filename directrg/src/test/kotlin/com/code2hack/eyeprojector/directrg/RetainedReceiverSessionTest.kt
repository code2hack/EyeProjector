package com.code2hack.eyeprojector.directrg

import android.media.MediaCodec
import android.opengl.EGL14
import android.view.Surface
import com.code2hack.eyeprojector.protocol.*
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.DataInputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Actual owner, session, decoder and GL adapter. Android/GPU/TLS boundaries are doubles. */
class RetainedReceiverSessionTest {
    @get:Rule val temporary = TemporaryFolder()
    private val size = Geometry(480, 640)
    private class View : ReceiverSessionOwner.View {
        val states = mutableListOf<ReceiverSessionOwner.Snapshot>()
        val inputs = mutableListOf<ReceiverInput>()
        override fun state(snapshot: ReceiverSessionOwner.Snapshot) { states.add(snapshot) }
        override fun input(source: ProjectionSession, event: ReceiverInput) { inputs.add(event) }
    }
    private inner class Fixture : AutoCloseable {
        val queue = LinkedBlockingQueue<() -> Unit>()
        val commands = LinkedBlockingQueue<SessionControl.Request>()
        val resources = mutableListOf<Boolean>()
        val background = mutableListOf<ReceiverInput>()
        lateinit var observation: (SessionState) -> Unit
        lateinit var output: RetainedVideoOutput
        val codec = MediaCodec()
        val bytes = ByteArrayOutputStream()
        val owner: ReceiverSessionOwner
        val view = View()
        val first = Surface()
        init {
            EGL14.failCurrent = false; EGL14.failSwap = false; EGL14.failQuery = false; EGL14.error = 0
            EGL14.swaps.clear()
            MediaCodec.created = codec
            val pts = LinkedBlockingQueue<Long>()
            var renderedPts = 0L
            codec.queuedInput = { value, flags -> if (flags == 0) pts.add(value) }
            codec.output = { info -> pts.poll()?.let { renderedPts = it; info.presentationTimeUs = it; 0 } ?: -1 }
            codec.releasedOutput = { render -> if (render) {
                codec.configuredSurface!!.texture!!.frame(renderedPts)
                codec.frameListener!!.onFrameRendered(codec, renderedPts, 0)
            } }
            owner = ReceiverSessionOwner(temporary.newFolder().resolve("session"), null,
                { queue.add(it) }, {}, resources::add, { _, event -> background.add(event) },
                { presented, failed -> try { RetainedVideoOutput(presented, failed).also { output = it } }
                    catch (error: Exception) { throw AssertionError("renderer construction", error) } },
                startSession = { session ->
                    session.javaClass.getDeclaredField("output").apply { isAccessible = true }.set(session, DataOutputStream(bytes))
                    try { session.receive(Frame(Wire.GEOMETRY, payload = size.bytes())) }
                    catch (error: Exception) { throw AssertionError("decoder construction", error) }
                }, clientFactory = { state, unavailable ->
                    observation = state
                    PairedSessionClient(temporary.newFolder(), state, unavailable,
                        { request, _ -> commands.add(request); null }, { queue.add(it) })
                })
            owner.attach(view, first)
            observation(SessionState("retained-phone-owner", SessionPhase.AVAILABLE))
            assertNotNull("owner failed setup: ${owner.snapshot()}", owner.session)
        }
        fun pumpUntil(message: String, condition: () -> Boolean) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (!condition() && System.nanoTime() < deadline) queue.poll(20, TimeUnit.MILLISECONDS)?.invoke()
            assertTrue(message, condition())
        }
        fun pump() { while (true) (queue.poll() ?: return).invoke() }
        fun frame(pts: Long = 0) { owner.session!!.receive(Frame(Wire.VIDEO, pts, byteArrayOf(1))) }
        fun streaming() { frame(); pumpUntil("current window presented") { owner.snapshot().state == "STREAMING" } }
        override fun close() { EGL14.failCurrent = false; EGL14.failSwap = false; EGL14.failQuery = false; owner.close(); pump() }
    }

    @Test fun repeatedPauseSurfaceLossAndActivityReplacementKeepSameSessionAndStaticImage() {
        Fixture().use { f ->
            f.streaming()
            val retained = f.owner.session
            repeat(4) { cycle ->
                f.owner.detach(f.view)
                assertEquals("DISPLAY_DETACHED", f.owner.snapshot().state)
                assertSame(retained, f.owner.session)
                assertEquals(0, f.codec.releases.get())
                assertFalse(retained!!.tap(Tap(1, 1, size)))
                val updates = f.output.surface.texture!!.updates.get()
                f.frame((cycle + 1) * 33_333L)
                f.pumpUntil("offscreen consumer drains detached video") { f.output.surface.texture!!.updates.get() > updates }
                val next = Surface()
                val recreated = View()
                f.owner.attach(recreated, next)
                f.pumpUntil("static texture presents without another encoded packet") { f.owner.snapshot().state == "STREAMING" }
                assertSame(retained, f.owner.session)
                assertSame(next, EGL14.swaps.last())
                assertEquals("retained-phone-owner", f.owner.snapshot().epoch)
                f.owner.detach(recreated)
                f.owner.attach(f.view, Surface())
                f.pumpUntil("return to original observer") { f.owner.snapshot().state == "STREAMING" }
            }
            assertTrue(f.commands.none { it.operation == SessionControl.Operation.STOP })
            assertEquals(listOf(true), f.resources)
        }
    }

    @Test fun stalePresentationQueuedBeforeDetachCannotEnableInputForNewWindow() {
        Fixture().use { f ->
            f.streaming()
            val old = f.first
            f.output.redraw()
            // Synchronous attach waits for preceding GL work, but its presentation callback is still queued.
            f.owner.attachSurface(f.view, null)
            old.release()
            f.pump()
            assertEquals("DISPLAY_DETACHED", f.owner.snapshot().state)
            assertFalse(f.owner.session!!.navigate(Navigation.HOME))
            f.owner.attachSurface(f.view, Surface())
            f.pumpUntil("new window presents") { f.owner.snapshot().state == "STREAMING" }
            assertTrue(f.owner.session!!.navigate(Navigation.HOME))
        }
    }

    @Test fun authenticatedControlArrivesInBackgroundButDetachedOperationsAreNeverReplayed() {
        Fixture().use { f ->
            f.streaming(); f.owner.detach(f.view)
            val event = ReceiverInput(ReceiverInputKind.PANEL, 7)
            f.owner.session!!.receive(Frame(Wire.RECEIVER_INPUT, payload = event.bytes()))
            f.pumpUntil("background authenticated control seam") { f.background.size == 1 }
            assertTrue(f.view.inputs.isEmpty())
            f.owner.attach(f.view, Surface())
            f.pumpUntil("reattached") { f.owner.snapshot().state == "STREAMING" }
            assertTrue(f.view.inputs.isEmpty())
            f.owner.session!!.receive(Frame(Wire.RECEIVER_INPUT, payload = event.bytes()))
            f.pumpUntil("later usable input delivered") { f.view.inputs.size == 1 }
            assertEquals(2, f.background.size)
        }
    }

    @Test fun stopWhileDetachedReleasesExactlyOnceAndOldCallbacksCannotRestoreSession() {
        val f = Fixture()
        try {
            f.streaming(); val retained = f.owner.session!!
            f.owner.detach(f.view)
            f.output.redraw()
            f.owner.disconnect()
            f.pump()
            assertNull(f.owner.session)
            assertEquals(1, f.codec.releases.get())
            assertTrue(f.output.surface.texture!!.released)
            assertEquals(listOf(true, false), f.resources)
            f.owner.attach(f.view, Surface())
            f.observation(SessionState("retained-phone-owner", SessionPhase.AVAILABLE))
            f.pump()
            assertNull(f.owner.session)
            assertFalse(retained.navigate(Navigation.HOME))
            f.pumpUntil("explicit Stop reaches paired control owner") { f.commands.any { it.operation == SessionControl.Operation.STOP } }
        } finally { f.close() }
        assertEquals(1, f.codec.releases.get())
    }

    @Test fun peerEndWhileDetachedReleasesVideoAndSessionResources() {
        Fixture().use { f ->
            f.streaming(); f.owner.detach(f.view)
            f.owner.session!!.receive(Frame(Wire.END))
            f.pumpUntil("terminal state released retained session") { f.owner.session == null }
            assertEquals(1, f.codec.releases.get())
            assertEquals(listOf(true, false), f.resources)
            assertEquals("DISCONNECTED", f.owner.snapshot().state)
        }
    }

    @Test fun lostNativeWindowDetachesPresentationButKeepsOffscreenDecode() {
        Fixture().use { f ->
            f.streaming()
            EGL14.failQuery = true; EGL14.error = EGL14.EGL_BAD_SURFACE
            f.output.redraw()
            f.pumpUntil("native window loss detached") { f.owner.snapshot().state == "DISPLAY_DETACHED" }
            assertNotNull(f.owner.session); assertEquals(0, f.codec.releases.get())
            EGL14.failQuery = false
            f.owner.attachSurface(f.view, Surface())
            f.pumpUntil("fresh window restores static texture") { f.owner.snapshot().state == "STREAMING" }
        }
    }

    @Test fun detachedFirstFrameStillUsesExistingVideoLivenessAndReleasesOnStall() {
        Fixture().use { f ->
            f.owner.detach(f.view)
            f.owner.session!!.heartbeat(System.nanoTime() + 6_000_000_000L)
            f.pumpUntil("first-frame failure while detached") { f.owner.session == null }
            assertEquals("VIDEO_STALLED", f.owner.snapshot().state)
            assertEquals(1, f.codec.releases.get())
            assertEquals(listOf(true, false), f.resources)
        }
    }

    @Test fun recreatedInputContextKeepsMonotonicPointerSequenceAndRetiresPhonePointerOnDetach() {
        Fixture().use { f ->
            f.streaming()
            fun pointers(): List<HeadPointerState> {
                val input = DataInputStream(f.bytes.toByteArray().inputStream())
                val values = mutableListOf<HeadPointerState>()
                try { while (input.available() > 0) {
                    val frame = Wire.readFrame(input)
                    if (frame.channel == Wire.HEAD_POINTER) values.add(HeadPointerState.from(frame.payload))
                } } catch (_: java.io.EOFException) { }
                return values
            }
            val revision = f.owner.advanceInputRevision()
            val point = HeadPointerState(revision, 99, true, false, 20f, 30f, null, null)
            f.owner.session!!.headPointer(point, Long.MAX_VALUE)
            f.pumpUntil("first Phone pointer") { pointers().any { it.available } }
            val first = pointers().last()
            f.owner.detach(f.view)
            f.pumpUntil("detached Phone pointer unavailable") { pointers().lastOrNull()?.available == false }
            val detached = pointers().last()
            assertTrue(detached.sequence > first.sequence)
            f.owner.attach(View(), Surface())
            f.pumpUntil("recreated presentation") { f.owner.snapshot().state == "STREAMING" }
            val nextRevision = f.owner.advanceInputRevision()
            assertTrue(nextRevision > revision)
            f.owner.session!!.headPointer(point.copy(revision = nextRevision, sequence = 0), Long.MAX_VALUE)
            f.pumpUntil("new Activity's zero local sequence accepted by retained allocator") { pointers().lastOrNull()?.revision == nextRevision }
            assertTrue(pointers().last().available)
            assertTrue(pointers().last().sequence > detached.sequence)
        }
    }

    @Test fun delayedWindowLossFromOldAttachmentCannotDetachNewPresentation() {
        Fixture().use { f ->
            f.streaming()
            EGL14.failSwap = true; EGL14.error = EGL14.EGL_BAD_SURFACE
            f.output.redraw()
            f.output.invalidate() // Barrier: old failure is queued for owner delivery, not delivered yet.
            EGL14.failSwap = false
            val next = Surface()
            f.owner.attachSurface(f.view, next)
            f.output.surface.texture!!.frame(33_333)
            f.pumpUntil("new attachment survives delayed old window loss") { f.owner.snapshot().state == "STREAMING" }
            assertSame(next, EGL14.swaps.last())
            assertTrue(f.owner.session!!.navigate(Navigation.HOME))
        }
    }

    @Test fun actualEglContextFailureReleasesLocksAndThreadEvenWhenCleanupFails() {
        Fixture().use { f ->
            f.streaming()
            val texture = f.output.surface.texture!!
            EGL14.failCurrent = true
            texture.frame(33_333)
            f.pumpUntil("context failure releases session") { f.owner.session == null }
            assertEquals(1, f.codec.releases.get())
            assertTrue(texture.released)
            assertEquals(listOf(true, false), f.resources)
            assertFalse(Thread.getAllStackTraces().keys.any { it.name == "eyeprojector-video-output" && it.isAlive })
        }
    }
}
