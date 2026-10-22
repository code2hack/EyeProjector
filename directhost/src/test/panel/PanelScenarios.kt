package com.code2hack.eyeprojector.directhost

import android.net.LocalSocket
import com.code2hack.eyeprojector.protocol.*
import java.io.*
import java.lang.reflect.Proxy
import java.net.InetAddress
import java.nio.file.Files
import java.util.concurrent.*
import java.util.concurrent.atomic.*
import javax.net.ssl.*
import kotlin.concurrent.thread

private fun eventually(predicate: () -> Boolean) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
    while (!predicate() && System.nanoTime() < deadline) Thread.sleep(5)
    check(predicate())
}

fun main() {
    var passed = 0
    for (reason in listOf(SessionStopReason.HOST_STOP, SessionStopReason.CONTROL_END, SessionStopReason.VIDEO_IO)) {
        PanelFixture().use { f ->
            check(f.off().off == PanelSubmission.SUBMITTED)
            f.held()
            f.close(reason)
            check(f.events.indexOf("cancel-attempt") < f.events.indexOf("restore-attempt"))
            check(f.events.indexOf("restore-attempt") < f.events.indexOf("control-close"))
            check(f.events.indexOf("control-close") < f.events.indexOf("child-wait"))
            check(!f.child.isAlive && !f.state().sessionActive)
            check(f.state().restoration == PanelRestoration.SUBMITTED)
            check(f.state().observation == PanelObservation.UNVERIFIED)
            check(f.receipt().contains("\"stopReason\":\"${reason.name}\""))
            check(f.receipt().contains("\"restorationSubmission\":\"SUBMITTED\""))
            f.close(reason)
            check(f.events.count { it == "restore-attempt" } == 1)
        }
        println("PASS actual Session close $reason cancels/restores before transport, reaps child; observation UNVERIFIED"); passed++
    }
    PanelFixture().use { f ->
        f.close(SessionStopReason.HOST_STOP)
        check(f.events.none { it == "restore-attempt" })
        check(f.state().restoration == PanelRestoration.NOT_NEEDED)
        check(!f.child.isAlive)
    }
    println("PASS never-blanked actual Session does not send ON"); passed++
    PanelFixture().use { f ->
        f.off(); f.held(); f.failCancel = true; f.failRestore = true
        f.close(SessionStopReason.CONTROL_IO)
        check(f.events.containsAll(listOf("cancel-attempt", "restore-attempt", "control-close", "child-wait")))
        check(f.state().restoration == PanelRestoration.FAILED && !f.child.isAlive)
        check(f.receipt().contains("\"restorationSubmission\":\"FAILED\""))
        check(f.receipt().contains("\"physicalObservation\":\"UNVERIFIED\""))
    }
    println("PASS cancel and restore failures cannot skip actual Session socket/child cleanup"); passed++
    PanelFixture().use { f ->
        f.failOff = true
        val response = f.off()
        check(response.off == PanelSubmission.FAILED && !response.sessionActive)
        check(response.restoration == PanelRestoration.SUBMITTED)
        check(f.state().restoration == PanelRestoration.SUBMITTED)
        check(f.events.containsAll(listOf("restore-attempt", "control-close", "child-wait")))
        check(f.receipt().contains("\"stopReason\":\"CONTROL_IO\""))
        check(f.receipt().contains("\"offSubmission\":\"FAILED\""))
        check(!f.child.isAlive)
    }
    println("PASS actual Session OFF write failure owes restoration and triggers complete failure cleanup"); passed++
    PanelFixture().use { old ->
        old.off(); old.held()
        old.blockCancel = CountDownLatch(1)
        val before = System.nanoTime()
        old.close(SessionStopReason.HOST_STOP)
        check(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - before) < 1500)
        check(old.cancelEntered.count == 0L && old.socketClosed.get() && !old.child.isAlive)
        check(old.state().restoration == PanelRestoration.PENDING)
        check(old.receipt().contains("\"restorationSubmission\":\"PENDING\""))
        PanelFixture().use { fresh ->
            check(fresh.state().restoration == PanelRestoration.NOT_NEEDED)
            old.blockCancel!!.countDown()
            eventually { old.state().restoration == PanelRestoration.FAILED }
            check(fresh.writes.isEmpty())
            check(old.writes.none { it.contentEquals(byteArrayOf(10, 1)) })
            check(fresh.state().off == PanelSubmission.NOT_REQUESTED)
        }
    }
    println("PASS blocked cancellation leaves pending truthful receipt; late restore fails only on old closed socket"); passed++
    PanelFixture().use { f ->
        f.off(); f.blockRestore = CountDownLatch(1)
        val before = System.nanoTime()
        f.close(SessionStopReason.CONTROL_IO)
        check(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - before) < 1500)
        check(f.restoreEntered.count == 0L && f.socketClosed.get() && !f.child.isAlive)
        check(f.state().restoration == PanelRestoration.PENDING)
        check(f.receipt().contains("\"restorationSubmission\":\"PENDING\""))
        f.blockRestore!!.countDown()
        eventually { f.state().restoration == PanelRestoration.FAILED }
    }
    println("PASS blocked restore cannot hold telemetry/control lock or prevent actual Session socket/child cleanup"); passed++
    PanelFixture().use { f ->
        f.directory.resolve("endpoint.properties").writeText("host=192.168.42.1\nport=44378\n")
        val listener = SSLContext.getDefault().serverSocketFactory.createServerSocket() as SSLServerSocket
        val host = Bridge(f.directory, f.directory.resolve("unused.jar"), f.timer, listener, {}, f.ownerEpoch)
        @Suppress("UNCHECKED_CAST")
        val active = Bridge::class.java.getDeclaredField("active").apply { isAccessible = true }.get(host) as AtomicReference<Any?>
        active.set(f.session)
        try {
            check(host.requestPanelOff(321, f.state().context.ownerEpoch, f.state().context.projectionToken).off == PanelSubmission.SUBMITTED)
            check(host.panelState().requestId == 321L)
            host.close()
            check(active.get() == null && listener.isClosed && f.timer.isShutdown)
            check(host.panelState().restoration == PanelRestoration.SUBMITTED && !host.panelState().sessionActive)
            check(host.requestPanelOff(322, f.state().context.ownerEpoch, f.state().context.projectionToken).off == PanelSubmission.UNAVAILABLE)
            check(!f.child.isAlive)
        } finally { host.close() }
    }
    println("PASS actual Bridge routes to owned Session and retains final restoration evidence after release"); passed++
    PanelFixture().use { f ->
        (f.field("scale") as LiveScale).observe(ScrcpyRecord.Session(Geometry(480, 640), false))
        f.blockOff = CountDownLatch(1)
        val failures = CopyOnWriteArrayList<Throwable>()
        val off = thread(isDaemon = true) { try { f.off() } catch (e: Throwable) { failures.add(e) } }
        check(f.offEntered.await(2, TimeUnit.SECONDS))
        val scale = thread(isDaemon = true) {
            try {
                f.session.javaClass.getDeclaredMethod("requestScale", java.lang.Long.TYPE, java.lang.Double.TYPE, java.lang.Boolean.TYPE)
                    .apply { isAccessible = true }.invoke(f.session, 900L, 1.25, false)
            } catch (e: Throwable) { failures.add(e) }
        }
        eventually { scale.stackTrace.any { it.methodName == "writeControl" } }
        val firstDone = CountDownLatch(1); val secondDone = CountDownLatch(1)
        val first = thread(isDaemon = true) { try { f.close(SessionStopReason.HOST_STOP) } finally { firstDone.countDown() } }
        val second = thread(isDaemon = true) { try { f.close(SessionStopReason.HOST_STOP) } finally { secondDone.countDown() } }
        try {
            eventually { f.socketClosed.get() && !f.child.isAlive }
            check(firstDone.await(1, TimeUnit.SECONDS) && secondDone.await(1, TimeUnit.SECONDS)) {
                "R18-2 completed sockets/child but close/OwnedRelease still waits on blocked scale monitor"
            }
            check(f.receipt().contains("\"scaleStatus\":\"PENDING\""))
            check(f.receipt().contains("\"scaleSnapshotBasis\":\"LAST_KNOWN_REPORT_ATTEMPT\""))
            check(f.receipt().contains("\"finalAppliedScaleVerified\":false"))
            check(f.receipt().contains("\"requestedScale\":1.25"))
            check(f.receipt().contains("\"appliedScale\":1.0"))
        } finally {
            f.blockOff!!.countDown()
            listOf(off, scale, first, second).forEach { it.join(2000) }
        }
        check(failures.isEmpty()) { failures.toString() }
    }
    println("PASS R18-2 actual OFF plus actual scale overlap lets first/concurrent close and receipt finish"); passed++
    PanelFixture().use { old ->
        PanelFixture(old.ownerEpoch).use { fresh ->
            old.directory.resolve("endpoint.properties").writeText("host=192.168.42.1\nport=44378\n")
            val listener = SSLContext.getDefault().serverSocketFactory.createServerSocket() as SSLServerSocket
            val host = Bridge(old.directory, old.directory.resolve("unused.jar"), old.timer, listener, {}, old.ownerEpoch)
            @Suppress("UNCHECKED_CAST")
            val active = Bridge::class.java.getDeclaredField("active").apply { isAccessible = true }.get(host) as AtomicReference<Any?>
            val admission = Bridge::class.java.getDeclaredField("admission").apply { isAccessible = true }.get(host)
            synchronized(admission) { active.set(old.session) }
            val original = old.state().context; val replacement = fresh.state().context
            check(original.ownerEpoch == replacement.ownerEpoch && original.projectionToken != replacement.projectionToken)
            old.blockOff = CountDownLatch(1)
            val reply = AtomicReference<PanelPowerState?>(); val failure = AtomicReference<Throwable?>()
            val pending = thread(isDaemon = true) {
                try { reply.set(host.requestPanelOff(700, original.ownerEpoch, original.projectionToken)) }
                catch (e: Throwable) { failure.set(e) }
            }
            check(old.offEntered.await(2, TimeUnit.SECONDS))
            try {
                synchronized(admission) { active.set(fresh.session) }
                check(host.panelState(original.ownerEpoch, original.projectionToken).off == PanelSubmission.UNAVAILABLE)
                check(host.requestPanelOff(701, original.ownerEpoch, original.projectionToken).off == PanelSubmission.UNAVAILABLE)
                check(host.requestPanelOff(702, "different-owner", replacement.projectionToken).off == PanelSubmission.UNAVAILABLE)
                check(fresh.writes.isEmpty())
                val current = host.requestPanelOff(703, replacement.ownerEpoch, replacement.projectionToken)
                check(current.context == replacement && current.off == PanelSubmission.SUBMITTED)
                old.close(SessionStopReason.HOST_STOP)
                check(old.state().restoration == PanelRestoration.PENDING && !old.child.isAlive)
                check(fresh.writes.size == 1)
            } finally {
                old.blockOff!!.countDown(); pending.join(2000); host.close()
            }
            check(!pending.isAlive && failure.get() == null)
            check(reply.get()!!.context == original && !reply.get()!!.sessionActive)
            check(old.writes.isEmpty())
            check(fresh.writes.size == 2)
            check(host.panelState().context == replacement)
        }
    }
    println("PASS actual Bridge atomically captures old Session and rejects stale media/owner tokens without fresh side effects"); passed++
    PanelFixture().use { f ->
        f.close(SessionStopReason.HOST_STOP)
        val receipt = f.receipt()
        check(receipt.contains("\"scaleSnapshotBasis\":\"UNKNOWN_BEFORE_FIRST_REPORT\""))
        check(receipt.contains("\"scaleStatus\":\"UNKNOWN\""))
        check(receipt.contains("\"scaleRequestId\":null"))
        check(receipt.contains("\"requestedScale\":null"))
        check(receipt.contains("\"appliedScale\":null"))
    }
    println("PASS before first actual scale report receipt retains unknown/null rather than default baseline success"); passed++
    PanelFixture().use { f ->
        val live = f.field("scale") as LiveScale
        live.observe(ScrcpyRecord.Session(Geometry(480, 640), false))
        live.request(800, Double.NaN)
        check(live.state().status == ScaleStatus.READY)
        f.close(SessionStopReason.HOST_STOP)
        val receipt = f.receipt()
        check(receipt.contains("\"scaleStatus\":\"REJECTED\""))
        check(receipt.contains("\"scaleSnapshotBasis\":\"LAST_KNOWN_REPORT_ATTEMPT\""))
        check(receipt.contains("\"requestedScale\":null"))
        check(receipt.contains("\"observedWidth\":480"))
        check(receipt.contains("\"finalAppliedScaleVerified\":false"))
    }
    println("PASS cached rejected report differs honestly from actual LiveScale value and keeps finite/observed semantics"); passed++
    PanelFixture().use { old ->
        PanelFixture(old.ownerEpoch).use { fresh ->
            fun bridge(f: PanelFixture): Bridge {
                f.directory.resolve("endpoint.properties").writeText("host=192.168.42.1\nport=44378\n")
                val listener = SSLContext.getDefault().serverSocketFactory.createServerSocket() as SSLServerSocket
                val result = Bridge(f.directory, f.directory.resolve("unused.jar"), f.timer, listener, {}, f.ownerEpoch)
                @Suppress("UNCHECKED_CAST")
                val active = Bridge::class.java.getDeclaredField("active").apply { isAccessible = true }.get(result) as AtomicReference<Any?>
                active.set(f.session)
                return result
            }
            val oldBridge = bridge(old); val freshBridge = bridge(fresh)
            val routes = PanelSessionRoutes<Bridge> { it.invalidatePanelOperations() }
            try {
                val firstOwner = routes.begin(old.ownerEpoch); routes.publish(firstOwner, oldBridge)
                val captured = routes.capture(old.ownerEpoch)!!
                check(captured.target === oldBridge)
                routes.stop()
                val oldContext = old.state().context
                check(old.state().sessionActive) // Teardown has not begun: admission closes first.
                check(captured.target.requestPanelOff(1000, oldContext.ownerEpoch, oldContext.projectionToken).off == PanelSubmission.UNAVAILABLE)
                check(old.writes.isEmpty())
                val newOwner = routes.begin(fresh.ownerEpoch); routes.publish(newOwner, freshBridge)
                routes.complete(firstOwner, oldBridge, old.state())
                routes.publish(firstOwner, oldBridge) // A late accepted-worker publication cannot overwrite fresh.
                check(routes.current()!!.target === freshBridge)
                check(routes.lastState().off == PanelSubmission.UNAVAILABLE)
                val context = fresh.state().context
                check(routes.capture(context.ownerEpoch)!!.target.requestPanelOff(1001, context.ownerEpoch, context.projectionToken).off == PanelSubmission.SUBMITTED)
                routes.stop()
                routes.publish(newOwner, freshBridge) // Publication after Stop remains invalid even for that same Owner.
                check(routes.current() == null)
                check(freshBridge.requestPanelOff(1002, context.ownerEpoch, context.projectionToken).off == PanelSubmission.UNAVAILABLE)
            } finally { oldBridge.close(); freshBridge.close() }
        }
    }
    println("PASS actual Bridge route Stop invalidates before teardown and old publication/cleanup cannot overwrite fresh owner route"); passed++
    println("$passed actual Session composition scenarios passed; modeled Android socket/process edges, no physical proof")
}
