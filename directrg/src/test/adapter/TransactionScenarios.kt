package com.code2hack.eyeprojector.directrg

import com.code2hack.eyeprojector.directhost.SessionAttemptStore
import com.code2hack.eyeprojector.directhost.SessionController
import com.code2hack.eyeprojector.protocol.SessionControl
import com.code2hack.eyeprojector.protocol.SessionPhase
import com.code2hack.eyeprojector.protocol.SessionState
import java.io.File
import java.io.IOException
import java.net.Socket
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Only transport scheduling, UI dispatch, and a backend work queue are modeled. No device operations. */
private class Ui {
    private val work = LinkedBlockingQueue<() -> Unit>()
    fun post(task: () -> Unit) { work.add(task) }
    fun drain() { while (true) (work.poll() ?: return).invoke() }
    fun until(description: String, condition: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(4)
        while (!condition()) {
            val left = end - System.nanoTime()
            check(left > 0) { description }
            work.poll(minOf(left, TimeUnit.MILLISECONDS.toNanos(50)), TimeUnit.NANOSECONDS)?.invoke()
        }
        drain()
    }
}

private class Phone(name: String) {
    val directory = File(System.getenv("CASE_ROOT"), name).apply { mkdirs() }
    private val stateDirectory = File(directory, "state").apply { mkdirs() }
    val store = SessionAttemptStore(File(stateDirectory, "attempts.properties"))
    private val epoch = AtomicInteger()
    val starts = AtomicInteger()
    val owned = AtomicReference<String>()
    val allStopDispatches = AtomicInteger()
    var queuedStops = AtomicInteger()
        private set
    var model = create(SessionPhase.INACTIVE)
        private set
    private fun create(phase: SessionPhase): SessionController {
        val generationStops = queuedStops
        return SessionController({ value -> owned.set(value); starts.incrementAndGet(); Unit }, {
            generationStops.incrementAndGet(); allStopDispatches.incrementAndGet(); Unit
        }, { "${directory.name}-${epoch.incrementAndGet()}" }, phase, store.read(), store::write)
    }
    fun command(request: SessionControl.Request) = model.command(request)
    fun ready(): SessionState {
        val owner = model.start().epoch
        check(owned.get() == owner) { "Phone Start did not dispatch an owned backend: ${model.state()}" }
        model.backend(owner, "READY")
        return model.state().also { check(it.phase == SessionPhase.AVAILABLE) }
    }
    fun restartUnknown() {
        queuedStops = AtomicInteger()
        model = create(SessionPhase.UNKNOWN)
    }
    fun release(owner: String) { model.backend(owner, "STOPPED"); owned.compareAndSet(owner, null) }
    fun breakStore() {
        Files.move(stateDirectory.toPath(), File(directory, "preserved-state").toPath())
        stateDirectory.writeText("Deliberate host-fixture blocker at app-private atomic-write parent")
    }
    fun repairStore() {
        check(stateDirectory.delete())
        Files.move(File(directory, "preserved-state").toPath(), stateDirectory.toPath())
    }
}

private typealias Transport = (SessionControl.Request, (Socket) -> Unit) -> SessionState?
private fun client(ui: Ui, attachment: SessionAttachment, attached: MutableList<String>, transport: Transport): PairedSessionClient =
    PairedSessionClient(File("unused-synthetic-pair-directory"), { value ->
        if (attachment.available(value)) attached.add(value.epoch)
    }, {}, transport, deliver = ui::post)
private fun await(latch: CountDownLatch, label: String) = check(latch.await(4, TimeUnit.SECONDS)) { label }
private fun canceledReply(opened: (Socket) -> Unit, entered: CountDownLatch): Nothing = Socket().use { socket ->
    opened(socket); entered.countDown()
    val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(4)
    while (!socket.isClosed && System.nanoTime() < end) Thread.sleep(1)
    check(socket.isClosed) { "Receiver did not close its admitted reply socket" }
    throw IOException("Modeled reply canceled after authoritative Phone admission")
}

