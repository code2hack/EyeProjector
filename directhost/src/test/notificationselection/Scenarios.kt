package com.code2hack.eyeprojector.directhost.notifications.selectiontest

import android.content.SharedPreferences
import android.os.Handler
import com.code2hack.eyeprojector.directhost.*
import com.code2hack.eyeprojector.directhost.activation.*
import com.code2hack.eyeprojector.directhost.activation.producer.Fixture
import com.code2hack.eyeprojector.directhost.apps.*
import com.code2hack.eyeprojector.directhost.notifications.*
import com.code2hack.eyeprojector.directhost.scale.*
import com.code2hack.eyeprojector.protocol.*
import java.io.Closeable
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import rikka.shizuku.Shizuku

private val identifiers = AtomicInteger()
private class CheckedThread(private val work: () -> Unit, name: String) : Thread(name) {
    private var failure: Throwable? = null
    override fun run() { try { work() } catch (error: Throwable) { failure = error } }
    fun finish() { join(2000); check(!isAlive) { "background operation did not return: $name" }; failure?.let { throw it } }
}
private fun field(o: Any, name: String) = o.javaClass.getDeclaredField(name).apply { isAccessible = true }
private fun selected(f: NotificationFixture) = field(f.policy, "selected").get(f.policy) as NotificationSelection
private fun await(message: String, condition: () -> Boolean) {
    val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
    while (!condition() && System.nanoTime() < until) { Handler.flush(); Thread.yield() }
    check(condition()) { message }
}

private class NotificationFixture(portFactory: (FacadeNotificationPort) -> NotificationSelectionPort = { it }) : AutoCloseable {
    val actual = Fixture()
    val pkg = "example.notification${identifiers.incrementAndGet()}"
    val policy = NotificationPolicy.process
    val choices = ChosenApps.open(actual.context)
    val preferences = AppNotificationPreferences.open(actual.context)
    val owner: NotificationSelectionOwner
    val runtime: PhoneNotificationRuntime
    val connection = NotificationListenerConnection()
    var changedHook: (() -> Unit)? = null
    private var time = 0L
    init {
        choices.add(InstalledApp(pkg, pkg))
        owner = NotificationSelectionOwner(portFactory(FacadeNotificationPort(actual.sessions)), policy,
            { packageName -> choices.read().any { it.packageName == packageName } to preferences.isEnabled(packageName) },
            { Handler(android.os.Looper.getMainLooper()).post(it) }, { changedHook?.invoke() })
        runtime = PhoneNotificationRuntime({ choices.read().map { it.packageName }.toSet() },
            { changed -> ChosenApps.observe(actual.context, changed) }, { NotificationAccessApproval.GRANTED },
            { work, complete -> complete(work()) }, { it() }, owner)
        runtime.connected(connection) { NotificationSnapshot.Available(emptyList()) }
        Handler.flush()
    }
    fun post(): NotificationIngressObservation = checkNotNull(runtime.posted(connection, pkg) { NotificationRecord(pkg, "synthetic-key", ++time) })
    fun goalReady() {
        actual.lane.submit {}.get(2, TimeUnit.SECONDS)
        actual.report(ReceiverUsabilityMode.PROJECTED)
        Handler.flush()
    }
    fun freshReady() {
        Shizuku.available = false; actual.bootstrap.refresh(); actual.hostLane.submit {}.get(2, TimeUnit.SECONDS); Handler.flush()
        Shizuku.available = true; actual.bootstrap.refresh(); actual.hostLane.submit {}.get(2, TimeUnit.SECONDS); Handler.flush()
        goalReady()
    }
    override fun close() {
        SharedPreferences.beforeRead = null; SharedPreferences.beforeApply = null; SharedPreferences.deferListeners = false
        owner.close(); runtime.disconnected(connection); SharedPreferences.flush()
        // Resolve this fixture's explicit failed-write indicators independently;
        // the process policy is shared with the actual canonical adapters.
        preferences.setEnabled(pkg, preferences.isEnabled(pkg)); choices.add(InstalledApp(pkg, pkg))
        // Accepted committed work can enqueue restore feedback after its launch
        // settlement. Drain that actual work before terminating boundary executors.
        val coordinator = field(actual.sessions, "appScales").get(actual.sessions)
        await("fixture actual scale lanes settled before executor teardown") {
            val lanes = field(coordinator, "lanes").get(coordinator) as Map<*, *>
            lanes.values.all { lane -> lane!!; field(lane, "active").get(lane) == null &&
                (field(lane, "queue").get(lane) as Collection<*>).isEmpty() }
        }
        actual.close()
    }
}

