package com.code2hack.eyeprojector.directrg

import com.code2hack.eyeprojector.directhost.SessionController
import com.code2hack.eyeprojector.protocol.SessionControl
import com.code2hack.eyeprojector.protocol.SessionPhase
import com.code2hack.eyeprojector.protocol.SessionState
import java.io.File
import java.io.IOException
import java.net.Socket
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Actual production client/controller/attachment; only network and the Android UI queue are modeled. */
private class UiQueue {
    private val work = LinkedBlockingQueue<() -> Unit>()
    val submitted = AtomicInteger()
    var afterPost: () -> Unit = {}
    fun post(action: () -> Unit) { work.add(action); submitted.incrementAndGet(); afterPost() }
    fun take(): () -> Unit = work.poll(4, TimeUnit.SECONDS) ?: error("No queued UI delivery")
    fun drain() { while (true) (work.poll() ?: return).invoke() }
    fun until(condition: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(4)
        while (!condition()) {
            val remaining = end - System.nanoTime()
            check(remaining > 0) { "UI condition was not delivered" }
            (work.poll(remaining, TimeUnit.NANOSECONDS) ?: error("No queued UI delivery")).invoke()
        }
    }
}

private class Host {
    private val sequence = AtomicInteger()
    private val owned = AtomicReference<String>()
    val stops = AtomicInteger()
    val controller = SessionController({ owned.set(it) }, { stops.incrementAndGet() }, { "host-${sequence.incrementAndGet()}" })
    fun ready(): SessionState {
        val epoch = controller.start().epoch
        controller.backend(epoch, "READY")
        return controller.state().also { check(it.phase == SessionPhase.AVAILABLE) }
    }
    fun release() { controller.backend(owned.get(), "STOPPED") }
}

private typealias Transport = (SessionControl.Request, (Socket) -> Unit) -> SessionState?

/** Frozen525 used the Activity queue around callbacks; corrected source owns the queued-delivery check. */
private fun client(ui: UiQueue, state: (SessionState) -> Unit, unavailable: () -> Unit, transport: Transport): PairedSessionClient {
    val constructor = PairedSessionClient::class.java.declaredConstructors.single {
        it.parameterCount in 4..5 && it.parameterTypes.last() != Int::class.javaPrimitiveType
    }.apply { isAccessible = true }
    val onState: (SessionState) -> Unit = if (constructor.parameterCount == 4) ({ value -> ui.post { state(value) } }) else state
    val onUnavailable: () -> Unit = if (constructor.parameterCount == 4) ({ ui.post(unavailable) }) else unavailable
    val arguments = mutableListOf<Any>(File("unused-synthetic-directory"), onState, onUnavailable, transport)
    if (constructor.parameterCount == 5) arguments.add({ action: () -> Unit -> ui.post(action) })
    return constructor.newInstance(*arguments.toTypedArray()) as PairedSessionClient
}

private fun await(latch: CountDownLatch) = check(latch.await(4, TimeUnit.SECONDS)) { "Scheduled operation did not finish" }
private fun canceledReply(opened: (Socket) -> Unit, entered: CountDownLatch): Nothing = Socket().use { socket ->
    opened(socket); entered.countDown()
    val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(4)
    while (!socket.isClosed && System.nanoTime() < end) Thread.sleep(1)
    check(socket.isClosed)
    throw IOException("Synthetic canceled reply after server admission")
}

