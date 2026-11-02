package com.code2hack.eyeprojector.directrg

import android.view.Surface
import com.code2hack.eyeprojector.protocol.*
import java.io.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Actual native grammar producer -> actual ProjectionSession ordered writer -> actual wire bytes. */
class AnchorIntentTransportTest {
    @get:Rule val temporary = TemporaryFolder()
    private val size = Geometry(480, 640)
    private val fact = ReceiverUsability("session-owner", ReceiverIncarnation("function-receiver", 1), 1, 1, ReceiverUsabilityMode.PROJECTED)
    private fun point(x: Float = 120f) = HeadPointerState(1, 1, true, true, x, 240f, Fit(0, 0, 480, 640), size)
    private fun field(s: ProjectionSession, n: String) = s.javaClass.getDeclaredField(n).apply { isAccessible = true }
    private fun ready(bytes: ByteArrayOutputStream): ProjectionSession {
        val revisions = AtomicLong()
        return ProjectionSession(temporary.newFolder(), Surface(), { _, _ -> }, { _, _ -> }, nextUsabilityRevision = revisions::incrementAndGet).also { s ->
            field(s, "geometry").set(s, size); field(s, "output").set(s, DataOutputStream(bytes)); field(s, "controlReady").set(s, true)
            val readiness = field(s, "videoReadiness").get(s) as VideoReadiness
            val epoch = readiness.beginReplacement()!!
            check(readiness.awaitFrame(epoch, System.nanoTime()) && readiness.rendered(epoch))
            s.usability(fact); await(s)
        }
    }
    private fun await(s: ProjectionSession) {
        for (name in listOf("controls", "scaleControls")) {
            val lane = field(s, name).get(s) as OrderedControlDispatcher
            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (lane.snapshot().let { it.pending > 0 || it.inFlight > 0 } && System.nanoTime() < until) Thread.yield()
            assertEquals(0L, lane.snapshot().pending); assertEquals(0L, lane.snapshot().inFlight)
        }
    }
    private fun frames(bytes: ByteArrayOutputStream): List<Frame> = DataInputStream(bytes.toByteArray().inputStream()).use { input ->
        buildList { while (input.available() > 0) add(Wire.readFrame(input)) }
    }
    private fun intention(s: ProjectionSession, n: Long = 0, p: HeadPointerState = point()) = CapturedAnchorIntent(
        AnchorIntent(AnchorIntentKind.ACTIVATE, AnchorSource.GLASSES_NATIVE, n, n, s.captureAnchorContext(p)!!), s)

    @Test fun actualNativeGrammarSerializesEveryOriginalForThePhoneConsumer() {
        val bytes = ByteArrayOutputStream()
        ready(bytes).use { s ->
            var now = 1000L; var hover = point()
            data class Timer(val at: Long, val run: () -> Unit, var canceled: Boolean = false)
            val timers = mutableListOf<Timer>()
            val schedule: (Long, () -> Unit) -> (() -> Unit) = { at, action ->
                val timer = Timer(at, action); timers.add(timer); { timer.canceled = true }
            }
            fun advance(to: Long) {
                now = to
                while (true) {
                    val timer = timers.firstOrNull { !it.canceled && it.at <= to } ?: break
                    timer.canceled = true; timer.run()
                }
            }
            fun deliver(source: AnchorSource, g: FunctionGestureRecognizer.Gesture) {
                assertTrue(s.anchorIntent(CapturedAnchorIntent(AnchorIntent(g.kind, source, g.gestureId, g.sequence,
                    g.original as AnchorIntentContext), s)))
            }
            val native = FunctionGestureRecognizer({ now }, { s }, schedule,
                { deliver(AnchorSource.GLASSES_NATIVE, it) }, captureOriginal = { _, id, parent ->
                    s.captureAnchorContext(hover)?.takeIf {
                        s.beginAnchor(AnchorOriginal(AnchorSource.GLASSES_NATIVE, id, it, parent))
                    }
                })
            fun report(r: FunctionGestureRecognizer.Report, at: Long) { now = at; native.accept(r) }
            report(FunctionGestureRecognizer.Report.DOWN, 1000); hover = point(300f)
            report(FunctionGestureRecognizer.Report.UP, 1020)
            report(FunctionGestureRecognizer.Report.DOWN, 1100); report(FunctionGestureRecognizer.Report.UP, 1120)
            repeat(2) { report(FunctionGestureRecognizer.Report.DOUBLE, 1130); report(FunctionGestureRecognizer.Report.CLICK, 1140) }
            advance(1640)
            report(FunctionGestureRecognizer.Report.DOWN, 1700); advance(2500)
            report(FunctionGestureRecognizer.Report.LONG, 2501); report(FunctionGestureRecognizer.Report.UP, 2600)
            await(s)
            val sent = frames(bytes).filter { it.channel == Wire.ANCHOR_INTENT }.map { AnchorIntent.from(it.payload) }
            assertEquals(listOf(AnchorIntentKind.ACTIVATE, AnchorIntentKind.ACTIVATE, AnchorIntentKind.REGISTER), sent.map { it.kind })
            assertEquals(List(3) { AnchorSource.GLASSES_NATIVE }, sent.map { it.source })
            assertEquals(120f, sent.first().context.pointer.x)
            assertTrue(sent.drop(1).all { it.context.pointer.x == 300f })
            for (source in listOf(AnchorSource.GLASSES_NATIVE)) {
                val originals = sent.filter { it.source == source }
                assertEquals(3, originals.map { it.gestureId }.distinct().size)
                assertTrue(originals.zipWithNext().all { (a, b) -> a.sequence < b.sequence })
            }
            System.getenv("EYEPROJECTOR_ANCHOR_PRODUCER_EVIDENCE")?.let { directory ->
                File(directory).apply { mkdirs() }.resolve("producer-wire.bin").writeBytes(bytes.toByteArray())
            }
        }
    }

