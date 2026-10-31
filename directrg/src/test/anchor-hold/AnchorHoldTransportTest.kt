package com.code2hack.eyeprojector.directrg

import android.view.Surface
import com.code2hack.eyeprojector.protocol.*
import java.io.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AnchorHoldTransportTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun field(session: ProjectionSession, name: String) = session.javaClass.getDeclaredField(name).apply { isAccessible = true }
    private fun drain(session: ProjectionSession) {
        for (name in listOf("controls", "scaleControls")) {
            val work = field(session, name).get(session) as OrderedControlDispatcher
            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (work.snapshot().let { it.pending > 0 || it.inFlight > 0 } && System.nanoTime() < until) Thread.yield()
            assertEquals(0L, work.snapshot().pending); assertEquals(0L, work.snapshot().inFlight)
        }
    }
    private fun artifact(): List<Frame> {
        val path = File(checkNotNull(System.getenv("EYEPROJECTOR_HOLD_PHONE_ARTIFACT")))
        return DataInputStream(path.inputStream()).use { input -> buildList { while (input.available() > 0) add(Wire.readFrame(input)) } }
    }

    @Test fun actualPhoneStateArtifactPassesActualGlassesSessionAndPersistentIndicator() {
        val frames = artifact()
        val original = frames.filter { it.channel == Wire.ANCHOR }.map { AnchorMessage.from(it.payload) }
            .filterIsInstance<AnchorMessage.HeldState>().first()
        val rendered = mutableListOf<Boolean>()
        val indicator = AnchorHeldIndicator { rendered.add(it) }
        val states = mutableListOf<AnchorMessage.HeldState>()
        val session = ProjectionSession(temporary.newFolder(), Surface(), { _, _ -> }, { _, _ -> }, onAnchor = { source, message ->
            if (message is AnchorMessage.HeldState) {
                states.add(message)
                assertTrue(indicator.accept(source, message.intent, message.revision, message.state == AnchorHoldState.HELD))
            }
        })
        session.use { s ->
            indicator.bind(s, original.intent.context.usability)
            frames.forEach(s::receive)
            assertEquals(listOf(AnchorHoldState.HELD, AnchorHoldState.RELEASED), states.map { it.state })
            assertEquals(states[0].intent, states[1].intent)
            assertEquals(listOf(true, false), rendered)
            assertFalse(indicator.visible)
        }
    }

    @Test fun actualRetainedOwnerForwardsOriginalHeldCallbackAndDoesNotAdoptRetiredSource() {
        val parent = RetainedReceiverSessionTest()
        parent.temporary.create()
        try { parent.Fixture(withFeature = true).use { f ->
            val indicator = AnchorHeldIndicator {}
            val got = mutableListOf<Pair<ProjectionSession, AnchorMessage.HeldState>>()
            val view = object : ReceiverSessionOwner.View {
                override fun state(snapshot: ReceiverSessionOwner.Snapshot) = Unit
                override fun input(source: ProjectionSession, event: ReceiverInput) = Unit
                override fun anchorHeld(source: ProjectionSession, message: AnchorMessage.HeldState) {
                    got.add(source to message)
                    indicator.accept(source, message.intent, message.revision, message.state == AnchorHoldState.HELD)
                }
            }
            f.owner.attach(view, f.first); f.owner.presentation(view, true, false); f.streaming()
            val source = f.owner.session!!
            val fact = source.observationUsability()!!
            assertEquals(ReceiverUsabilityMode.PROJECTED, fact.mode)
            indicator.bind(source, fact)
            val intent = FunctionIntent(FunctionIntentKind.ACTIVATE, FunctionSource.PHONE_HID, 2, 3,
                FunctionIntentContext(fact, HeadPointerState(fact.inputRevision, 4, true, true, 10f, 20f,
                    Fit(0, 0, 480, 640), Geometry(480, 640))))
            source.receive(Frame(Wire.ANCHOR, payload = AnchorMessage.HeldState(5, intent, 1, AnchorHoldState.HELD).bytes()))
            f.pump(); assertTrue(indicator.visible); assertSame(source, got.single().first)
            source.receive(Frame(Wire.ANCHOR, payload = AnchorMessage.HeldState(5, intent, 2, AnchorHoldState.CANCELED).bytes()))
            f.pump(); assertFalse(indicator.visible)
            f.owner.detach(view); indicator.clear()
            source.receive(Frame(Wire.ANCHOR, payload = AnchorMessage.HeldState(5, intent, 3, AnchorHoldState.HELD).bytes()))
            f.pump(); assertEquals(2, got.size); assertFalse(indicator.visible)
        } } finally { parent.temporary.delete() }
    }

    @Test fun ownRegistrationOpenStillPrecedesItsUsabilityTransition() {
        val output = ByteArrayOutputStream()
        val revisions = AtomicLong()
        ProjectionSession(temporary.newFolder(), Surface(), { _, _ -> }, { _, _ -> },
            nextUsabilityRevision = revisions::incrementAndGet).use { s ->
            val size = Geometry(480, 640)
            field(s, "geometry").set(s, size); field(s, "output").set(s, DataOutputStream(output)); field(s, "controlReady").set(s, true)
            val readiness = field(s, "videoReadiness").get(s) as VideoReadiness
            val epoch = readiness.beginReplacement()!!
            check(readiness.awaitFrame(epoch, System.nanoTime()) && readiness.rendered(epoch))
            val fact = ReceiverUsability("session-owner", ReceiverIncarnation("glasses", 1), 1, 1, ReceiverUsabilityMode.PROJECTED)
            s.usability(fact); drain(s)
            val intent = FunctionIntent(FunctionIntentKind.REGISTER, FunctionSource.GLASSES_NATIVE, 2, 3,
                s.captureFunctionContext(HeadPointerState(1, 4, true, true, 10f, 20f, Fit(0, 0, 480, 640), size))!!)
            val offer = AnchorMessage.Offer(7, intent, AnchorHighlight(1.0, 2.0, 3.0, 4.0))
            assertTrue(s.openAnchorRegistration(offer, fact.copy(revision = 100, inputRevision = 2, mode = ReceiverUsabilityMode.LOCAL_PANEL)))
            drain(s)
            val frames = DataInputStream(output.toByteArray().inputStream()).use { input -> buildList { while (input.available() > 0) add(Wire.readFrame(input)) } }
            val index = frames.indexOfFirst { it.channel == Wire.ANCHOR }
            val open = AnchorMessage.from(frames[index].payload) as AnchorMessage.Open
            assertEquals(offer.intent, open.intent)
            assertEquals(open.chooser, ReceiverUsability.from(frames[index + 1].payload))
        }
    }
}