private fun firstStatusDisconnect() {
    val phone = Phone("first-status"); val owner = phone.ready(); val ui = Ui(); val attachment = SessionAttachment()
    val attached = mutableListOf<String>(); val entered = CountDownLatch(1); val laterStatus = CountDownLatch(1)
    val statusCount = AtomicInteger(); val thirdStatus = CountDownLatch(1); val stops = AtomicInteger()
    val session = client(ui, attachment, attached) { request, opened ->
        when (request.operation) {
            SessionControl.Operation.STATUS -> {
                val reply = phone.command(request)
                val count = statusCount.incrementAndGet()
                if (count == 1) canceledReply(opened, entered)
                if (count >= 3) thirdStatus.countDown()
                laterStatus.countDown(); reply
            }
            SessionControl.Operation.STOP -> { stops.incrementAndGet(); phone.command(request) }
            SessionControl.Operation.START -> phone.command(request)
            else -> error("Unexpected ${request.operation}")
        }
    }
    try {
        session.connect(); await(entered, "First STATUS did not reach authoritative owner admission")
        val targets = session.disconnect(null).also { it.forEach(attachment::suppress) }
        session.start(); await(laterStatus, "Client did not continue after initial STATUS cancellation")
        // Flush through a later command rather than relying on an arbitrary sleep for UI posting.
        session.command(SessionControl.Operation.STATUS, "")
        await(thirdStatus, "Following STATUS did not establish the prior delivery had been posted")
        ui.drain()
        println("TRACE firstSTATUS owner=${owner.epoch} targets=$targets transportStops=${stops.get()} backendStopDispatches=${phone.queuedStops.get()} attached=$attached state=${phone.model.state()}")
        check(stops.get() > 0 && phone.queuedStops.get() == 1) { "Initial ownership query lacked a cancellable durable attempt" }
        check(attached.isEmpty()) { "Canceled first STATUS reattached preexisting owner ${owner.epoch}: $attached" }
        check(phone.model.state().phase == SessionPhase.STOPPING) { "Canceled owner was exposed as available" }
    } finally { session.close(attachment.release()) }
}

private fun queuedStopSurvivesPhoneDeath() {
    val phone = Phone("queued-stop"); val ui = Ui(); val attachment = SessionAttachment(); val attached = mutableListOf<String>()
    val entered = CountDownLatch(1); val stopAcknowledged = CountDownLatch(1); val queryAfterRestore = CountDownLatch(1)
    val owner = AtomicReference<SessionState>(); val restored = AtomicBoolean(); val startSeen = AtomicBoolean()
    val restoredQueries = AtomicInteger()
    val session = client(ui, attachment, attached) { request, opened -> when (request.operation) {
        SessionControl.Operation.STATUS -> phone.command(request).also { if (restored.get() && restoredQueries.incrementAndGet() >= 2) queryAfterRestore.countDown() }
        SessionControl.Operation.START -> {
            check(startSeen.compareAndSet(false, true)) { "Repeated Receiver start after Disconnect" }
            owner.set(phone.ready()) // Phone A wins between STATUS(I) and START(attempt).
            phone.command(request)
            canceledReply(opened, entered)
        }
        SessionControl.Operation.STOP -> phone.command(request).also { stopAcknowledged.countDown() }
        else -> error("Unexpected ${request.operation}")
    } }
    try {
        session.connect(); await(entered, "Concurrent START did not reach owner admission")
        session.disconnect(null).forEach(attachment::suppress)
        await(stopAcknowledged, "No canceled-attempt STOP response")
        val queuedBeforeDeath = phone.queuedStops.get()
        check(queuedBeforeDeath == 1 && phone.model.state().phase == SessionPhase.STOPPING)
        // The real bootstrap queues Stop; the Phone process now dies before that backend work runs.
        phone.restartUnknown(); phone.model.restore(owner.get().epoch, "READY"); restored.set(true)
        session.command(SessionControl.Operation.STATUS, "")
        session.command(SessionControl.Operation.STATUS, "")
        await(queryAfterRestore, "Following restored STATUS did not establish a prior delivery was posted")
        ui.drain()
        println("TRACE queuedStop owner=${owner.get().epoch} lostQueue=$queuedBeforeDeath restoredQueue=${phone.queuedStops.get()} state=${phone.model.state()} attached=$attached")
        check(phone.queuedStops.get() == 1) { "Durable cancellation intent did not reissue lost queued backend Stop" }
        check(phone.model.state().phase == SessionPhase.STOPPING && attached.isEmpty()) { "Restored canceled owner became available/attached" }
        repeat(3) { phone.model.restore(owner.get().epoch, "READY") }
        check(phone.queuedStops.get() == 1) { "Repeated restore duplicated the queued Stop" }
    } finally { session.close(attachment.release()) }
}

