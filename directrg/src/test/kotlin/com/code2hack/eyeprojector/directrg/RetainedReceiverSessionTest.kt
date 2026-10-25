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
    internal class View : ReceiverSessionOwner.View {
        val states = mutableListOf<ReceiverSessionOwner.Snapshot>()
        val inputs = mutableListOf<ReceiverInput>()
        override fun state(snapshot: ReceiverSessionOwner.Snapshot) { states.add(snapshot) }
        override fun input(source: ProjectionSession, event: ReceiverInput) { inputs.add(event) }
    }
    internal inner class Fixture(withFeature: Boolean = false) : AutoCloseable {
        val feature = if (withFeature) ReceiverActivationStore(temporary.newFolder().resolve("feature.properties")).also {
            it.startIncarnation()
            val first = ReceiverLaunchScope("old-scope", "old-trigger", 7)
            it.launch(first, "old-admission")
            it.accept(ActivationReply(ActivationDirective("H1", true, 1, "old-owner"), TriggerReceipt("old-trigger", "H1", TriggerDisposition.MAPPED)))
            val current = ReceiverLaunchScope("current-scope", "current-trigger", 8)
            it.launch(current, "current-admission")
            it.accept(ActivationReply(ActivationDirective("H2", true, 1, "retained-phone-owner"), TriggerReceipt("current-trigger", "H2", TriggerDisposition.MAPPED)))
        } else null
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
                }, feature = feature, clientFactory = { state, unavailable ->
                    observation = state
                    PairedSessionClient(temporary.newFolder(), state, unavailable,
                        { request, _ -> commands.add(request); null }, { queue.add(it) }, feature = feature)
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

    private fun mode(session: ProjectionSession): ReceiverUsability = session.javaClass.getDeclaredField("sourceUsability").apply { isAccessible = true }.get(session) as ReceiverUsability

    @Test fun revisionOnlyPublicationDoesNotRecursivelyRedeliverUiSnapshot() {
        Fixture(withFeature=true).use { f ->
            var calls=0
            val view=object:ReceiverSessionOwner.View {
                override fun state(snapshot:ReceiverSessionOwner.Snapshot) {
                    check(++calls<4) { "Mode publication recursively delivered the same UI state" }
                    // Explicit UI cancellation boundary: Activity cancellation advances this owner revision,
                    // then its passive pointer producer re-publishes current presentation.
                    f.owner.advanceInputRevision();f.owner.presentation(this,true,true)
                }
                override fun input(source:ProjectionSession,event:ReceiverInput)=Unit
            }
            f.owner.attach(view,null)
            assertTrue(calls in 1..2)
            assertEquals(ReceiverUsabilityMode.LOCAL_PANEL,mode(f.owner.session!!).mode)
        }
    }

    @Test fun explicitLocalPanelFocusAndDetachDoNotDependOnProjectedVideo() {
        Fixture(withFeature = true).use { f ->
            val session = f.owner.session!!
            f.owner.presentation(f.view, true, true)
            assertEquals(ReceiverUsabilityMode.LOCAL_PANEL, mode(session).mode)
            f.owner.presentation(f.view, false, true)
            assertEquals(ReceiverUsabilityMode.NONE, mode(session).mode)
            f.owner.presentation(f.view, true, true)
            f.owner.detach(f.view)
            assertEquals(ReceiverUsabilityMode.NONE, mode(session).mode)
            f.owner.presentation(f.view, true, true) // Retired view has no authority.
            assertEquals(ReceiverUsabilityMode.NONE, mode(session).mode)
        }
    }

    @Test fun changedDirectiveCannotStampAnotherOwnerOnOriginalMedia() {
        Fixture(withFeature = true).use { f ->
            val session = f.owner.session!!
            f.owner.presentation(f.view, true, true)
            f.feature!!.accept(ActivationReply(ActivationDirective("H3", true, 1, "new-owner")))
            // The real client can accept on its executor before main receives the peer update.
            f.owner.presentation(f.view, false, true)
            assertEquals("retained-phone-owner", mode(session).ownerEpoch)
            assertEquals(ReceiverUsabilityMode.NONE, mode(session).mode)
        }
    }

    @Test fun pausedPositiveOwnerCannotOvertakeReplacementLossFromNoneOrProjected() {
        for(initiallyShown in listOf(false,true)) Fixture(withFeature=true).use { f ->
            f.streaming();f.owner.presentation(f.view,initiallyShown,false)
            val session=f.owner.session!!
            val entered=java.util.concurrent.CountDownLatch(1);val release=java.util.concurrent.CountDownLatch(1)
            val first=java.util.concurrent.atomic.AtomicBoolean(true)
            val ownerCounter=f.owner.javaClass.getDeclaredField("usabilityRevision").apply{isAccessible=true}.get(f.owner) as java.util.concurrent.atomic.AtomicLong
            session.javaClass.getDeclaredField("nextUsabilityRevision").apply{isAccessible=true}.set(session,{
                if(first.compareAndSet(true,false)){entered.countDown();check(release.await(3,TimeUnit.SECONDS))}
                ownerCounter.incrementAndGet()
            } as () -> Long)
            val publishing=Thread { f.owner.advanceInputRevision();f.owner.presentation(f.view,true,false) }
            publishing.start();assertTrue(entered.await(2,TimeUnit.SECONDS))
            val geometry=Thread { session.receive(Frame(Wire.GEOMETRY,payload=Geometry(640,480).bytes())) }
            geometry.start()
            val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(2)
            while(geometry.state!=Thread.State.BLOCKED && System.nanoTime()<end)Thread.yield()
            assertEquals(Thread.State.BLOCKED,geometry.state)
            release.countDown();publishing.join(2000);geometry.join(2000)
            assertFalse(publishing.isAlive);assertFalse(geometry.isAlive)
            assertEquals(ReceiverUsabilityMode.NONE,mode(session).mode)
            // A late legacy positive callback cannot override the source's newer explicit NONE.
            session.presentation(true)
            session.headPointer(HeadPointerState(99,1,true,false,1f,1f,null,null),Long.MAX_VALUE)
            val point=session.javaClass.getDeclaredField("lastHeadPointer").apply{isAccessible=true}.get(session) as HeadPointerState
            assertFalse(point.available)
        }
    }

    @Test fun decoderGenerationFencePrecedesConcurrentOwnerPublicationAndNoneAllocation() {
        Fixture(withFeature=true).use { f ->
            f.streaming();f.owner.presentation(f.view,true,false)
            val session=f.owner.session!!
            val entered=java.util.concurrent.CountDownLatch(1);val release=java.util.concurrent.CountDownLatch(1)
            val first=java.util.concurrent.atomic.AtomicBoolean(true)
            val revisions=java.util.concurrent.atomic.AtomicLong(mode(session).revision+10)
            session.javaClass.getDeclaredField("nextUsabilityRevision").apply{isAccessible=true}.set(session,{
                if(first.compareAndSet(true,false)){entered.countDown();check(release.await(3,TimeUnit.SECONDS))}
                revisions.incrementAndGet()
            } as () -> Long)
            val geometry=Thread { session.receive(Frame(Wire.GEOMETRY,payload=Geometry(640,480).bytes())) }
            geometry.start();assertTrue(entered.await(2,TimeUnit.SECONDS))
            val publishing=Thread { f.owner.advanceInputRevision();f.owner.presentation(f.view,true,false) }
            publishing.start()
            val until=System.nanoTime()+TimeUnit.SECONDS.toNanos(2)
            while(mode(session).mode!=ReceiverUsabilityMode.NONE && System.nanoTime()<until)Thread.yield()
            val publishedMode=mode(session).mode
            release.countDown();geometry.join(2000);publishing.join(2000)
            assertFalse(geometry.isAlive);assertFalse(publishing.isAlive)
            assertEquals(ReceiverUsabilityMode.NONE,publishedMode)
        }
    }

    @Test fun replacementNoneCannotBeUndoneByOwnerPublishBeforeQueuedGeometryDelivery() {
        Fixture(withFeature = true).use { f ->
            f.streaming();f.owner.presentation(f.view, true, false)
            val session=f.owner.session!!
            assertEquals(ReceiverUsabilityMode.PROJECTED,mode(session).mode)
            session.receive(Frame(Wire.GEOMETRY,payload=Geometry(640,480).bytes()))
            assertEquals(ReceiverUsabilityMode.NONE,mode(session).mode)
            // Deliberately do not pump the queued replacement invalidation yet.
            f.owner.presentation(f.view, false, false);f.owner.presentation(f.view, true, false)
            assertEquals(ReceiverUsabilityMode.NONE,mode(session).mode)
            f.pump()
            assertEquals(ReceiverUsabilityMode.NONE,mode(session).mode)
            f.frame(33_333);f.pumpUntil("current replacement frame restores actual projected mode") {
                mode(session).mode==ReceiverUsabilityMode.PROJECTED
            }
        }
    }

    @Test fun oldScopeCancellationDoesNotReleaseCurrentMediaOrPresentation() {
        Fixture(withFeature = true).use { f ->
            f.streaming()
            f.owner.presentation(f.view, true, true)
            val session = f.owner.session
            assertTrue(f.feature!!.presentation!!.presented)
            f.owner.disconnect(scope = ReceiverLaunchScope("old-scope", "old-trigger", 7), activationId = "H1")
            f.pump()
            assertSame(session, f.owner.session)
            assertEquals(0, f.codec.releases.get())
            assertEquals("STREAMING", f.owner.snapshot().state)
            assertTrue(f.feature.presentation!!.presented)
        }
    }

    @Test fun idempotentLaunchOnRecreationRetainsStaticStreamingWithoutNewEncodedFrame() {
        Fixture(withFeature = true).use { f ->
            f.streaming(); val session = f.owner.session
            f.owner.launch(ReceiverLaunchScope("current-scope", "current-trigger", 8))
            assertEquals("STREAMING", f.owner.snapshot().state)
            f.owner.detach(f.view)
            f.owner.attach(f.view, Surface())
            f.pumpUntil("static retained image after repeated same trigger") { f.owner.snapshot().state == "STREAMING" }
            assertSame(session, f.owner.session)
        }
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
