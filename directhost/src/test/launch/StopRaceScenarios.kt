package com.code2hack.eyeprojector.directhost

import com.code2hack.eyeprojector.protocol.*
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

private fun awaitStopRace(label: String, ready: () -> Boolean) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
    while (!ready() && System.nanoTime() < deadline) Thread.yield()
    check(ready()) { "Timed out waiting for $label" }
}

private fun Thread.waitingOnMerger() = state == Thread.State.BLOCKED &&
    stackTrace.any { it.className.endsWith(".input.PhoneInputMerger") && it.methodName == "transaction" }

/** Actual Session precheck -> actual merger monitor -> Stop, with teardown held behind resources. */
fun stopRaceScenarios() {
    val failures = mutableListOf<String>()
    for (kind in listOf("TAP DOWN", "SCROLL UP", "Function BACK")) {
        Fixture().use { old -> Fixture().use { fresh ->
            old.fusionGeometry(); old.pointer()
            fresh.fusionGeometry(); fresh.pointer()
            val at = android.os.SystemClock.uptimeMillis()
            val action = if (kind == "SCROLL UP") PhoneHidAction.SCROLL_DOWN else PhoneHidAction.TAP
            val edge = PhoneHidEdge(action, PhoneHidPhase.DOWN, 1, 66, 88, at, at)
            if (kind == "SCROLL UP") old.hid(20, edge)
            val stopFailure = AtomicReference<Throwable?>()
            val inputFailure = AtomicReference<Throwable?>()
            val stop = Thread {
                try { old.closeSession() } catch (error: Throwable) { stopFailure.set(error) }
            }
            var input: Thread? = null
            var lateBytes = -1
            try {
                synchronized(old.get("resources")!!) {
                    synchronized(old.get("controlLock")!!) {
                        if (kind == "Function BACK") {
                            // Real Session timer and FunctionWork must pass their precheck, then wait here.
                            repeat(1) { n ->
                                val down = PhoneHidEdge(PhoneHidAction.FUNCTION, PhoneHidPhase.DOWN,
                                    1, 67, 90, at + n * 30, at + n * 30)
                                old.hid(n.toLong(), down)
                                old.hid(n.toLong(), down.copy(phase = PhoneHidPhase.UP,
                                    eventTimeMs = down.eventTimeMs + 1))
                            }
                            awaitStopRace("actual Function worker at merger monitor") {
                                input = Thread.getAllStackTraces().keys.singleOrNull {
                                    it.name == "eyeprojector-phone-function" && it.waitingOnMerger()
                                }
                                input != null
                            }
                        } else {
                            input = Thread {
                                try {
                                    old.hid(20, if (kind == "SCROLL UP") edge.copy(
                                        phase = PhoneHidPhase.UP, eventTimeMs = at + 20) else edge)
                                } catch (error: Throwable) { inputFailure.set(error) }
                            }.also { it.start() }
                            awaitStopRace("already-prechecked $kind at merger monitor") { input!!.waitingOnMerger() }
                        }
                        stop.start()
                        awaitStopRace("actual Stop invalidation") { !(old.get("running") as AtomicBoolean).get() }
                    }
                    // Stop cannot close inputControls or sockets yet: only the control monitor was released.
                    input!!.join(2000)
                    check(!input!!.isAlive && inputFailure.get() == null)
                    lateBytes = (old.control.sink as ByteArrayOutputStream).size()
                    check(stop.isAlive && !old.control.released)
                    val freshDown = edge.copy(action = PhoneHidAction.TAP, downTimeMs = at + 100, eventTimeMs = at + 100)
                    fresh.hid(21, freshDown)
                    fresh.hid(21, freshDown.copy(phase = PhoneHidPhase.UP, eventTimeMs = at + 120))
                    check((fresh.control.sink as ByteArrayOutputStream).size() == 64)
                    check(!fresh.peer.released && !fresh.control.released && fresh.child.alive)
                }
            } finally {
                stop.join(3000)
                input?.join(2000)
            }
            check(!stop.isAlive && stopFailure.get() == null)
            check(old.peer.released && old.video.released && old.control.released && !old.child.alive)
            check(!fresh.peer.released && !fresh.control.released && fresh.child.alive)
            if (lateBytes != 0) failures.add("$kind wrote $lateBytes stock bytes after Stop")
            println("${if (lateBytes == 0) "PASS" else "FAIL"} actual Session queued $kind: lateBytes=$lateBytes, old cleanup/fresh Session preserved")
        } }
    }
    Fixture().use { old ->
        old.fusionGeometry(); old.pointer()
        val size = Geometry(480, 640)
        old.receive(Frame(Wire.TOUCH, payload = Touch(Touch.Phase.DOWN, 1, Tap(5, 6, size)).bytes()))
        val written = ByteArrayOutputStream()
        var stopped = false
        old.control.sink = object : OutputStream() {
            override fun write(value: Int) { written.write(value) }
            override fun write(data: ByteArray, offset: Int, length: Int) {
                written.write(data, offset, length)
                if (!stopped && length > 1 && data[offset + 1].toInt() == Touch.Phase.CANCEL.action) {
                    stopped = true
                    old.closeSession() // Stop during actual stock cancellation, before the replacement DOWN.
                }
            }
        }
        val at = android.os.SystemClock.uptimeMillis()
        old.hid(30, PhoneHidEdge(PhoneHidAction.TAP, PhoneHidPhase.DOWN, 2, 66, 99, at, at))
        check(stopped && old.peer.released && old.control.released && !old.child.alive)
        val late = written.toByteArray().toList().chunked(32).drop(1).size
        if (late != 0) failures.add("cancellation callback Stop admitted $late new stock frame(s)")
        println("${if (late == 0) "PASS" else "FAIL"} actual stock cancellation callback Stop: newFrames=$late, cleanup preserved")
    }
    check(failures.isEmpty()) { failures.joinToString("; ") }
    println("4 actual Session Stop interleavings passed; modeled external APIs, no device proof")
}

fun main() = stopRaceScenarios()