/** Explicit delivery/settlement boundary only; G/D admission and Ready facts are actual source. */
private class ControlledPort(private val actual: FacadeNotificationPort) : NotificationSelectionPort {
    override val facade get() = actual.facade
    data class Pending(val carrier: NotificationLaunchCarrier, val settled: (NotificationLaunchSettlement) -> Unit)
    private class Registration(val handle: ActivationHandle, val receive: (ActivationReady<ProjectionContext>) -> Unit) {
        var closed = false; var fired = false
    }
    private val registrations = mutableListOf<Registration>()
    private var latest: ActivationState<ProjectionContext>? = null
    val sent = mutableListOf<Pending>()
    var arms = 0
    var detached = 0
    var beforeArmReturn: (() -> Unit)? = null
    var duringClose: (() -> Unit)? = null
    var duringSubmit: ((Pending) -> Unit)? = null
    var throwAfterSubmit = false
    var throwBeforeArmReturn = false
    override fun admit(selection: NotificationSelection, snapshot: NotificationPolicy.Snapshot) = actual.admit(selection, snapshot)
    override fun observe(handle: ActivationHandle, receive: (ActivationState<ProjectionContext>) -> Unit) = actual.observe(handle) { state ->
        latest = state; receive(state)
        registrations.toList().forEach(::deliver)
    }
    private fun deliver(registration: Registration) {
        val state = latest ?: return
        if (!registration.closed && !registration.fired && state.handle === registration.handle && state.phase == ActivationPhase.READY &&
            state.attempt != null && state.target != null && state.binding != null && state.authority != 0L) {
            registration.fired = true
            registration.receive(ActivationReady(state.handle, state.attempt, state.target, state.binding, state.authority))
        }
    }
    override fun ready(handle: ActivationHandle, receive: (ActivationReady<ProjectionContext>) -> Unit): Closeable {
        check(registrations.none { !it.closed }) { "successor arm registered before predecessor detached" }
        arms++
        val registration = Registration(handle, receive)
        registrations.add(registration); deliver(registration)
        beforeArmReturn?.invoke()
        if (throwBeforeArmReturn) error("synthetic registration return lost")
        return Closeable { if (!registration.closed) { registration.closed = true; detached++; duringClose?.invoke() } }
    }
    override fun submit(carrier: NotificationLaunchCarrier, settled: (NotificationLaunchSettlement) -> Unit) {
        val pending = Pending(carrier, settled)
        sent.add(pending); duringSubmit?.invoke(pending)
        if (throwAfterSubmit) error("synthetic escaped invocation")
    }
    fun settle(pending: Pending, outcome: NotificationDispatchOutcome) = pending.settled(NotificationLaunchSettlement(pending.carrier, outcome))
    fun lateReady(index: Int, ready: ActivationReady<ProjectionContext>) = registrations[index].receive(ready)
}