private fun closedReceiverUnknownRestoration() {
    val phone = Phone("closed-unknown"); val ui = Ui(); val attachment = SessionAttachment(); val attached = mutableListOf<String>()
    val entered = CountDownLatch(1); val cancelReturned = CountDownLatch(1); val stopThread = AtomicReference<Thread>()
    val owner = AtomicReference<SessionState>(); val restarted = AtomicBoolean(); val starts = AtomicInteger()
    val session = client(ui, attachment, attached) { request, opened -> when (request.operation) {
        SessionControl.Operation.STATUS -> phone.command(request)
        SessionControl.Operation.START -> {
            starts.incrementAndGet(); owner.set(phone.ready()); phone.command(request); canceledReply(opened, entered)
        }
        SessionControl.Operation.STOP -> {
            if (restarted.compareAndSet(false, true)) phone.restartUnknown()
            phone.command(request).also { stopThread.set(Thread.currentThread()); cancelReturned.countDown() }
        }
        else -> error("Unexpected ${request.operation}")
    } }
    try {
        session.connect(); await(entered, "No admitted in-flight START")
        session.close(); await(cancelReturned, "Final Receiver close did not send its cancellation")
        stopThread.get().join(1500); check(!stopThread.get().isAlive) { "Final Receiver cleanup did not finish" }
        check(phone.model.state().phase == SessionPhase.UNKNOWN) { "Fixture did not retain unknown backend reconciliation" }
        phone.model.restore(owner.get().epoch, "READY")
        ui.drain()
        println("TRACE closedUNKNOWN owner=${owner.get().epoch} restoredQueue=${phone.queuedStops.get()} state=${phone.model.state()} attached=$attached starts=${starts.get()}")
        check(phone.queuedStops.get() == 1 && phone.model.state().phase == SessionPhase.STOPPING) { "Closed Receiver's durable unknown cancellation was lost at Phone restore" }
        check(attached.isEmpty())
        phone.model.restore(owner.get().epoch, "READY"); check(phone.queuedStops.get() == 1)
    } finally { session.close() }
}

private fun terminalStoreFailureIsControlled() {
    val phone = Phone("terminal-store-io"); val owner = phone.ready()
    phone.model.stop(owner.epoch); check(phone.queuedStops.get() == 1)
    phone.breakStore()
    var escaped: Throwable? = null
    // The callback body is the actual model backend observer entry point; filesystem and store are real.
    try { phone.model.backend(owner.epoch, "STOPPED") } catch (failure: Throwable) { escaped = failure }
    val terminal = phone.model.state()
    println("TRACE terminalStoreIO escaped=${escaped?.javaClass?.name} terminal=$terminal")
    check(escaped == null) { "Atomic store IOException escaped authoritative terminal observer: $escaped" }
    check(!terminal.phase.active && terminal.phase !in setOf(SessionPhase.UNKNOWN, SessionPhase.STOPPING)) { "Released backend represented as live or unverified" }
    val startsBefore = phone.starts.get()
    val rejected = phone.model.start()
    check(phone.starts.get() == startsBefore && !rejected.phase.active) { "Failed commit dispatched a backend or promised an active owner" }
    phone.repairStore()
    phone.command(SessionControl.Request(SessionControl.Operation.STATUS))
    check(phone.store.read().owner != owner.epoch) { "Retirement was not retryable after store recovery" }
    val fresh = phone.ready()
    check(fresh.epoch != owner.epoch && phone.starts.get() == startsBefore + 1)
}