    @Test fun outputLockBackpressureRetainsBothCapturedActivationsAndOriginalHover() {
        val bytes = ByteArrayOutputStream()
        ready(bytes).use { s ->
            val original = intention(s)
            synchronized(field(s, "outputLock").get(s)) {
                assertTrue(s.beginAnchor(AnchorOriginal(original.intent.source, 0, original.intent.context)))
                assertTrue(s.anchorIntent(original))
                assertTrue(s.beginAnchor(AnchorOriginal(original.intent.source, 1, original.intent.context)))
                assertTrue(s.anchorIntent(CapturedAnchorIntent(original.intent.copy(gestureId = 1, sequence = 1), s)))
                s.headPointer(point(350f).copy(sequence = 2), Long.MAX_VALUE)
            }
            await(s)
            val sent = frames(bytes).filter { it.channel == Wire.ANCHOR_INTENT }.map { AnchorIntent.from(it.payload) }
            assertEquals(listOf(0L, 1L), sent.map { it.sequence })
            assertTrue(sent.all { it.context.pointer.x == 120f })
        }
    }

    @Test fun capturedOriginalCrossesGeometryAndPresentationChangesWhileWriterIsBlocked() {
        for (change in listOf("mode", "revision", "geometry", "presentation")) {
            val bytes = ByteArrayOutputStream()
            ready(bytes).use { s ->
                val original = intention(s)
                synchronized(field(s, "outputLock").get(s)) {
                    assertTrue(s.beginAnchor(AnchorOriginal(original.intent.source, 0, original.intent.context)))
                    assertTrue(s.anchorIntent(original))
                    when (change) {
                        "mode" -> s.usability(fact.copy(revision = 2, mode = ReceiverUsabilityMode.NONE))
                        "revision" -> s.usability(fact.copy(revision = 2, inputRevision = 2))
                        "geometry" -> s.receive(Frame(Wire.GEOMETRY, payload = Geometry(640, 480).bytes()))
                        "presentation" -> s.presentation(false)
                    }
                }
                await(s)
                val sent = frames(bytes)
                assertEquals(change, listOf(original.intent), sent.filter { it.channel == Wire.ANCHOR_INTENT }.map { AnchorIntent.from(it.payload) })
                val birth = sent.filter { it.channel == Wire.ANCHOR_ORIGINAL }.map { AnchorOriginal.from(it.payload) }
                assertEquals(change, listOf(AnchorOriginal(original.intent.source, 0, original.intent.context)), birth)
                assertTrue(sent.indexOfFirst { it.channel == Wire.ANCHOR_ORIGINAL } < sent.indexOfFirst { it.channel == Wire.ANCHOR_INTENT })
            }
        }
    }

    @Test fun explicitCancellationOrActualSourceReplacementCannotWriteOrAdoptOriginal() {
        for (change in listOf("incarnation", "cancel")) {
            val bytes = ByteArrayOutputStream()
            ready(bytes).use { s ->
                val original = intention(s)
                synchronized(field(s, "outputLock").get(s)) {
                    assertTrue(s.beginAnchor(AnchorOriginal(original.intent.source, 0, original.intent.context)))
                    assertTrue(s.anchorIntent(original))
                    if (change == "incarnation") s.usability(fact.copy(revision = 2, incarnation = ReceiverIncarnation("successor", 2)))
                    else s.cancelPendingInput()
                }
                await(s)
                assertTrue(change, frames(bytes).none { it.channel == Wire.ANCHOR_INTENT })
                ready(ByteArrayOutputStream()).use { successor -> assertFalse(successor.anchorIntent(original)) }
            }
        }
    }

    @Test fun incomingTypedIntentionBindsThisActualSessionBeforeDeferredConsumerDelivery() {
        val bytes = ByteArrayOutputStream()
        ready(bytes).use { s ->
            val got = mutableListOf<CapturedAnchorIntent<ProjectionSession>>()
            s.anchorIntents.activate = got::add
            val incoming = intention(s).intent.copy(source = AnchorSource.PHONE_HID)
            s.receive(Frame(Wire.ANCHOR_INTENT, payload = incoming.bytes())); await(s)
            assertEquals(incoming, got.single().intent); assertSame(s, got.single().original)
        }
    }
}