private fun policyRegressions() {
    NotificationFixture().use { f ->
        var once = true
        val reentry = f.policy.observe { if (once) { once = false; f.preferences.setEnabled(f.pkg, true) } }
        f.preferences.setEnabled(f.pkg, false)
        reentry.close()
        check(f.preferences.isEnabled(f.pkg)) { "superseded Off persisted after reentrant On" }
        println("PASS C56-01 actual canonical preference begin reentry cannot persist superseded Off after On")
    }
    NotificationFixture().use { f ->
        var once = true
        val reentry = f.policy.observe { if (once) { once = false; f.choices.add(InstalledApp(f.pkg, f.pkg)) } }
        f.choices.remove(f.pkg)
        reentry.close()
        check(f.choices.read().any { it.packageName == f.pkg }) { "superseded remove persisted after reentrant add" }
        println("PASS C56-01 actual canonical chosen remove reentry cannot erase newer redundant add")
    }
    NotificationFixture().use { f ->
        check(f.preferences.isEnabled(f.pkg)) // Missing saved preference: default On.
        f.post(); val original = selected(f)
        SharedPreferences.beforeApply = { error("synthetic write failure") }
        check(runCatching { f.preferences.setEnabled(f.pkg, true) }.isFailure)
        SharedPreferences.beforeApply = null
        f.post()
        check(original.permit.state == LaunchCommitPermit.State.RETIRED && selected(f) !== original)
        check(field(f.actual.sessions, "currentDemand").get(f.actual.sessions) != null) { "failed redundant On denied NEW default-On event" }
        println("PASS C56-02 failed redundant/default On re-reads actual eligibility for NEW G without reviving retired G")
    }
    NotificationFixture().use { f ->
        f.post(); val original = selected(f)
        SharedPreferences.beforeRead = { if (it == "ordered_apps") error("synthetic chosen read failure") }
        check(runCatching { f.choices.add(InstalledApp(f.pkg, f.pkg)) }.isFailure)
        SharedPreferences.beforeRead = null
        f.post()
        check(original.permit.state == LaunchCommitPermit.State.RETIRED && selected(f) !== original)
        check(field(f.actual.sessions, "currentDemand").get(f.actual.sessions) != null) { "failed redundant add denied existing membership" }
        println("PASS C56-02 failed redundant add preserves NEW actual existing-membership eligibility")
    }
    NotificationFixture().use { f ->
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        var first = true
        val observation = f.policy.observe {
            if (first && Thread.currentThread().name == "old-policy-writer") {
                first = false; entered.countDown(); check(release.await(4, TimeUnit.SECONDS))
            }
        }
        val writer = CheckedThread({ f.preferences.setEnabled(f.pkg, false) }, "old-policy-writer")
        writer.start(); check(entered.await(2, TimeUnit.SECONDS))
        f.preferences.setEnabled(f.pkg, true)
        release.countDown(); writer.finish()
        observation.close(); check(f.preferences.isEnabled(f.pkg))
        println("PASS C56-01 actual older writer delayed before I/O cannot overwrite newer canonical On")
    }
    for (mutation in listOf("Off", "remove")) NotificationFixture().use { f ->
        f.post(); val original = selected(f)
        if (mutation == "Off") SharedPreferences.beforeApply = { error("synthetic write failure") }
        else SharedPreferences.beforeRead = { if (it == "ordered_apps") error("synthetic read failure") }
        check(runCatching {
            if (mutation == "Off") f.preferences.setEnabled(f.pkg, false) else f.choices.remove(f.pkg)
        }.isFailure)
        SharedPreferences.beforeApply = null; SharedPreferences.beforeRead = null
        check(original.permit.state == LaunchCommitPermit.State.RETIRED)
        f.post(); val replacement = selected(f)
        check(replacement !== original && replacement.handle !== original.handle && replacement.permit !== original.permit)
        check(replacement.permit.state == LaunchCommitPermit.State.PENDING)
        println("PASS C56-02 failed revoking $mutation keeps old G retired; NEW receipt uses actual saved eligibility and distinct D/permit")
    }
    NotificationFixture().use { f ->
        f.post(); val original = selected(f)
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val newer = CountDownLatch(1)
        SharedPreferences.beforeRead = { key -> if (key == "ordered_apps" && Thread.currentThread().name == "old-remove-reader") {
            entered.countDown(); check(release.await(4, TimeUnit.SECONDS))
        } }
        val removing = CheckedThread({ f.choices.remove(f.pkg) }, "old-remove-reader")
        removing.start(); check(entered.await(2, TimeUnit.SECONDS))
        check(original.permit.state == LaunchCommitPermit.State.RETIRED) { "remove intent did not revoke before held read" }
        val observed = f.policy.observe { if (Thread.currentThread().name == "new-add-writer") newer.countDown() }
        val adding = CheckedThread({ f.choices.add(InstalledApp(f.pkg, f.pkg)) }, "new-add-writer")
        adding.start(); check(newer.await(2, TimeUnit.SECONDS)); release.countDown()
        removing.finish(); adding.finish()
        observed.close(); SharedPreferences.beforeRead = null
        check(f.choices.read().any { it.packageName == f.pkg })
        f.post(); check(selected(f) !== original && original.permit.state == LaunchCommitPermit.State.RETIRED)
        println("PASS C56-01 remove revokes before held storage read; later add intent supersedes older writer waiting in same store")
    }
    NotificationFixture().use { f ->
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val newer = CountDownLatch(1)
        SharedPreferences.beforeApply = { if (Thread.currentThread().name == "old-off-in-apply") {
            entered.countDown(); check(release.await(4, TimeUnit.SECONDS))
        } }
        val old = CheckedThread({ f.preferences.setEnabled(f.pkg, false) }, "old-off-in-apply")
        old.start(); check(entered.await(2, TimeUnit.SECONDS))
        val observed = f.policy.observe { if (Thread.currentThread().name == "new-on-writer") newer.countDown() }
        val next = CheckedThread({ f.preferences.setEnabled(f.pkg, true) }, "new-on-writer")
        next.start(); check(newer.await(2, TimeUnit.SECONDS)); release.countDown()
        old.finish(); next.finish()
        observed.close(); SharedPreferences.beforeApply = null
        check(f.preferences.isEnabled(f.pkg))
        println("PASS C56-01 older in-progress apply drains before newer On writer; final canonical value follows newer intent")
    }
    NotificationFixture().use { f ->
        f.preferences.setEnabled(f.pkg, true); f.post(); val original = selected(f)
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val revoked = CountDownLatch(1)
        SharedPreferences.deferListeners = true
        SharedPreferences.beforeApply = { if (Thread.currentThread().name == "old-on-in-apply") {
            entered.countDown(); check(release.await(4, TimeUnit.SECONDS))
        } }
        val old = CheckedThread({ f.preferences.setEnabled(f.pkg, true) }, "old-on-in-apply")
        old.start(); check(entered.await(2, TimeUnit.SECONDS))
        val observed = f.policy.observe { if (Thread.currentThread().name == "new-off-writer") revoked.countDown() }
        val off = CheckedThread({ f.preferences.setEnabled(f.pkg, false) }, "new-off-writer")
        off.start(); check(revoked.await(2, TimeUnit.SECONDS))
        check(f.preferences.isEnabled(f.pkg) && original.permit.state == LaunchCommitPermit.State.RETIRED)
        val demand = field(f.actual.sessions, "currentDemand").get(f.actual.sessions)
        f.post(); check(field(f.actual.sessions, "currentDemand").get(f.actual.sessions) === demand)
        release.countDown(); old.finish(); off.finish(); observed.close()
        SharedPreferences.beforeApply = null; SharedPreferences.deferListeners = false; SharedPreferences.flush()
        check(!f.preferences.isEnabled(f.pkg))
        println("PASS pending authoritative Off rejects NEW G despite saved On and deferred preference observers while older apply is held")
    }
    for (kind in listOf("preference", "membership")) NotificationFixture().use { f ->
        var once = true
        val observation = if (kind == "preference") AppNotificationPreferences.observe(f.actual.context) {
            if (once) { once = false; f.preferences.setEnabled(f.pkg, true) }
        } else ChosenApps.observe(f.actual.context) {
            if (once) { once = false; f.choices.add(InstalledApp(f.pkg, f.pkg)) }
        }
        if (kind == "preference") f.preferences.setEnabled(f.pkg, false) else f.choices.remove(f.pkg)
        observation.close()
        check(f.preferences.isEnabled(f.pkg) && f.choices.read().any { it.packageName == f.pkg })
        f.post(); check(selected(f).permit.state == LaunchCommitPermit.State.PENDING)
        println("PASS canonical $kind listener reentry after actual memory commit preserves newer On/add and fresh admission")
    }
}