private fun freshOwnerAndRepeatedActions() {
    val phone = Phone("fresh-owner"); val ui = Ui(); val attachment = SessionAttachment(); val attached = mutableListOf<String>()
    val entered = CountDownLatch(1); val canceled = CountDownLatch(1); val owner = AtomicReference<SessionState>()
    val start = AtomicReference<SessionControl.Request>(); val oldStops = AtomicInteger(); val startCount = AtomicInteger()
    val session = client(ui, attachment, attached) { request, opened -> when (request.operation) {
        SessionControl.Operation.STATUS -> phone.command(request)
        SessionControl.Operation.START -> {
            check(startCount.incrementAndGet() == 1); start.set(request)
            owner.set(phone.ready()); phone.command(request); canceledReply(opened, entered)
        }
        SessionControl.Operation.STOP -> phone.command(request).also { oldStops.incrementAndGet(); canceled.countDown() }
        else -> error("Unexpected ${request.operation}")
    } }
    try {
        session.connect(); await(entered, "Old attempt did not reach Phone")
        session.disconnect(null).forEach(attachment::suppress); await(canceled, "Old attempt cancellation was not applied")
        val request = start.get(); val cancellation = SessionControl.Request.cancelStart(request.epoch, request.startEpoch)
        repeat(3) { phone.command(cancellation) }
        check(phone.queuedStops.get() == 1) { "Repeated cancellation dispatched duplicate backend stops" }
        phone.release(owner.get().epoch)
        val fresh = phone.ready(); val dispatches = phone.allStopDispatches.get()
        repeat(3) {
            phone.command(cancellation)
            phone.command(request)
            phone.command(SessionControl.Request(SessionControl.Operation.STATUS, startEpoch = request.startEpoch))
        }
        check(phone.model.state().epoch == fresh.epoch && phone.model.state().phase == SessionPhase.AVAILABLE)
        check(phone.allStopDispatches.get() == dispatches) { "Retired canceled attempt chased future fresh C" }
        session.start(); ui.until("Genuine later Phone Start could not attach through a fresh observation attempt") { attached.contains(fresh.epoch) }
        check(attached == listOf(fresh.epoch))
        println("TRACE freshC old=${owner.get().epoch} fresh=${fresh.epoch} oldStopTransports=${oldStops.get()} stopDispatches=$dispatches attached=$attached")
    } finally { session.close(attachment.release()) }
}

private fun unknownObservationThenAvailableCancellation() {
    val phone = Phone("unknown-then-available"); val owner = phone.ready(); phone.restartUnknown()
    val ui = Ui(); val attachment = SessionAttachment(); val attached = mutableListOf<String>()
    val unknownObserved = CountDownLatch(1); val entered = CountDownLatch(1); val correlatedReturned = CountDownLatch(1)
    val seenStart = AtomicReference<SessionControl.Request>(); val stopReply = AtomicReference<SessionState>()
    val session = client(ui, attachment, attached) { request, opened -> when (request.operation) {
        SessionControl.Operation.STATUS -> phone.command(request).also { if (it.phase == SessionPhase.UNKNOWN) unknownObserved.countDown() }
        SessionControl.Operation.START -> {
            seenStart.set(request); phone.command(request); canceledReply(opened, entered)
        }
        SessionControl.Operation.STOP -> {
            if (request.startEpoch.isEmpty()) throw IOException("Independent known-owner STOP transport failed")
            phone.command(request).also { stopReply.set(it); correlatedReturned.countDown() }
        }
        else -> error("Unexpected ${request.operation}")
    } }
    try {
        session.connect(); await(unknownObserved, "Initial UNKNOWN STATUS did not establish the attempt")
        phone.model.restore(owner.epoch, "READY")
        session.start(); await(entered, "Reconciled active owner did not reach the pending Start reply")
        session.disconnect(null).forEach(attachment::suppress)
        await(correlatedReturned, "Correlated cancellation transport did not independently succeed")
        println("TRACE unknownBaseline owner=${owner.epoch} start=${seenStart.get()} response=${stopReply.get()} queue=${phone.queuedStops.get()}")
        check(phone.queuedStops.get() == 1 && phone.model.state().phase == SessionPhase.STOPPING) {
            "UNKNOWN-to-AVAILABLE observation changed baseline and rejected its bound-owner cancellation"
        }
    } finally { session.close(attachment.release()) }
}