private fun queuedAvailableAndContinuedPolling() {
    val host = Host(); val old = host.ready(); val ui = UiQueue(); val attachment = SessionAttachment()
    val attached = mutableListOf<String>(); val errors = AtomicInteger(); val deliveries = AtomicInteger(); val startEntered = CountDownLatch(1)
    val stopAttempt = CountDownLatch(1); val laterPolls = CountDownLatch(2); val cancelIssued = AtomicBoolean()
    val proposed = AtomicReference<String>(); val starts = AtomicInteger()
    val transport: Transport = { request, opened -> when (request.operation) {
        SessionControl.Operation.STATUS -> {
            if (cancelIssued.get()) laterPolls.countDown()
            host.controller.state()
        }
        SessionControl.Operation.START -> {
            starts.incrementAndGet(); proposed.set(request.startEpoch)
            check(host.controller.start(request.epoch, request.startEpoch).epoch == old.epoch)
            canceledReply(opened, startEntered)
        }
        SessionControl.Operation.STOP -> {
            if (request.epoch == old.epoch) {
                stopAttempt.countDown()
                throw IOException("Synthetic unavailable remote Stop; no success is claimed")
            }
            host.controller.stop(request.epoch)
        }
        else -> error("Unexpected operation")
    } }
    val session = client(ui, { deliveries.incrementAndGet(); if (attachment.available(it)) attached.add(it.epoch) }, { errors.incrementAndGet() }, transport)
    try {
        session.connect(); await(startEntered)
        check(ui.submitted.get() > 0)
        val staleDelivery = ui.take()
        val targets = session.disconnect(null).also { it.forEach(attachment::suppress) }
        staleDelivery()
        check(deliveries.get() == 0) { "A pre-Disconnect UI delivery still invoked its callback" }
        check(targets.toSet() == setOf(old.epoch, proposed.get())) { "Disconnect omitted the observed host epoch: $targets" }
        cancelIssued.set(true); session.start(); await(stopAttempt); await(laterPolls)
        ui.drain()
        check(attached.isEmpty()) { "Old AVAILABLE attached after Disconnect: $attached" }
        check(host.controller.state() == old) { "A failed remote Stop was incorrectly treated as success" }
        check(errors.get() > 0) { "Current genuine transport failure disappeared" }
        check(starts.get() == 1)
        host.controller.stop(old.epoch); host.release()
        val fresh = host.ready()
        ui.until { attached.contains(fresh.epoch) }
        ui.drain()
        check(attached == listOf(fresh.epoch))
        check(starts.get() == 1) { "Fresh Phone Start required an extra Glasses Start" }
    } finally { session.close(attachment.release()) }
}

private fun concurrentStartReplyCarriesDifferentOwner() {
    val host = Host(); val ui = UiQueue(); val attachment = SessionAttachment(); val attached = mutableListOf<String>()
    val repliesQueued = CountDownLatch(2); val stopApplied = CountDownLatch(1)
    val active = AtomicReference<SessionState>(); val proposed = AtomicReference<String>()
    ui.afterPost = { repliesQueued.countDown() }
    val transport: Transport = { request, _ -> when (request.operation) {
        SessionControl.Operation.STATUS -> host.controller.state()
        SessionControl.Operation.START -> {
            proposed.set(request.startEpoch)
            active.set(host.ready()) // A Phone Start wins after the preceding inactive STATUS.
            host.controller.start(request.epoch, request.startEpoch).also { check(it.epoch == active.get().epoch) }
        }
        SessionControl.Operation.STOP -> host.controller.stop(request.epoch).also {
            if (request.epoch == active.get().epoch) stopApplied.countDown()
        }
        else -> error("Unexpected operation")
    } }
    val session = client(ui, { if (attachment.available(it)) attached.add(it.epoch) }, {}, transport)
    try {
        session.connect(); await(repliesQueued)
        val targets = session.disconnect(null).also { it.forEach(attachment::suppress) }
        check(targets.toSet() == setOf(active.get().epoch, proposed.get())) { "Actual Start reply owner was omitted: $targets" }
        await(stopApplied); ui.drain()
        check(attached.isEmpty())
        check(host.controller.state().phase == SessionPhase.STOPPING && host.stops.get() == 1)
    } finally { session.close() }
}

private fun failedFirstFinalStopDoesNotSkipAcceptedStart() {
    val host = Host(); val ui = UiQueue(); val accepted = CountDownLatch(1); val stopped = CountDownLatch(1)
    val proposed = AtomicReference<String>(); val stopThread = AtomicReference<Thread>()
    val attempts = Collections.synchronizedList(mutableListOf<String>())
    val transport: Transport = { request, opened -> when (request.operation) {
        SessionControl.Operation.STATUS -> host.controller.state()
        SessionControl.Operation.START -> {
            proposed.set(request.startEpoch)
            check(host.controller.start(request.epoch, request.startEpoch).epoch == request.startEpoch)
            canceledReply(opened, accepted)
        }
        SessionControl.Operation.STOP -> {
            attempts.add(request.epoch)
            if (request.epoch == "stale-attached") throw IOException("First independent STOP failed")
            host.controller.stop(request.epoch).also { stopThread.set(Thread.currentThread()); stopped.countDown() }
        }
        else -> error("Unexpected operation")
    } }
    val session = client(ui, {}, {}, transport)
    try {
        session.connect(); await(accepted); session.close("stale-attached")
        await(stopped); stopThread.get().join(1000); check(!stopThread.get().isAlive)
        check(attempts == listOf("stale-attached", proposed.get()))
        check(host.controller.state().phase == SessionPhase.STOPPING && host.stops.get() == 1)
        check(host.controller.state().phase != SessionPhase.INACTIVE) // No release acknowledgment was modeled.
        ui.drain()
    } finally { session.close() }
}