private fun realPipeline() {
    NotificationFixture().use { f ->
        f.post(); f.goalReady()
        await("actual original notification app launch") { f.actual.service.launches.contains(f.pkg) }
        await("actual selection committed settlement") { f.owner.lastOutcome?.commitment == NotificationCommitment.COMMITTED }
        check(f.actual.service.launches.count { it == f.pkg } == 1)
        println("PASS actual facade+canonical stores+runtime+selection+scale+Bootstrap reaches original typed claim and Binder once")
    }
    for (mutation in listOf("Off", "remove", "same-package-choice", "new-G")) NotificationFixture().use { f ->
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val display = field(f.actual.bootstrap, "displayExecutor").get(f.actual.bootstrap) as ExecutorService
        display.submit { entered.countDown(); check(release.await(4, TimeUnit.SECONDS)) }
        check(entered.await(2, TimeUnit.SECONDS))
        f.post(); f.goalReady()
        check(f.actual.service.launches.none { it == f.pkg })
        when (mutation) {
            "Off" -> { f.preferences.setEnabled(f.pkg, false); f.preferences.setEnabled(f.pkg, true) }
            "remove" -> { f.choices.remove(f.pkg); f.choices.add(InstalledApp(f.pkg, f.pkg)) }
            "same-package-choice" -> f.actual.sessions.launchApp(f.pkg, f.actual.service.context) {}
            "new-G" -> f.post()
        }
        release.countDown()
        display.submit {}.get(2, TimeUnit.SECONDS); Handler.flush()
        if (mutation == "same-package-choice") await("manual successor actual launch") { f.actual.service.launches.count { it == f.pkg } == 1 }
        else check(f.actual.service.launches.none { it == f.pkg })
        println("PASS actual second display queue preclaim $mutation invalidates original carrier; On/readd never resurrects it")
    }
    NotificationFixture().use { f ->
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        f.actual.service.beforeLaunch = { entered.countDown(); check(release.await(4, TimeUnit.SECONDS)); error("synthetic committed Binder failure") }
        f.post(); f.goalReady(); check(entered.await(2, TimeUnit.SECONDS))
        f.preferences.setEnabled(f.pkg, false)
        release.countDown()
        (field(f.actual.bootstrap, "displayExecutor").get(f.actual.bootstrap) as ExecutorService).submit {}.get(2, TimeUnit.SECONDS)
        Handler.flush(); check(f.actual.service.launches.none { it == f.pkg })
        check(f.owner.lastOutcome?.commitment == NotificationCommitment.COMMITTED &&
            f.owner.lastOutcome?.faultStage == NotificationFaultStage.BINDER && f.owner.failed)
        // No retry from a committed operation whose effect/result is ambiguous.
        f.actual.service.beforeLaunch = null
        f.goalReady(); Handler.flush(); check(f.actual.service.launches.none { it == f.pkg })
        println("PASS actual postclaim Binder exception and Off never trigger notification resubmission")
    }
    for (failure in listOf("decode", "feedback", "decode-and-feedback")) NotificationFixture().use { f ->
        val observation = if (failure.contains("feedback")) f.actual.sessions.appScales.observe { if (it != null) error("synthetic feedback failure") } else null
        if (failure.startsWith("decode")) f.actual.service.displayPayloadOverride = ByteArray(3)
        f.post(); f.goalReady()
        await("committed $failure outcome") { f.owner.lastOutcome?.commitment == NotificationCommitment.COMMITTED && f.owner.lastOutcome?.faultStage != null }
        check(f.owner.lastOutcome?.faultStage == if (failure.startsWith("decode")) NotificationFaultStage.DECODE else NotificationFaultStage.FEEDBACK)
        check(f.owner.failed && f.actual.service.launches.count { it == f.pkg } == 1)
        observation?.close(); f.actual.service.displayPayloadOverride = null
        f.freshReady(); check(f.actual.service.launches.count { it == f.pkg } == 1)
        println("PASS committed $failure/null feedback remains visible after terminal settlement; fresh Ready never resubmits committed G")
    }
    NotificationFixture().use { f ->
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        f.actual.service.beforeScale = { entered.countDown(); check(release.await(4, TimeUnit.SECONDS)) }
        f.actual.sessions.launchApp("example.manual-scale", f.actual.service.context) {}
        await("actual original restore scale work entered") { entered.count == 0L }
        f.post(); f.goalReady()
        check(f.actual.service.launches.none { it == f.pkg })
        val original = selected(f)
        f.actual.service.beforeScale = null; release.countDown()
        await("notification drains after old original scale callback without new Ready") { f.actual.service.launches.contains(f.pkg) }
        check(original.permit.state == LaunchCommitPermit.State.COMMITTED)
        println("PASS actual first coordinator queue retains G through old restore/no-op feedback; original Ready dispatches after actual lane release")
    }
}

