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
    internal inner class Fixture(withFeature: Boolean = false, directFeature: Boolean = false) : AutoCloseable {
        val feature = if (withFeature || directFeature) ReceiverActivationStore(temporary.newFolder().resolve("feature.properties")).also {
            it.startIncarnation()
            if (withFeature) {
                val first = ReceiverLaunchScope("old-scope", "old-trigger", 7)
                it.launch(first, "old-admission")
                it.accept(ActivationReply(ActivationDirective("H1", true, 1, "old-owner"), TriggerReceipt("old-trigger", "H1", TriggerDisposition.MAPPED)))
                val current = ReceiverLaunchScope("current-scope", "current-trigger", 8)
                it.launch(current, "current-admission")
                it.accept(ActivationReply(ActivationDirective("H2", true, 1, "retained-phone-owner"), TriggerReceipt("current-trigger", "H2", TriggerDisposition.MAPPED)))
            }
        } else null
        val queue = LinkedBlockingQueue<() -> Unit>()
        val commands = LinkedBlockingQueue<SessionControl.Request>()
        @Volatile var transport: (SessionControl.Request) -> SessionState? = { null }
        lateinit var client: PairedSessionClient
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
                        { request, _ -> commands.add(request); transport(request) }, { queue.add(it) }, feature = feature).also { client = it }
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

    private fun drainControls(session: ProjectionSession) {
        for(name in listOf("controls","scaleControls")) {
            val lane=session.javaClass.getDeclaredField(name).apply{isAccessible=true}.get(session) as OrderedControlDispatcher
            val until=System.nanoTime()+TimeUnit.SECONDS.toNanos(3)
            while(lane.snapshot().let{it.pending>0||it.inFlight>0}&&System.nanoTime()<until)Thread.yield()
            assertEquals(0L,lane.snapshot().pending);assertEquals(0L,lane.snapshot().inFlight)
        }
    }
    private fun outputFrames(bytes: ByteArrayOutputStream)=DataInputStream(bytes.toByteArray().inputStream()).use { input ->
        buildList{while(input.available()>0)add(Wire.readFrame(input))}
    }

    @Test fun directAndDemandOwnersWriteModeThenTapThroughActualSessionWriter() {
        for (source in listOf("legacy", "direct", "demand")) Fixture(withFeature=source=="demand", directFeature=source=="direct").use { f ->
            val session=f.owner.session!!
            f.owner.presentation(f.view,true,false)
            if (source!="legacy") assertEquals(ReceiverUsabilityMode.NONE,mode(session).mode)
            f.streaming()
            if (source=="legacy") assertNull(session.observationUsability())
            else assertEquals(ReceiverUsabilityMode.PROJECTED,mode(session).mode)
            if (source=="direct") {
                assertNull(f.owner.currentDemand())
                assertNull(f.feature!!.presentation) // Direct readiness does not invent an activation report.
            }
            assertTrue(session.cycleMode())
            assertTrue(session.tap(Tap(20,30,size)))
            drainControls(session)
            assertEquals(listOf(Wire.MODE_CYCLE,Wire.TAP),outputFrames(f.bytes).filter { it.channel in listOf(Wire.MODE_CYCLE,Wire.TAP) }.map { it.channel })
            assertEquals(2L,controls(session).snapshot().written)
            assertEquals(0L,controls(session).snapshot().canceled)
        }
    }

    @Test fun directOwnerNoneAndLocalPanelNeverDeliverModeOrBypassExplicitNone() {
        Fixture(directFeature=true).use { f ->
            val session=f.owner.session!!
            assertEquals(ReceiverUsabilityMode.NONE,mode(session).mode)
            session.presentation(true) // A legacy Boolean cannot override original explicit NONE.
            assertTrue(session.cycleMode());drainControls(session)
            f.owner.presentation(f.view,true,true)
            assertEquals(ReceiverUsabilityMode.LOCAL_PANEL,mode(session).mode)
            assertTrue(session.cycleMode());drainControls(session)
            assertEquals(2L,controls(session).snapshot().canceled)
            assertTrue(outputFrames(f.bytes).none { it.channel==Wire.MODE_CYCLE })
            f.owner.presentation(f.view,true,false);f.streaming()
            assertEquals(ReceiverUsabilityMode.PROJECTED,mode(session).mode)
            assertTrue(session.cycleMode());drainControls(session)
            assertEquals(1,outputFrames(f.bytes).count { it.channel==Wire.MODE_CYCLE })
        }
    }

    private fun controls(session: ProjectionSession) = session.javaClass.getDeclaredField("controls").apply { isAccessible=true }.get(session) as OrderedControlDispatcher

    @Test fun queuedModeRetiresWithOriginalDetachDecoderReplacementAndCloseWithoutReplay() {
        for (source in listOf("legacy", "direct", "demand")) for (loss in listOf("detach", "decoder", "close", "owner", "panel")) {
            if(source=="legacy" && loss=="panel") continue // Legacy Boolean sources have no explicit panel fact.
            Fixture(withFeature=source=="demand", directFeature=source=="direct").use { f ->
                f.owner.presentation(f.view,true,false);f.streaming()
                val original=f.owner.session!!;val lane=controls(original)
                val entered=java.util.concurrent.CountDownLatch(1);val release=java.util.concurrent.CountDownLatch(1)
                assertTrue(lane.submit(0,{true},"test-blocker",false) {
                    entered.countDown()
                    var interrupted=false
                    while(true) try { release.await();break } catch (_:InterruptedException) { interrupted=true }
                    if(interrupted) Thread.currentThread().interrupt()
                })
                assertTrue(entered.await(2,TimeUnit.SECONDS))
                try {
                    assertTrue(original.cycleMode())
                    when(loss) {
                        "detach" -> f.owner.detach(f.view)
                        "decoder" -> original.receive(Frame(Wire.GEOMETRY,payload=Geometry(640,480).bytes()))
                        "close" -> original.close()
                        "owner" -> f.observation(SessionState("replacement-phone-owner",SessionPhase.AVAILABLE))
                        "panel" -> f.owner.presentation(f.view,true,true)
                    }
                    if(loss=="owner") assertNotSame(original,f.owner.session)
                } finally { release.countDown() }
                drainControls(original)
                assertEquals("$source/$loss",1L,lane.snapshot().canceled)
                assertTrue(outputFrames(f.bytes).none { it.channel==Wire.MODE_CYCLE })
                if(loss=="close" || loss=="owner") assertFalse("closed original cannot admit later Mode",original.cycleMode())
                if(loss=="detach") {
                    f.owner.presentation(f.view,true,false) // Retired view cannot restore readiness.
                    val recreated=View();f.owner.attach(recreated,Surface());f.owner.presentation(recreated,true,false)
                    f.pumpUntil("current original presents again") { f.owner.snapshot().state=="STREAMING" }
                    drainControls(original)
                    assertTrue("canceled Mode never replays after attachment",outputFrames(f.bytes).none { it.channel==Wire.MODE_CYCLE })
                    assertTrue(original.cycleMode());drainControls(original)
                    assertEquals(1,outputFrames(f.bytes).count { it.channel==Wire.MODE_CYCLE })
                }
            }
        }
    }







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

    @Test fun capturedCurrentDirectSessionReleasesMediaAndStopsItsOriginalPeer() {
        for (oldTrigger in listOf(false, true)) Fixture(directFeature = true).use { f ->
            f.streaming()
            val scope = ReceiverLaunchScope("direct-scope", "direct-T", 7)
            f.feature!!.launch(scope, "original-admission")
            if (oldTrigger) {
                f.feature.cancel(scope, null)
                f.feature.accept(ActivationReply(trigger = TriggerReceipt(scope.trigger, null, TriggerDisposition.CANCELED)))
            }
            val original = f.owner.snapshot()
            val video = f.output
            val stopped = java.util.concurrent.CountDownLatch(1)
            val allowStop = java.util.concurrent.CountDownLatch(1)
            var complete = 0
            f.transport = { request ->
                if (request.operation == SessionControl.Operation.STOP) {
                    stopped.countDown(); check(allowStop.await(3, TimeUnit.SECONDS))
                }
                SessionState("retired", SessionPhase.INACTIVE, activation = request.cancelTrigger?.let {
                    ActivationReply(trigger = TriggerReceipt(it.triggerId, null, TriggerDisposition.CANCELED))
                })
            }
            try {
                f.owner.disconnect(scope = scope, activationId = null, originalSession = original.session,
                    originalEpoch = original.epoch, complete = { complete++ })
                // These are actual close effects, independent of the client completion callback.
                assertNull(f.owner.session); assertEquals(1, f.codec.releases.get())
                assertTrue(video.surface.texture!!.released)
                assertFalse(f.owner.hasRetainedResources()); assertEquals(listOf(true, false), f.resources)
                assertFalse(original.session!!.navigate(Navigation.HOME))
                assertTrue(stopped.await(2, TimeUnit.SECONDS)); f.pump(); assertEquals(0, complete)
                assertEquals(listOf(original.epoch), f.commands.filter { it.operation == SessionControl.Operation.STOP }.map { it.epoch })
            } finally { allowStop.countDown() }
            f.pumpUntil("original T completion after Stop attempt") { complete == 1 }
            assertTrue(f.feature.state.triggers.getValue(scope.trigger).acknowledged)
            assertNull(f.feature.currentScope())
            f.observation(SessionState(original.epoch!!, SessionPhase.AVAILABLE)); f.pump()
            assertNull(f.owner.session)
        }
    }

    @Test fun oldTriggerAcknowledgmentAloneCannotRetireAnUnrelatedDirectSession() {
        Fixture(directFeature = true).use { f ->
            f.streaming()
            val scope = ReceiverLaunchScope("old-scope", "old-T", 7)
            f.feature!!.launch(scope, "old-admission"); f.feature.cancel(scope, null)
            f.feature.accept(ActivationReply(trigger = TriggerReceipt(scope.trigger, null, TriggerDisposition.CANCELED)))
            val original = f.owner.session
            var complete = 0
            f.owner.disconnect(scope = scope, activationId = null, complete = { complete++ })
            f.pumpUntil("old trigger acknowledged") { complete == 1 }
            assertSame(original, f.owner.session); assertEquals(0, f.codec.releases.get())
            assertTrue(f.commands.none { it.operation == SessionControl.Operation.STOP })
        }
    }

    @Test fun staleOriginalSessionAndLateCallbacksCannotReleaseReplacementDirectMedia() {
        Fixture(directFeature = true).use { f ->
            f.streaming()
            val original = f.owner.snapshot()
            val oldVideo = f.output
            val oldCodec = f.codec
            val nextCodec = MediaCodec(); MediaCodec.created = nextCodec
            oldVideo.redraw(); oldVideo.invalidate() // Old presentation callbacks are already queued.
            f.observation(SessionState("S2-owner", SessionPhase.AVAILABLE))
            val replacement = f.owner.session!!
            assertNotSame(original.session, replacement)
            f.owner.presentation(f.view, true, true)
            f.owner.disconnect(scope = ReceiverLaunchScope("old-scope", "old-T", 7), activationId = "old-H",
                originalSession = original.session, originalEpoch = original.epoch)
            f.pump()
            assertSame(replacement, f.owner.session); assertEquals(0, nextCodec.releases.get())
            assertEquals(1, oldCodec.releases.get()); assertTrue(oldVideo.surface.texture!!.released)
            assertTrue(f.owner.hasRetainedResources()); assertTrue(f.commands.none { it.operation == SessionControl.Operation.STOP })
            assertEquals(ReceiverUsabilityMode.LOCAL_PANEL, mode(replacement).mode)
        }
    }

    @Test fun capturedActivationSessionKeepsDurableTriggerAndActivationCancellation() {
        Fixture(withFeature = true).use { f ->
            f.streaming()
            val original = f.owner.snapshot()
            val scope = f.feature!!.currentScope()!!
            f.transport = { request -> SessionState("retired", SessionPhase.INACTIVE,
                activation = ActivationReply(trigger = request.cancelTrigger?.let {
                    TriggerReceipt(it.triggerId, "H2", TriggerDisposition.CANCELED)
                }, cancelAcknowledged = request.cancelActivation?.activationId)) }
            var complete = 0
            f.owner.disconnect(scope = scope, activationId = "H2", originalSession = original.session,
                originalEpoch = original.epoch, complete = { complete++ })
            assertNull(f.owner.session); assertEquals(1, f.codec.releases.get())
            assertTrue("H2" in f.feature.state.canceled)
            f.pumpUntil("original H settlement") { complete == 1 }
            assertTrue(f.feature.state.pendingCancel.isEmpty())
            assertTrue(f.commands.any { it.cancelActivation?.activationId == "H2" })
            assertTrue(f.commands.any { it.operation == SessionControl.Operation.STOP && it.epoch == original.epoch })
        }
    }

    @Test fun durableCancellationRetryKeepsOriginalSessionAfterReplacement() {
        Fixture(directFeature = true).use { f ->
            f.streaming()
            val scope = ReceiverLaunchScope("original-scope", "original-T", 7)
            f.feature!!.launch(scope, "original-admission")
            val original = f.owner.snapshot()
            val journal = f.feature.javaClass.getDeclaredField("file").apply { isAccessible = true }.get(f.feature) as java.io.File
            val directory = journal.parentFile
            val saved = java.io.File(directory.parentFile, directory.name + "-saved")
            assertTrue(directory.renameTo(saved)); directory.writeText("journal storage unavailable")
            try {
                f.owner.disconnect(scope = scope, activationId = null,
                    originalSession = original.session, originalEpoch = original.epoch)
                assertSame(original.session, f.owner.session)
                assertEquals("CANCEL_PENDING", f.owner.snapshot().state)
                val nextCodec = MediaCodec(); MediaCodec.created = nextCodec
                f.observation(SessionState("S2-owner", SessionPhase.AVAILABLE))
                val next = f.owner.session!!
                assertTrue(directory.delete()); assertTrue(saved.renameTo(directory))
                f.owner.retryCancellation(); f.pump()
                assertSame(next, f.owner.session); assertEquals(0, nextCodec.releases.get())
                assertTrue(f.feature.state.triggers.getValue(scope.trigger).canceled)
                assertTrue(f.commands.none { it.operation == SessionControl.Operation.STOP })
            } finally {
                if (saved.exists()) { directory.delete(); check(saved.renameTo(directory)) }
            }
        }
    }

    @Test fun lateOriginalCompletionCannotReleaseSuccessorAndEpochMustMatch() {
        Fixture(directFeature = true).use { f ->
            f.streaming()
            val scope = ReceiverLaunchScope("scope", "T", 7)
            f.feature!!.launch(scope, "admission"); f.feature.cancel(scope, null)
            f.feature.accept(ActivationReply(trigger = TriggerReceipt(scope.trigger, null, TriggerDisposition.CANCELED)))
            val original = f.owner.snapshot()
            f.owner.disconnect(scope = scope, activationId = null, originalSession = original.session, originalEpoch = "other-epoch")
            assertSame(original.session, f.owner.session); assertEquals(0, f.codec.releases.get())
            var completed = 0
            f.owner.disconnect(scope = scope, activationId = null, originalSession = original.session,
                originalEpoch = original.epoch, complete = { completed++ })
            val executor = f.client.javaClass.getDeclaredField("executor").apply { isAccessible = true }.get(f.client) as java.util.concurrent.ScheduledExecutorService
            executor.submit {}.get(2, TimeUnit.SECONDS) // Completion is queued on the owner lane, not delivered yet.
            assertEquals(0, completed)
            val nextCodec = MediaCodec(); MediaCodec.created = nextCodec
            f.observation(SessionState("S2-owner", SessionPhase.AVAILABLE))
            val next = f.owner.session!!
            f.pump()
            assertEquals(1, completed); assertSame(next, f.owner.session)
            assertEquals(0, nextCodec.releases.get())
            assertEquals(listOf(original.epoch), f.commands.filter { it.operation == SessionControl.Operation.STOP }.map { it.epoch })
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
