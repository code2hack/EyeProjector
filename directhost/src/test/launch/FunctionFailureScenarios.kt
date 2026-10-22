package com.code2hack.eyeprojector.directhost

import com.code2hack.eyeprojector.protocol.*
import java.io.DataOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Function callback itself owns the first actual Session close; no external close triggers it. */
fun functionFailureScenarios() {
    val failures = mutableListOf<String>()
    for (stockBack in listOf(false, true)) {
        val events = CopyOnWriteArrayList<String>()
        val child = object : Child() {
            override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
                events.add("child-wait")
                if (Thread.interrupted()) {
                    events.add("child-interrupted")
                    throw InterruptedException("modeled interruptible owned child wait")
                }
                return super.waitFor(timeout, unit)
            }
            override fun destroy() { events.add("child-destroy"); super.destroy() }
        }
        Fixture(child = child).use { f ->
            f.fusionGeometry(); f.pointer(projected = stockBack)
            (f.get("frames") as AtomicLong).set(1)
            val failed = CountDownLatch(1)
            val restoreEntered = CountDownLatch(1)
            val allowRestore = CountDownLatch(1)
            val caller = AtomicReference<Thread>()
            val back = Scrcpy.navigate(Navigate(Navigation.BACK, Geometry(480, 640)))
            f.control.sink = object : OutputStream() {
                override fun write(value: Int) = error("Expected whole stock messages")
                override fun write(data: ByteArray) {
                    if (data.contentEquals(back) && stockBack) {
                        caller.set(Thread.currentThread()); failed.countDown()
                        throw IOException("modeled Function stock Back failure")
                    }
                    if (data.contentEquals(byteArrayOf(10, 1))) {
                        events.add("restore-enter"); restoreEntered.countDown()
                        check(allowRestore.await(4, TimeUnit.SECONDS))
                        if (f.control.released) throw IOException("restore missed its pre-transport opportunity")
                        events.add("restore-submitted")
                    }
                }
                override fun close() { events.add("control-close") }
            }
            if (!stockBack) f.set("output", DataOutputStream(object : OutputStream() {
                override fun write(value: Int) {
                    caller.set(Thread.currentThread()); failed.countDown()
                    throw IOException("modeled Function completed UI TLS failure")
                }
            }))
            val off = f.type.getDeclaredMethod("requestPanelOff", Long::class.javaPrimitiveType)
                .apply { isAccessible = true }.invoke(f.session, 81L) as PanelPowerState
            check(off.off == PanelSubmission.SUBMITTED)
            val at = android.os.SystemClock.uptimeMillis()
            repeat(if (stockBack) 2 else 1) { n ->
                val down = PhoneHidEdge(PhoneHidAction.FUNCTION, PhoneHidPhase.DOWN,
                    1, 67, 90, at + n * 30, at + n * 30)
                f.hid(n.toLong(), down)
                f.hid(n.toLong(), down.copy(phase = PhoneHidPhase.UP, eventTimeMs = down.eventTimeMs + 1))
            }
            var joinedRelease = false
            try {
                check(failed.await(2, TimeUnit.SECONDS) && restoreEntered.await(2, TimeUnit.SECONDS))
                val worker = caller.get()
                check(worker.name == "eyeprojector-phone-function")
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
                while (System.nanoTime() < deadline) {
                    joinedRelease = worker.stackTrace.any { it.className.endsWith(".HeldInputRelease") && it.methodName == "beforeTransportClose" } &&
                        worker.state == Thread.State.TIMED_WAITING && !worker.isInterrupted
                    if (joinedRelease || f.control.released) break
                    Thread.yield()
                }
                // Still blocked in the original join: panel restoration owns its pre-transport turn.
                val premature = f.control.released || events.contains("child-wait")
                allowRestore.countDown()
                worker.join(2500)
                check(!worker.isAlive)
                val ordered = events.indexOf("restore-submitted") in 0 until events.indexOf("control-close")
                val cleaned = f.peer.released && f.video.released && f.control.released && !child.alive
                val passed = joinedRelease && !premature && ordered && cleaned && !events.contains("child-interrupted")
                if (!passed) failures.add("${if (stockBack) "Back" else "TLS"}: join=$joinedRelease, premature=$premature, events=$events, childAlive=${child.alive}")
                println("${if (passed) "PASS" else "FAIL"} actual Session Function ${if (stockBack) "Back" else "TLS"} failure self-close: join=$joinedRelease, ordered=$ordered, cleaned=$cleaned, events=$events")
            } finally { allowRestore.countDown() }
        }
    }
    check(failures.isEmpty()) { failures.joinToString("; ") }
    println("2 actual Session Function error self-close scenarios passed; modeled external APIs, no device proof")
}

fun main() = functionFailureScenarios()