private fun receiptBeforeRead() {
    NotificationFixture().use { f ->
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        f.actual.lane.submit { entered.countDown(); check(release.await(4, TimeUnit.SECONDS)) }
        check(entered.await(2, TimeUnit.SECONDS))
        var once = true
        SharedPreferences.beforeRead = { key -> if (key == "ordered_apps" && once) { once = false; f.actual.sessions.stop() } }
        f.post()
        SharedPreferences.beforeRead = null
        check(f.actual.sessions.activationId() == null) { "receipt adopted post-Stop authority after chosen read" }
        f.post()
        check(f.actual.sessions.activationId() != null) { "fresh post-Stop receipt failed" }
        release.countDown()
        println("PASS C56-10 actual receipt ticket captured before chosen read prevents post-Stop H adoption; fresh receipt works")
    }
    NotificationFixture().use { f ->
        var once = true
        SharedPreferences.beforeRead = { key -> if (key == "ordered_apps" && once) {
            once = false; f.preferences.setEnabled("example.unrelated", false)
        } }
        f.post(); SharedPreferences.beforeRead = null
        check(selected(f).packageName == f.pkg)
        println("PASS unrelated-package policy intent during first chosen read does not discard this original receipt")
    }
}

private fun ownershipRecovery() {
    lateinit var port: ControlledPort
    NotificationFixture({ ControlledPort(it).also { value -> port = value } }).use { f ->
        f.post(); f.goalReady(); check(port.sent.size == 1)
        val first = port.sent.single()
        port.settle(first, NotificationDispatchOutcome.fault(first.carrier, NotificationFaultStage.ENQUEUE, "SYNTHETIC_BOUNDARY"))
        repeat(3) { f.policy.publish(); Handler.flush() }
        check(port.sent.size == 1 && port.arms == 1 && f.owner.failed)
        f.freshReady()
        check(port.sent.size == 2)
        val second = port.sent.last()
        check(second.carrier.selection === first.carrier.selection && second.carrier.permit === first.carrier.permit &&
            second.carrier.ready.handle === first.carrier.ready.handle && second.carrier.ready.authority != first.carrier.ready.authority)
        check(first.carrier.permit.state == LaunchCommitPermit.State.PENDING)
        port.lateReady(0, first.carrier.ready); check(port.sent.size == 2)
        println("PASS C56-13 settled fault has no same-Ready loop; real producer fresh same-D authority rearms SAME G/D/permit; late R1 cannot clobber R2")
    }
    NotificationFixture({ ControlledPort(it).also { value -> port = value } }).use { f ->
        f.post(); f.goalReady(); val first = port.sent.single()
        f.freshReady(); check(port.sent.size == 1)
        port.beforeArmReturn = { if (port.arms == 2) check(port.sent.size == 1 && port.detached == 1) }
        port.settle(first, NotificationDispatchOutcome.fault(first.carrier, NotificationFaultStage.COORDINATOR, "SYNTHETIC_SETTLED"))
        check(port.sent.size == 2 && port.arms == 2 && port.detached == 2)
        check(port.sent.last().carrier.ready.authority != first.carrier.ready.authority)
        println("PASS C56-11/13 newer real Ready held during S1 drain survives; inline R2 callback waits for its own registration return+detach")
    }
    NotificationFixture({ ControlledPort(it).also { value -> port = value } }).use { f ->
        port.duringSubmit = { pending -> if (port.sent.size == 1) {
            f.freshReady()
            port.settle(pending, NotificationDispatchOutcome.fault(pending.carrier, NotificationFaultStage.COORDINATOR, "SYNTHETIC_SETTLED"))
            check(port.sent.size == 1) { "S2 began before S1 invocation return" }
        } }
        f.post(); f.goalReady(); check(port.sent.size == 2)
        println("PASS C56-11 actual fresh Ready and inline matching settlement cannot release S1 until submit invocation returns")
    }
    NotificationFixture({ ControlledPort(it).also { value -> port = value } }).use { f ->
        port.throwAfterSubmit = true
        f.post(); f.goalReady(); val first = port.sent.single()
        check(f.owner.lastOutcome?.faultCode == "SUBMISSION_UNRESOLVED")
        f.freshReady(); check(port.sent.size == 1)
        port.throwAfterSubmit = false
        port.settle(first, NotificationDispatchOutcome.fault(first.carrier, NotificationFaultStage.COORDINATOR, "ACTUAL_BOUNDARY_SETTLEMENT"))
        check(port.sent.size == 2 && port.sent.last().carrier.selection === first.carrier.selection)
        println("PASS escaped submit retains original S despite newer Ready until matching boundary settlement actually arrives")
    }
    for (boundary in listOf("changed", "close")) NotificationFixture({ ControlledPort(it).also { value -> port = value } }).use { f ->
        f.post()
        var nested: NotificationIngressObservation? = null
        val reenter = { if (nested == null) { nested = f.post() }; Unit }
        if (boundary == "changed") f.changedHook = { f.changedHook = null; reenter() }
        else port.duringClose = { port.duringClose = null; reenter() }
        f.post()
        val winner = selected(f)
        val owned = field(f.owner, "current").get(f.owner)!!
        check(winner.observation.receipt == checkNotNull(nested).receipt && field(owned, "selection").get(owned) === winner)
        check(winner.permit.state == LaunchCommitPermit.State.PENDING && port.arms == 2)
        println("PASS C56-14 $boundary reentry admits newer B; outer A cannot overwrite its original receipt/selection ownership")
    }
    NotificationFixture({ ControlledPort(it).also { value -> port = value } }).use { f ->
        port.throwBeforeArmReturn = true
        f.post(); f.goalReady(); f.freshReady()
        check(port.arms == 1 && port.sent.isEmpty() && f.owner.failed)
        println("PASS unresolved registration return reports fault and retains original R; fresh Ready cannot invent cleanup or another arm")
    }
    NotificationFixture({ ControlledPort(it).also { value -> port = value } }).use { f ->
        val observed = f.post(); val original = selected(f)
        f.runtime.disconnected(f.connection)
        check(original.permit.state == LaunchCommitPermit.State.PENDING)
        val replacement = NotificationListenerConnection()
        f.runtime.connected(replacement) { NotificationSnapshot.Available(listOf(observed.event.record)) }
        f.runtime.removed(replacement, f.pkg) { observed.event.record }
        check(original.permit.state == LaunchCommitPermit.State.PENDING && selected(f) === original)
        f.goalReady(); check(port.sent.single().carrier.selection === original)
        f.runtime.disconnected(replacement)
        println("PASS transient ingress disconnect retains original G; equal metadata/removal in replacement baseline cannot prove cross-gap native removal")
    }
    NotificationFixture({ ControlledPort(it).also { value -> port = value } }).use { f ->
        f.post(); val original = selected(f)
        f.owner.access(NotificationAccessApproval.DENIED); f.owner.access(NotificationAccessApproval.GRANTED)
        check(original.permit.state == LaunchCommitPermit.State.RETIRED)
        f.post(); check(selected(f) !== original)
        println("PASS actual approval revocation retires old permit; restored approval permits only a new receipt")
    }
}

