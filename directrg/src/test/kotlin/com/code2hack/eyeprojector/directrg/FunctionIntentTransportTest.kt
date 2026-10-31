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

/** Actual grammar/HID producer -> actual ProjectionSession ordered writer -> actual wire bytes. */
class FunctionIntentTransportTest {
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
    private fun intention(s: ProjectionSession, n: Long = 0, p: HeadPointerState = point()) = CapturedFunctionIntent(
        FunctionIntent(FunctionIntentKind.ACTIVATE, FunctionSource.GLASSES_NATIVE, n, n, s.captureFunctionContext(p)!!), s)

    @Test fun actualNativeGrammarAndHidProducerSerializeEveryOriginalForThePhoneConsumer() {
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
            fun deliver(source: FunctionSource, g: FunctionGestureRecognizer.Gesture) {
                assertTrue(s.functionIntent(CapturedFunctionIntent(FunctionIntent(g.kind, source, g.gestureId, g.sequence,
                    g.original as FunctionIntentContext), s)))
            }
            val native = FunctionGestureRecognizer({ now }, { s }, schedule,
                { deliver(FunctionSource.GLASSES_NATIVE, it) }, capture = { s.captureFunctionContext(hover) })
            fun report(r: FunctionGestureRecognizer.Report, at: Long) { now = at; native.accept(r) }
            report(FunctionGestureRecognizer.Report.DOWN, 1000); hover = point(300f)
            report(FunctionGestureRecognizer.Report.UP, 1020)
            report(FunctionGestureRecognizer.Report.DOWN, 1100); report(FunctionGestureRecognizer.Report.UP, 1120)
            repeat(2) { report(FunctionGestureRecognizer.Report.DOUBLE, 1130); report(FunctionGestureRecognizer.Report.CLICK, 1140) }
            advance(1640)
            report(FunctionGestureRecognizer.Report.DOWN, 1700); advance(2500)
            report(FunctionGestureRecognizer.Report.LONG, 2501); report(FunctionGestureRecognizer.Report.UP, 2600)
            val binding = HidBinding(HidButton(HidDeviceIdentity("function-host-fixture", 1, 2), 30, 40), "Fixture", "Function")
            val bindings = HidBindings({ null }, { true }).also { assertTrue(it.assign(HidAction.FUNCTION, binding)) }
            val hid = HidKeyInput(bindings, { now }, { s }, schedule, { PointerPosition(300f, 240f, true) },
                { _, _ -> fail("Function must not own a contact") }, { _, _ -> fail("unexpected contact cancel") },
                { _, _, _ -> fail("unexpected scroll") }, { deliver(FunctionSource.GLASSES_HID, it) },
                functionCapture = { s.captureFunctionContext(hover) })
            fun edge(phase: HidKeyInput.Phase, down: Long, at: Long) {
                now = at; assertTrue(hid.accept(HidKeyInput.Event(7, binding, phase, down, at)))
            }
            edge(HidKeyInput.Phase.DOWN, 3000, 3000); edge(HidKeyInput.Phase.UP, 3000, 3020)
            edge(HidKeyInput.Phase.DOWN, 3100, 3100); edge(HidKeyInput.Phase.UP, 3100, 3120)
            edge(HidKeyInput.Phase.DOWN, 3200, 3200); advance(4000)
            edge(HidKeyInput.Phase.UP, 3200, 4100)
            await(s)
            val sent = frames(bytes).filter { it.channel == Wire.FUNCTION_INTENT }.map { FunctionIntent.from(it.payload) }
            assertEquals(listOf(FunctionIntentKind.ACTIVATE, FunctionIntentKind.ACTIVATE, FunctionIntentKind.REGISTER,
                FunctionIntentKind.ACTIVATE, FunctionIntentKind.ACTIVATE, FunctionIntentKind.REGISTER), sent.map { it.kind })
            assertEquals(List(3) { FunctionSource.GLASSES_NATIVE } + List(3) { FunctionSource.GLASSES_HID }, sent.map { it.source })
            assertEquals(120f, sent.first().context.pointer.x)
            assertTrue(sent.drop(1).all { it.context.pointer.x == 300f })
            for (source in listOf(FunctionSource.GLASSES_NATIVE, FunctionSource.GLASSES_HID)) {
                val originals = sent.filter { it.source == source }
                assertEquals(3, originals.map { it.gestureId }.distinct().size)
                assertTrue(originals.zipWithNext().all { (a, b) -> a.sequence < b.sequence })
            }
            System.getenv("EYEPROJECTOR_FUNCTION_PRODUCER_EVIDENCE")?.let { directory ->
                File(directory).apply { mkdirs() }.resolve("producer-wire.bin").writeBytes(bytes.toByteArray())
            }
        }
    }

    @Test fun outputLockBackpressureRetainsBothCapturedActivationsAndOriginalHover() {
        val bytes = ByteArrayOutputStream()
        ready(bytes).use { s ->
            val original = intention(s)
            synchronized(field(s, "outputLock").get(s)) {
                assertTrue(s.functionIntent(original))
                assertTrue(s.functionIntent(CapturedFunctionIntent(original.intent.copy(gestureId = 1, sequence = 1), s)))
                s.headPointer(point(350f).copy(sequence = 2), Long.MAX_VALUE)
            }
            await(s)
            val sent = frames(bytes).filter { it.channel == Wire.FUNCTION_INTENT }.map { FunctionIntent.from(it.payload) }
            assertEquals(listOf(0L, 1L), sent.map { it.sequence })
            assertTrue(sent.all { it.context.pointer.x == 120f })
        }
    }

    @Test fun originalContextIsRecheckedAfterOutputLockWaitAndNeverAdoptedByASuccessor() {
        for (replacement in listOf("mode", "revision", "incarnation", "geometry", "cancel")) {
            val bytes = ByteArrayOutputStream()
            ready(bytes).use { s ->
                val original = intention(s)
                synchronized(field(s, "outputLock").get(s)) {
                    assertTrue(s.functionIntent(original))
                    when (replacement) {
                        "mode" -> s.usability(fact.copy(revision = 2, mode = ReceiverUsabilityMode.NONE))
                        "revision" -> s.usability(fact.copy(revision = 2, inputRevision = 2))
                        "incarnation" -> s.usability(fact.copy(revision = 2, incarnation = ReceiverIncarnation("successor", 2)))
                        "geometry" -> field(s, "geometry").set(s, Geometry(640, 480))
                        "cancel" -> { s.cancelPendingInput(); s.usability(fact.copy(revision = 2, inputRevision = 2)) }
                    }
                }
                await(s)
                assertTrue(replacement, frames(bytes).none { it.channel == Wire.FUNCTION_INTENT })
                ready(ByteArrayOutputStream()).use { successor -> assertFalse(successor.functionIntent(original)) }
            }
        }
    }

    @Test fun incomingTypedIntentionBindsThisActualSessionBeforeDeferredConsumerDelivery() {
        val bytes = ByteArrayOutputStream()
        ready(bytes).use { s ->
            val got = mutableListOf<CapturedFunctionIntent<ProjectionSession>>()
            s.functionIntents.activate = got::add
            val incoming = intention(s).intent.copy(source = FunctionSource.PHONE_HID)
            s.receive(Frame(Wire.FUNCTION_INTENT, payload = incoming.bytes())); await(s)
            assertEquals(incoming, got.single().intent); assertSame(s, got.single().original)
        }
    }
}
