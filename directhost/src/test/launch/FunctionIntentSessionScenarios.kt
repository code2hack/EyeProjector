package com.code2hack.eyeprojector.directhost

import com.code2hack.eyeprojector.directhost.input.FunctionIntentRoute
import com.code2hack.eyeprojector.directhost.input.FunctionWork
import com.code2hack.eyeprojector.protocol.*
import java.io.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

private fun Fixture.drainFunctions() {
    val done = CountDownLatch(1)
    (get("functionWork") as FunctionWork).submit { done.countDown() }
    check(done.await(3, TimeUnit.SECONDS))
}
private fun Fixture.consumers() = (session as FunctionIntentRoute).functionIntents
private fun Fixture.context(): FunctionIntentContext {
    val fact = (session as CapturedProjection).receiverUsability().fact!!
    return FunctionIntentContext(fact, HeadPointerState(fact.inputRevision, 1, true,
        fact.mode == ReceiverUsabilityMode.PROJECTED, 120f, 230f, Fit(20, 30, 480, 640), Geometry(480, 640)))
}
private fun event(context: FunctionIntentContext, sequence: Long) = FunctionIntent(
    FunctionIntentKind.ACTIVATE, FunctionSource.GLASSES_NATIVE, sequence, sequence, context)

fun functionIntentSessionScenarios() {
    val artifact = File(System.getenv("EYEPROJECTOR_FUNCTION_PRODUCER_EVIDENCE"), "producer-wire.bin")
    check(artifact.isFile) { "Run the actual Glasses producer transport test first: $artifact" }
    val originalFrames = DataInputStream(artifact.inputStream()).use { input ->
        buildList { while (input.available() > 0) add(Wire.readFrame(input)) }
    }
    val expected = originalFrames.filter { it.channel == Wire.FUNCTION_INTENT }.map { FunctionIntent.from(it.payload) }
    check(expected.size == 6)
    Fixture().use { f ->
        f.fusionGeometry()
        val received = CopyOnWriteArrayList<CapturedFunctionIntent<CapturedProjection>>()
        f.consumers().activate = { received.add(it) }; f.consumers().register = { received.add(it) }
        originalFrames.forEach(f::receive); f.drainFunctions()
        check(received.map { it.intent } == expected)
        check(received.all { it.original === f.session })
        check((f.control.sink as ByteArrayOutputStream).size() == 0 && f.peer.sink.size() == 0)
        check(received.first().intent.context.pointer.x == 120f && received[1].intent.context.pointer.x == 300f)
        // Actual Phone Session HID producer joins this same FIFO and typed consumer hook.
        f.pointer()
        val at = android.os.SystemClock.uptimeMillis()
        repeat(3) { n ->
            val down = PhoneHidEdge(PhoneHidAction.FUNCTION, PhoneHidPhase.DOWN, 1, 67, 90, at + n * 100, at + n * 100)
            f.hid(n.toLong(), down)
            f.hid(n.toLong(), down.copy(phase = PhoneHidPhase.UP, eventTimeMs = down.eventTimeMs + if (n == 2) 800 else 20))
        }
        f.drainFunctions()
        val phone = received.drop(6)
        check(phone.map { it.intent.kind } == listOf(FunctionIntentKind.ACTIVATE, FunctionIntentKind.ACTIVATE, FunctionIntentKind.REGISTER))
        check(phone.all { it.original === f.session && it.intent.source == FunctionSource.PHONE_HID })
        check(phone.map { it.intent.gestureId }.distinct().size == 3)
        check(phone.zipWithNext().all { (a, b) -> a.intent.sequence < b.intent.sequence })
        check((f.control.sink as ByteArrayOutputStream).size() == 0 && f.peer.sink.size() == 0)
        println("PASS actual native/HID grammar -> Glasses Session -> wire9 artifact -> actual Phone Session original typed hooks, plus actual Phone HID pair/long; no navigation/contact output")
    }
    for (replacement in listOf("mode", "revision", "incarnation", "geometry", "closed")) {
        Fixture().use { old -> Fixture().use { successor ->
            old.fusionGeometry(); old.pointer(); successor.fusionGeometry(); successor.pointer()
            val entered = CountDownLatch(1); val release = CountDownLatch(1)
            val got = CopyOnWriteArrayList<CapturedFunctionIntent<CapturedProjection>>()
            val fresh = CopyOnWriteArrayList<CapturedFunctionIntent<CapturedProjection>>()
            old.consumers().activate = { captured ->
                got.add(captured)
                if (captured.intent.sequence == 0L) { entered.countDown(); while (release.count > 0) try { release.await() } catch (_: InterruptedException) {} }
            }
            successor.consumers().activate = { fresh.add(it) }
            val original = old.context()
            try {
                old.receive(Frame(Wire.FUNCTION_INTENT, payload = event(original, 0).bytes()))
                check(entered.await(2, TimeUnit.SECONDS))
                old.receive(Frame(Wire.FUNCTION_INTENT, payload = event(original, 1).bytes()))
                when (replacement) {
                    "mode" -> old.receive(Frame(Wire.RECEIVER_USABILITY, payload = original.usability.copy(revision = 2, mode = ReceiverUsabilityMode.NONE).bytes()))
                    "revision" -> old.pointer(revision = 2, sequence = 2)
                    "incarnation" -> old.receive(Frame(Wire.RECEIVER_USABILITY, payload = original.usability.copy(revision = 2, incarnation = ReceiverIncarnation("successor", 2)).bytes()))
                    "geometry" -> {
                        @Suppress("UNCHECKED_CAST")
                        (old.get("geometry") as AtomicReference<Geometry?>).set(Geometry(640, 480))
                    }
                    "closed" -> old.closeSession()
                }
                release.countDown()
                if (replacement != "closed") old.drainFunctions()
                check(got.size == 1 && got.single().original === old.session)
                check(fresh.isEmpty())
                println("PASS actual Session FIFO pending activation retired by $replacement; original capture never becomes successor")
            } finally { release.countDown() }
        } }
    }
    Fixture().use { f ->
        f.fusionGeometry(); f.pointer()
        val a = f.adapter(Adapter()); a.initialize(47); a.snapshot(true)
        f.receive(Frame(Wire.NAVIGATE, payload = Navigate(Navigation.FUNCTION_DOUBLE, Geometry(480, 640)).bytes()))
        a.snapshot(true)
        check(f.peer.sink.size() == 0 && (f.control.sink as ByteArrayOutputStream).size() == 0)
        println("PASS reserved Function-double wire identifier cannot dispatch old contextual Home")
    }
    println("PASS 7 Function production composition scenarios; host APIs/artifact transport only, no firmware/device proof")
}
fun main() = functionIntentSessionScenarios()