private fun coordinatorOwnership() {
    lateinit var port: ControlledPort
    NotificationFixture({ ControlledPort(it).also { value -> port = value } }).use { f ->
        f.post(); f.goalReady(); val original = port.sent.single().carrier
        val callbacks = mutableListOf<(NotificationDispatchOutcome) -> Unit>()
        var behavior = "hold"
        val backend = object : AppScaleBackend {
            override fun launchNotification(carrier: NotificationLaunchCarrier, complete: (NotificationDispatchOutcome) -> Unit) {
                callbacks.add(complete)
                if (behavior == "throw") error("synthetic backend throw")
                if (behavior == "callback-throw") { complete(NotificationDispatchOutcome.rejected(carrier)); error("synthetic postcallback throw") }
            }
            override fun launch(packageName: String, expected: ProjectionContext, complete: (ProjectionDisplayState?) -> Unit) = error("out of scope")
            override fun display(expected: ProjectionContext, complete: (ProjectionDisplayState?) -> Unit) = complete(null)
            override fun request(value: Double, expected: ProjectionContext, complete: (ScaleState?) -> Unit) = complete(null)
            override fun read(expected: ProjectionContext, complete: (ScaleState?) -> Unit) = complete(null)
        }
        val coordinator = AppScaleCoordinator(AppScaleStore(object : AppScaleStorage {
            override fun read(packageName: String): Double? = null
            override fun write(packageName: String, scale: Double) = true
        }), backend)
        val second = NotificationLaunchCarrier(original.selection, original.ready, Any())
        val third = NotificationLaunchCarrier(original.selection, original.ready, Any())
        val settlements = mutableListOf<NotificationLaunchSettlement>()
        coordinator.launchNotification(original) { settlements.add(it) }
        coordinator.launchNotification(second) { settlements.add(it) }
        check(callbacks.size == 1)
        callbacks[0](NotificationDispatchOutcome.rejected(original))
        check(callbacks.size == 2 && settlements.map { it.carrier } == listOf(original))
        callbacks[0](NotificationDispatchOutcome.rejected(original))
        coordinator.launchNotification(third) { settlements.add(it) }
        check(callbacks.size == 2) { "duplicate S1 callback released active S2" }
        callbacks[1](NotificationDispatchOutcome.rejected(second)); check(callbacks.size == 3)
        callbacks[2](NotificationDispatchOutcome.rejected(third))
        check(settlements.map { it.carrier } == listOf(original, second, third))
        behavior = "callback-throw"
        coordinator.launchNotification(original) { settlements.add(it) }
        check(settlements.size == 4)
        behavior = "throw"
        coordinator.launchNotification(second) { settlements.add(it) }
        check(settlements.size == 5 && settlements.last().outcome.commitment == NotificationCommitment.ADAPTER_FAULT)
        behavior = "hold"
        coordinator.launchNotification(original) { settlements.add(it) }
        coordinator.launchNotification(second) { settlements.add(it) }
        coordinator.contextChanged(ProjectionContext("replacement", "replacement"))
        check(settlements.count { it.carrier === second } == 3)
        callbacks.last()(NotificationDispatchOutcome.rejected(original))
        check(settlements.size == 7)
        println("PASS actual coordinator Work identity/once finish prevents duplicate S1 releasing S2; callback-throw/backend-throw/queued context cancellation settle exactly once")
    }
}