private fun cancellationBeforeInitialStatusAdmission(failBeforeAdmission: Boolean) {
    val label = if (failBeforeAdmission) "failed-before-admission" else "cancel-overtakes-status"
    val phone = Phone(label); val owner = phone.ready(); val ui = Ui(); val attachment = SessionAttachment(); val attached = mutableListOf<String>()
    val entered = CountDownLatch(1); val releaseFailure = CountDownLatch(1); val admitLate = CountDownLatch(1)
    val lateAdmitted = CountDownLatch(1); val cancellationReturned = CountDownLatch(1)
    val first = AtomicBoolean(true); val initial = AtomicReference<SessionControl.Request>(); val serverThread = AtomicReference<Thread>()
    val lateResponse = AtomicReference<SessionState>()
    val session = client(ui, attachment, attached) { request, opened -> when (request.operation) {
        SessionControl.Operation.STATUS -> {
            if (first.compareAndSet(true, false)) {
                initial.set(request)
                if (failBeforeAdmission) {
                    entered.countDown(); await(releaseFailure, "Initial failed transport was not released")
                    throw IOException("First STATUS failed before any Phone handler admission")
                }
                serverThread.set(Thread({
                    check(admitLate.await(6, TimeUnit.SECONDS)) { "Late admitted STATUS was not released" }
                    lateResponse.set(phone.command(request)); lateAdmitted.countDown()
                }, "private-late-status-handler").apply { isDaemon = true; start() })
                canceledReply(opened, entered)
            }
            phone.command(request)
        }
        SessionControl.Operation.STOP -> phone.command(request).also { cancellationReturned.countDown() }
        SessionControl.Operation.START -> phone.command(request)
        else -> error("Unexpected ${request.operation}")
    } }
    try {
        session.connect(); await(entered, "Initial STATUS transport was not pending before admission")
        session.disconnect(null).forEach(attachment::suppress)
        releaseFailure.countDown()
        await(cancellationReturned, "Cancel did not carry the pre-admission attempt identity")
        check(phone.queuedStops.get() == 1 && phone.model.state().phase == SessionPhase.STOPPING)
        phone.release(owner.epoch); val fresh = phone.ready(); val dispatches = phone.allStopDispatches.get()
        if (!failBeforeAdmission) {
            admitLate.countDown(); await(lateAdmitted, "Overtaken STATUS was never admitted")
            check(lateResponse.get().epoch == fresh.epoch)
        }
        val old = initial.get()
        repeat(3) { phone.command(SessionControl.Request.cancelStart("", old.startEpoch)) }
        check(phone.allStopDispatches.get() == dispatches && phone.model.state().epoch == fresh.epoch) { "Late prequery cancellation rebound to fresh C" }
        session.start(); ui.until("Fresh C could not attach after pre-admission cancellation") { attached.contains(fresh.epoch) }
        check(attached == listOf(fresh.epoch))
        println("TRACE $label old=${owner.epoch} first=$old fresh=${fresh.epoch} stopDispatches=$dispatches lateReply=${lateResponse.get()} attached=$attached")
    } finally {
        releaseFailure.countDown(); admitLate.countDown(); serverThread.get()?.join(1000)
        session.close(attachment.release())
    }
}

fun main() {
    val scenarios = listOf(
        "R8-01 initial STATUS Disconnect" to ::firstStatusDisconnect,
        "R8-03 queued Stop lost before Phone process restore" to ::queuedStopSurvivesPhoneDeath,
        "R8-03 closed Receiver UNKNOWN restore" to ::closedReceiverUnknownRestoration,
        "R8-04 terminal atomic store IOException" to ::terminalStoreFailureIsControlled,
        "positive fresh C and repeated cancellation" to ::freshOwnerAndRepeatedActions,
        "positive UNKNOWN observation then AVAILABLE cancel" to ::unknownObservationThenAvailableCancellation,
        "R8-01 CANCEL overtakes initial STATUS admission" to { cancellationBeforeInitialStatusAdmission(false) },
        "R8-01 failed first STATUS then cancellation admission" to { cancellationBeforeInitialStatusAdmission(true) },
    )
    var failures = 0
    scenarios.forEach { (name, scenario) -> try { scenario(); println("PASS $name") } catch (failure: Throwable) {
        failures++; println("FAIL $name: ${failure.message}"); failure.printStackTrace(System.out)
    } }
    println("RESULT scenarios=${scenarios.size} failures=$failures; actual client/controller/store/attachment, modeled transport/UI/backend scheduling, host filesystem only")
    check(failures == 0) { "$failures transaction regressions failed" }
}