private fun staleFailureAndFreshConnect(disconnectFirst: Boolean) {
    val host = Host(); val available = host.ready(); val ui = UiQueue(); val attachment = SessionAttachment()
    val firstQueued = CountDownLatch(1); val statuses = AtomicInteger(); val errors = AtomicInteger(); val failNext = AtomicBoolean()
    ui.afterPost = { firstQueued.countDown() }
    val transport: Transport = { request, _ -> when (request.operation) {
        SessionControl.Operation.STATUS -> {
            if (statuses.incrementAndGet() == 1 || failNext.compareAndSet(true, false)) throw IOException("Synthetic connection error")
            host.controller.state()
        }
        SessionControl.Operation.START -> host.controller.start(request.epoch, request.startEpoch)
        SessionControl.Operation.STOP -> host.controller.stop(request.epoch)
        else -> error("Unexpected operation")
    } }
    val session = client(ui, { attachment.available(it) }, { errors.incrementAndGet() }, transport)
    try {
        session.command(SessionControl.Operation.STATUS, ""); await(firstQueued)
        if (disconnectFirst) session.disconnect(null).forEach(attachment::suppress)
        session.connect()
        ui.until { attachment.epoch == available.epoch }
        check(errors.get() == 0) { "Obsolete failure delivery overwrote a later action" }
        failNext.set(true); session.command(SessionControl.Operation.STATUS, "")
        ui.until { errors.get() == 1 }
    } finally { session.close(attachment.release()) }
}

private fun currentDeliveryCanDisconnectReentrantly() {
    val host = Host(); val available = host.ready(); val ui = UiQueue(); val delivered = AtomicInteger()
    val stopped = CountDownLatch(1); lateinit var session: PairedSessionClient
    val transport: Transport = { request, _ -> when (request.operation) {
        SessionControl.Operation.STATUS -> host.controller.state()
        SessionControl.Operation.STOP -> host.controller.stop(request.epoch).also { stopped.countDown() }
        else -> error("Unexpected operation")
    } }
    session = client(ui, {
        if (delivered.incrementAndGet() == 1) check(session.disconnect(it.epoch).contains(available.epoch))
    }, {}, transport)
    try {
        session.command(SessionControl.Operation.STATUS, "")
        ui.until { delivered.get() == 1 }; await(stopped)
        check(host.controller.state().phase == SessionPhase.STOPPING)
    } finally { session.close() }
}

fun main() {
    var failures = 0
    fun scenario(name: String, run: () -> Unit) {
        try { run(); println("PASS $name") }
        catch (error: Throwable) { failures++; println("FAIL $name: $error"); error.printStackTrace() }
    }
    scenario("R8-01 queued AVAILABLE, continued polling, failed Stop and fresh Phone Start", ::queuedAvailableAndContinuedPolling)
    scenario("R8-01 concurrent Phone Start reply differs from the proposed epoch", ::concurrentStartReplyCarriesDifferentOwner)
    scenario("R8-02 first final Stop failure cannot skip accepted pending Start", ::failedFirstFinalStopDoesNotSkipAcceptedStart)
    scenario("R8-01 obsolete unavailable after Disconnect", { staleFailureAndFreshConnect(true) })
    scenario("R8-01 obsolete unavailable after explicit Connect", { staleFailureAndFreshConnect(false) })
    scenario("R8-01 current delivery supports reentrant Disconnect", ::currentDeliveryCanDisconnectReentrantly)
    check(failures == 0) { "$failures actual-client/controller scheduling scenarios failed" }
    println("6 actual-client/controller scheduling scenarios passed; modeled network/UI, no device claim")
}