private fun adoptedMergeComposition() {
    lateinit var port: ControlledPort
    NotificationFixture({ ControlledPort(it).also { value -> port = value } }).use { f ->
        f.post(); f.goalReady(); val ready = port.sent.single().carrier.ready
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        var selectedEffect: (() -> Unit)? = null
        val selectedTurn = f.actual.lane.submit { entered.countDown(); check(release.await(4, TimeUnit.SECONDS)); selectedEffect?.invoke() }
        check(entered.await(2, TimeUnit.SECONDS))
        val sessions = f.actual.sessions
        val authority = field(sessions, "contextAuthority").get(sessions) as ActivationContextAuthority
        val revision = field(sessions, "localRevision").get(sessions) as java.util.concurrent.atomic.AtomicLong
        val generic = (field(sessions, "currentHandle").get(sessions) as java.util.concurrent.atomic.AtomicReference<*>).get() as ActivationHandle
        val original = synchronized(authority.lock) {
            HidInputAuthority(ready.handle, ready.attempt.id, ready.target, authority.version, revision.get(), 0)
        }
        // Constructed already-admitted primitive loss obligation, not a native
        // sample/admission result. Only the saved helper/actor composition is tested.
        val loss = HidWakeRequest(PhoneHidDisposition.NONE, original, "synthetic-region", 4L, admitted = true)
        val type = sessions.javaClass.declaredClasses.single { it.simpleName == "LocalRequest" }
        val constructor = type.getDeclaredConstructor(ActivationHandle::class.java, ActivationCause::class.java,
            String::class.java, HidWakeRequest::class.java).apply { isAccessible = true }
        val enqueue = sessions.javaClass.getDeclaredMethod("enqueueLocalRequestLocked", type).apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val pending = field(sessions, "pendingRequest").get(sessions) as java.util.concurrent.atomic.AtomicReference<Any?>
        fun offer(wake: HidWakeRequest) = synchronized(authority.lock) {
            enqueue.invoke(sessions, constructor.newInstance(generic, ActivationCause.HID_PRESS, null, wake))
        }
        fun checkPending(handle: ActivationHandle) {
            val value = checkNotNull(pending.get())
            check(field(value, "handle").get(value) === handle && field(value, "hidWake").get(value) === loss)
        }
        offer(loss); checkPending(ready.handle) // Actual D stays selected with an empty/in-flight slot.
        offer(HidWakeRequest(PhoneHidDisposition.UNKNOWN, original, admitted = true)); checkPending(ready.handle)
        // Control the real selected-request boundary: D1 is dequeued, then D2
        // becomes current+pending before the actual D1 effect method runs.
        val dequeued = synchronized(authority.lock) { checkNotNull(pending.getAndSet(null)) }
        f.post(); val next = selected(f).handle!!; check(next !== ready.handle)
        val engine = field(sessions, "activation").get(sessions) as PhoneActivationEngine
        val beforeWake = engine.journal.current!!.wakeRevision
        val apply = sessions.javaClass.getDeclaredMethod("applyLocalRequest", type).apply { isAccessible = true }
        selectedEffect = {
            apply.invoke(sessions, dequeued)
            check(engine.journal.current!!.demandId == ready.handle.demandId && engine.journal.current!!.wakeRevision == beforeWake + 1)
            check(field(checkNotNull(pending.get()), "handle").get(pending.get()) === next)
        }
        release.countDown(); selectedTurn.get(2, TimeUnit.SECONDS); f.actual.lane.submit {}.get(2, TimeUnit.SECONDS); Handler.flush()
        check(f.actual.sessions.isActivationCurrent(next))
        check(engine.journal.current!!.demandId == next.demandId && f.actual.service.launches.none { it == f.pkg })
        val entered2 = CountDownLatch(1); val release2 = CountDownLatch(1)
        f.actual.lane.submit { entered2.countDown(); check(release2.await(4, TimeUnit.SECONDS)) }
        check(entered2.await(2, TimeUnit.SECONDS))
        f.actual.sessions.stop(); f.post(); val afterStop = selected(f).handle!!
        offer(loss)
        val replacement = checkNotNull(pending.get())
        check(field(replacement, "handle").get(replacement) === afterStop && field(replacement, "hidWake").get(replacement) == null)
        release2.countDown()
        println("PASS adopted63 exact helpers+actual65 wrappers+normal actor skip retain D2 while dequeued D1 applies original H loss/wake; NONE beats UNKNOWN and Stop blocks old H transfer; no target replay")
    }
}

fun main() {
    policyRegressions()
    realPipeline()
    receiptBeforeRead()
    ownershipRecovery()
    coordinatorOwnership()
    adoptedMergeComposition()
    val junit = org.junit.runner.JUnitCore.runClasses(NotificationEventsTest::class.java, NotificationIngressRuntimeTest::class.java,
        ChosenAppStoreTest::class.java, AppScaleCoordinatorTest::class.java)
    junit.failures.forEach { println(it.trace) }
    check(junit.wasSuccessful())
    println("PASS actual existing metadata/runtime/chosen/coordinator JUnit cases=${junit.runCount} failures=${junit.failureCount}")
    println("RESULT notification-selection actual-source scenarios complete")
}
