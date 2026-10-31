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

class AnchorTransportTest {
    @get:Rule val temporary = TemporaryFolder()
    private val geometry = Geometry(480, 640)
    private val fact = ReceiverUsability("session-owner", ReceiverIncarnation("function-receiver", 1), 1, 1, ReceiverUsabilityMode.PROJECTED)
    private fun field(s: ProjectionSession, name: String) = s.javaClass.getDeclaredField(name).apply { isAccessible = true }
    private fun drain(s: ProjectionSession) {
        for (name in listOf("controls", "scaleControls")) {
            val lane = field(s, name).get(s) as OrderedControlDispatcher
            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (lane.snapshot().let { it.pending > 0 || it.inFlight > 0 } && System.nanoTime() < until) Thread.yield()
            assertEquals(0L, lane.snapshot().pending); assertEquals(0L, lane.snapshot().inFlight)
        }
    }
    private fun ready(output: ByteArrayOutputStream, received: MutableList<AnchorMessage>): ProjectionSession {
        val revisions = AtomicLong()
        return ProjectionSession(temporary.newFolder(), Surface(), { _, _ -> }, { _, _ -> },
            nextUsabilityRevision = revisions::incrementAndGet, onAnchor = { source, message ->
                assertNotNull(source); received.add(message)
            }).also { s ->
            field(s, "geometry").set(s, geometry); field(s, "output").set(s, DataOutputStream(output)); field(s, "controlReady").set(s, true)
            val readiness = field(s, "videoReadiness").get(s) as VideoReadiness
            val epoch = readiness.beginReplacement()!!; check(readiness.awaitFrame(epoch, System.nanoTime()) && readiness.rendered(epoch))
            s.usability(fact); drain(s)
        }
    }
    private fun offer(s: ProjectionSession) = AnchorMessage.Offer(5, FunctionIntent(FunctionIntentKind.REGISTER,
        FunctionSource.GLASSES_NATIVE, 1, 1, s.captureFunctionContext(HeadPointerState(1, 1, true, true,
            120f, 230f, Fit(0, 0, 480, 640), geometry))!!), AnchorHighlight(100.0, 200.0, 140.0, 240.0))
    private fun frames(output: ByteArrayOutputStream): List<Frame> = DataInputStream(output.toByteArray().inputStream()).use {
        buildList { while (it.available() > 0) add(Wire.readFrame(it)) }
    }
    @Test fun openBindsExactChooserFactBeforeItsUsabilityPublication() {
        val output = ByteArrayOutputStream()
        ready(output, mutableListOf()).use { s ->
            val original = offer(s)
            assertTrue(s.openAnchorRegistration(original, fact.copy(revision = 100, inputRevision = 2, mode = ReceiverUsabilityMode.LOCAL_PANEL)))
            drain(s)
            val sent = frames(output)
            val index = sent.indexOfFirst { it.channel == Wire.ANCHOR }
            assertTrue(index >= 0)
            val open = AnchorMessage.from(sent[index].payload) as AnchorMessage.Open
            assertEquals(original.intent, open.intent)
            assertEquals(2, open.chooser.inputRevision)
            assertEquals(Wire.RECEIVER_USABILITY, sent[index + 1].channel)
            assertEquals(open.chooser, ReceiverUsability.from(sent[index + 1].payload))
            assertTrue(s.chooseAnchorRegistration(original, AnchorChoice.CLICK)); drain(s)
            val choice = AnchorMessage.from(frames(output).last().payload) as AnchorMessage.Choice
            assertEquals(5, choice.transactionId); assertEquals(original.intent, choice.intent)
        }
    }
    @Test fun oldOfferCannotBindAfterGenuineInputContextReplacement() {
        val output = ByteArrayOutputStream()
        ready(output, mutableListOf()).use { s ->
            val original = offer(s)
            s.usability(fact.copy(revision = 100, inputRevision = 3, mode = ReceiverUsabilityMode.NONE)); drain(s)
            assertFalse(s.openAnchorRegistration(original, fact.copy(revision = 101, inputRevision = 4, mode = ReceiverUsabilityMode.LOCAL_PANEL)))
            drain(s); assertTrue(frames(output).none { it.channel == Wire.ANCHOR })
        }
    }
    @Test fun actualPhoneFeedbackArtifactReachesOriginalGlassesSessionListener() {
        val directory = System.getenv("EYEPROJECTOR_ANCHOR_EVIDENCE")
        org.junit.Assume.assumeNotNull(directory) // Dedicated cross-process harness supplies exact artifact.
        val artifact = File(directory, "phone-feedback.bin")
        check(artifact.isFile) { "Run actual Phone Anchor Session composition first" }
        val got = mutableListOf<AnchorMessage>()
        ready(ByteArrayOutputStream(), got).use { s ->
            DataInputStream(artifact.inputStream()).use { input -> while (input.available() > 0) s.receive(Wire.readFrame(input)) }
            assertEquals(listOf(AnchorFeedback.CLICK_SUBMITTED, AnchorFeedback.CLICK_SUBMITTED),
                got.filterIsInstance<AnchorMessage.Feedback>().map { it.status })
            assertTrue(got.all { it.intent.context.usability.ownerEpoch == fact.ownerEpoch })
        }
    }
}
