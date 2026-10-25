package com.code2hack.eyeprojector.directhost.notifications.pipelinetest

import android.os.Handler
import android.os.Looper
import com.code2hack.eyeprojector.directhost.*
import com.code2hack.eyeprojector.directhost.activation.*
import com.code2hack.eyeprojector.directhost.activation.producer.Fixture
import com.code2hack.eyeprojector.directhost.apps.*
import com.code2hack.eyeprojector.directhost.notifications.*
import com.code2hack.eyeprojector.protocol.ReceiverUsabilityMode
import java.io.Closeable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

private fun field(value: Any, name: String) = value.javaClass.getDeclaredField(name).apply { isAccessible = true }
private val next = AtomicInteger()

/** Delivery/settlement boundary only. Admission, types, Ready facts and owner remain actual. */
private class Port(private val actual: FacadeNotificationPort, val controlled: Boolean) : NotificationSelectionPort {
    override val facade get() = actual.facade
    data class Pending(val carrier: NotificationLaunchCarrier, val settle: (NotificationLaunchSettlement) -> Unit)
    class Registration(val handle: ActivationHandle, val receive: (ActivationReady<ProjectionContext>) -> Unit) {
        var ready: ActivationReady<ProjectionContext>? = null
        var closes = 0
    }
    val sent = mutableListOf<Pending>()
    val registrations = mutableListOf<Registration>()
    var inSubmit = false
    var inlineReady = false
    var inlineSettlement = false
    var beforeReadyReturn: ((ActivationHandle) -> Unit)? = null
    var duringSubmit: ((Pending) -> Unit)? = null
    override fun admit(selection: NotificationSelection, snapshot: NotificationPolicy.Snapshot) = actual.admit(selection, snapshot)
    override fun observe(handle: ActivationHandle, receive: (ActivationState<ProjectionContext>) -> Unit) = actual.observe(handle, receive)
    override fun ready(handle: ActivationHandle, receive: (ActivationReady<ProjectionContext>) -> Unit): Closeable {
        val original = Registration(handle, receive).also(registrations::add)
        val registration = actual.ready(handle) { ready -> original.ready = ready; receive(ready) }
        if (inlineReady) Handler.flush()
        beforeReadyReturn?.invoke(handle)
        return Closeable { original.closes++; registration.close() }
    }
    override fun submit(carrier: NotificationLaunchCarrier, settled: (NotificationLaunchSettlement) -> Unit) {
        inSubmit = true
        try {
            if (!controlled) actual.submit(carrier, settled)
            else {
                val original = Pending(carrier, settled).also(sent::add)
                if (inlineSettlement) settle(original)
                duringSubmit?.invoke(original)
            }
        } finally { inSubmit = false }
    }
    fun settle(original: Pending, outcome: NotificationDispatchOutcome = NotificationDispatchOutcome.rejected(original.carrier)) =
        original.settle(NotificationLaunchSettlement(original.carrier, outcome))
}

/** Observes actual executor acceptance; delegates all queueing/execution without a model. */
private class EnqueueObserver(private val actual: ExecutorService) : AbstractExecutorService() {
    val accepted = AtomicInteger()
    override fun execute(command: Runnable) { actual.execute(command); accepted.incrementAndGet() }
    override fun shutdown() = actual.shutdown()
    override fun shutdownNow(): MutableList<Runnable> = actual.shutdownNow()
    override fun isShutdown() = actual.isShutdown
    override fun isTerminated() = actual.isTerminated
    override fun awaitTermination(timeout: Long, unit: TimeUnit) = actual.awaitTermination(timeout, unit)
}

private class TestFixture(val inlinePost: Boolean = false, controlled: Boolean = true, enforceNoObserverPosts: Boolean = false) : AutoCloseable {
    val actual = Fixture()
    val policy = NotificationPolicy.process
    val pkg = "example.pipeline${next.incrementAndGet()}"
    val choices = ChosenApps.open(actual.context)
    val port = Port(FacadeNotificationPort(actual.sessions), controlled)
    private val deliveries = ArrayDeque<() -> Unit>()
    private val expectedPolicyPosts = AtomicInteger()
    private val policyScheduleProbe = if (enforceNoObserverPosts) policy.observe { expectedPolicyPosts.incrementAndGet() } else null
    var unexpectedObserverPosts = 0; private set
    val owner: NotificationSelectionOwner
    val runtime: PhoneNotificationRuntime
    val connection = NotificationListenerConnection()
    private var time = 0L
    init {
        choices.add(InstalledApp(pkg, pkg))
        expectedPolicyPosts.set(0) // No owner subscription existed during initial membership setup.
        owner = NotificationSelectionOwner(port, policy,
            { packageName -> choices.read().any { it.packageName == packageName } to AppNotificationPreferences.open(actual.context).isEnabled(packageName) },
            { work ->
                if (enforceNoObserverPosts && expectedPolicyPosts.getAndDecrement() <= 0) {
                    unexpectedObserverPosts++
                    error("no-subscriber path scheduled extra pipeline observation work")
                }
                if (inlinePost) work() else deliveries.addLast(work)
            })
        runtime = PhoneNotificationRuntime({ choices.read().map { it.packageName }.toSet() },
            { changed -> ChosenApps.observe(actual.context, changed) }, { NotificationAccessApproval.GRANTED },
            { work, complete -> complete(work()) }, { it() }, owner)
        runtime.connected(connection) { NotificationSnapshot.Available(emptyList()) }
        pump()
    }
    fun post() = checkNotNull(runtime.posted(connection, pkg) { NotificationRecord(pkg, "synthetic-platform-key", ++time) })
    fun pump() {
        Handler.flush()
        while (deliveries.isNotEmpty()) deliveries.removeFirst()()
        Handler.flush()
    }
    fun ready() {
        actual.lane.submit {}.get(2, TimeUnit.SECONDS)
        actual.report(ReceiverUsabilityMode.PROJECTED)
        pump()
    }
    fun waitFor(message: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4)
        while (!condition() && System.nanoTime() < deadline) { pump(); Thread.yield() }
        check(condition()) { message }
    }
    override fun close() {
        owner.close()
        policyScheduleProbe?.close()
        port.sent.toList().forEach { port.settle(it) }
        runtime.disconnected(connection)
        pump()
        waitFor("actual coordinator did not release fixture work") {
            val lanes = field(actual.sessions.appScales, "lanes").get(actual.sessions.appScales) as Map<*, *>
            lanes.values.all { lane -> lane!!; field(lane, "active").get(lane) == null && (field(lane, "queue").get(lane) as Collection<*>).isEmpty() }
        }
        actual.close()
    }
}

private fun checkCase(name: String, work: () -> Unit) {
    work()
    println("PASS $name")
}

fun main() {
    var count = 0
    fun scenario(name: String, work: () -> Unit) { checkCase(name, work); count++ }

    scenario("no subscribers add no observer schedule and preserve original submission settlement retirement") {
        TestFixture(enforceNoObserverPosts = true).use { f ->
            f.post(); f.ready()
            check(f.port.sent.size == 1)
            val original = f.port.sent.single()
            val outcome = NotificationDispatchOutcome.rejected(original.carrier)
            f.port.settle(original, outcome); f.pump()
            check(f.owner.lastOutcome === outcome && f.port.sent.size == 1)
            f.policy.revoke(original.carrier.selection); f.pump()
            check(original.carrier.permit.state == LaunchCommitPermit.State.RETIRED)
            check(field(f.owner, "current").get(f.owner) == null)
            check(f.unexpectedObserverPosts == 0)
        }
    }
    scenario("registration has no replay; captured recipients exclude later subscribers") {
        TestFixture().use { f ->
            f.post()
            val events = mutableListOf<NotificationPipelineObservation>()
            val registration = f.owner.observePipeline(events::add)
            f.pump(); check(events.isEmpty())
            f.post(); f.pump()
            check(events.count { it.stage == NotificationPipelineStage.DEMAND_ADMITTED } == 1)
            registration.close()
        }
    }
    scenario("close suppresses already queued pipeline delivery") {
        TestFixture().use { f ->
            val events = mutableListOf<NotificationPipelineObservation>()
            val registration = f.owner.observePipeline(events::add)
            f.post(); registration.close(); f.pump(); check(events.isEmpty())
        }
    }
    scenario("observer exception is isolated from later observer and actual owner submission") {
        TestFixture().use { f ->
            val bad = f.owner.observePipeline { error("synthetic observer fault") }
            val events = mutableListOf<NotificationPipelineObservation>()
            val good = f.owner.observePipeline(events::add)
            f.post(); f.ready()
            check(f.port.sent.size == 1)
            check(events.any { it.stage == NotificationPipelineStage.SUBMISSION_STARTED })
            bad.close(); good.close()
        }
    }
    scenario("queued records retain original receipt G D and captured permit state across supersession") {
        TestFixture().use { f ->
            val events = mutableListOf<NotificationPipelineObservation>()
            val registration = f.owner.observePipeline(events::add)
            val first = f.post(); val second = f.post(); f.pump()
            val admitted = events.filter { it.stage == NotificationPipelineStage.DEMAND_ADMITTED }
            check(admitted.size == 2 && admitted[0].ingress === first && admitted[1].ingress === second)
            check(admitted[0].selection !== admitted[1].selection && admitted[0].demand !== admitted[1].demand)
            check(admitted[0].permitState == LaunchCommitPermit.State.PENDING)
            check((admitted[0].selection as NotificationSelection).permit.state == LaunchCommitPermit.State.RETIRED)
            check(!admitted[0].toString().contains("synthetic-platform-key"))
            registration.close()
        }
    }
    scenario("inline post permits admitted observer reentry without old selection clobber") {
        TestFixture(inlinePost = true).use { f ->
            val events = mutableListOf<NotificationPipelineObservation>()
            var once = true
            val registration = f.owner.observePipeline { event ->
                events.add(event)
                if (event.stage == NotificationPipelineStage.DEMAND_ADMITTED && once) { once = false; f.post() }
            }
            f.post(); f.ready()
            val admitted = events.filter { it.stage == NotificationPipelineStage.DEMAND_ADMITTED }
            check(admitted.size == 2 && f.port.sent.size == 1)
            check(f.port.sent.single().carrier.selection === admitted.last().selection)
            registration.close()
        }
    }
    scenario("inline settlement is reported only after original invocation return and exactly once") {
        TestFixture(inlinePost = true).use { f ->
            f.port.inlineReady = true; f.port.inlineSettlement = true
            val events = mutableListOf<NotificationPipelineObservation>()
            val registration = f.owner.observePipeline { event ->
                if (event.stage in setOf(NotificationPipelineStage.SUBMISSION_STARTED, NotificationPipelineStage.SETTLED))
                    check(!f.port.inSubmit) { "publication preceded original submission return" }
                events.add(event)
            }
            f.port.duringSubmit = { check(events.none { it.stage == NotificationPipelineStage.SETTLED }) }
            f.post(); f.ready()
            check(f.port.sent.size == 1)
            val pending = f.port.sent.single()
            f.port.settle(pending); f.pump()
            check(events.count { it.stage == NotificationPipelineStage.SETTLED } == 1)
            val submitted = events.single { it.stage == NotificationPipelineStage.SUBMISSION_STARTED }
            val settled = events.single { it.stage == NotificationPipelineStage.SETTLED }
            check(submitted.carrier === pending.carrier && settled.carrier === pending.carrier)
            check(settled.ready === pending.carrier.ready && settled.demand === pending.carrier.ready.handle)
            registration.close()
        }
    }
    scenario("retired S late and duplicate settlement preserve original identities and successor outcome") {
        TestFixture().use { f ->
            val events = mutableListOf<NotificationPipelineObservation>()
            val registration = f.owner.observePipeline(events::add)
            val firstReceipt = f.post(); f.ready(); val first = f.port.sent.single()
            f.post(); f.ready(); check(f.port.sent.size == 2)
            check(f.owner.lastOutcome == null)
            f.port.settle(first); f.port.settle(first); f.pump()
            val settled = events.filter { it.stage == NotificationPipelineStage.SETTLED }
            check(settled.size == 1 && settled.single().ingress === firstReceipt && settled.single().carrier === first.carrier)
            check(settled.single().selection === first.carrier.selection)
            check(f.owner.lastOutcome == null)
            registration.close()
        }
    }
    scenario("late original R return and callback remain correlated without replacing successor") {
        TestFixture().use { f ->
            f.port.inlineReady = true
            var once = true
            f.port.beforeReadyReturn = { if (once) { once = false; f.post() } }
            val events = mutableListOf<NotificationPipelineObservation>()
            val registration = f.owner.observePipeline(events::add)
            f.post(); f.ready(); f.ready()
            check(f.port.registrations.size >= 2)
            val old = f.port.registrations.first()
            check(old.closes == 1)
            val countBefore = events.count { it.stage == NotificationPipelineStage.READY_OBSERVED }
            old.ready?.let(old.receive); f.pump()
            check(events.count { it.stage == NotificationPipelineStage.READY_OBSERVED } == countBefore)
            check(f.port.sent.all { it.carrier.ready.handle !== old.handle })
            registration.close()
        }
    }
    scenario("actual coordinator hold then Bootstrap hold preserve one original carrier and committed settlement") {
        TestFixture(controlled = false).use { f ->
            val events = mutableListOf<NotificationPipelineObservation>()
            val registration = f.owner.observePipeline(events::add)
            val scaleEntered = CountDownLatch(1); val releaseScale = CountDownLatch(1)
            val displayEntered = CountDownLatch(1); val releaseDisplay = CountDownLatch(1)
            val displayField = field(f.actual.bootstrap, "displayExecutor")
            val display = EnqueueObserver(displayField.get(f.actual.bootstrap) as ExecutorService)
            displayField.set(f.actual.bootstrap, display)
            f.actual.service.beforeScale = { scaleEntered.countDown(); check(releaseScale.await(4, TimeUnit.SECONDS)) }
            try {
                f.actual.sessions.launchApp("example.pipeline.manual", f.actual.service.context) {}
                f.waitFor("manual restore did not enter") { scaleEntered.count == 0L }
                display.submit { displayEntered.countDown(); check(releaseDisplay.await(4, TimeUnit.SECONDS)) }
                check(displayEntered.await(2, TimeUnit.SECONDS))
                val heldEnqueues = display.accepted.get()
                val originalReceipt = f.post(); f.ready()
                val started = events.single { it.stage == NotificationPipelineStage.SUBMISSION_STARTED }
                check(f.actual.service.launches.none { it == f.pkg })
                check(events.none { it.stage == NotificationPipelineStage.SETTLED })
                f.actual.service.beforeScale = null; releaseScale.countDown()
                f.waitFor("coordinator notification Work not dispatched to second queue") {
                    display.accepted.get() > heldEnqueues
                }
                check(f.actual.service.launches.none { it == f.pkg })
                releaseDisplay.countDown()
                f.waitFor("actual original committed settlement missing") { events.any { it.stage == NotificationPipelineStage.SETTLED } }
                val settled = events.single { it.stage == NotificationPipelineStage.SETTLED }
                check(settled.ingress === originalReceipt && settled.carrier === started.carrier)
                check(settled.ready === started.ready && settled.selection === started.selection && settled.demand === started.demand)
                check(settled.outcome?.commitment == NotificationCommitment.COMMITTED)
                check(settled.outcome?.result?.foregroundPackage == f.pkg && f.actual.service.launches.count { it == f.pkg } == 1)
                check(settled.permitState == LaunchCommitPermit.State.COMMITTED)
            } finally { f.actual.service.beforeScale = null; releaseScale.countDown(); releaseDisplay.countDown(); registration.close() }
        }
    }
    scenario("inline SUBMISSION_STARTED observer manual choice occurs after real coordinator invocation") {
        TestFixture(inlinePost = true, controlled = false).use { f ->
            val display = field(f.actual.bootstrap, "displayExecutor").get(f.actual.bootstrap) as ExecutorService
            val entered = CountDownLatch(1); val release = CountDownLatch(1)
            display.submit { entered.countDown(); check(release.await(4, TimeUnit.SECONDS)) }
            check(entered.await(2, TimeUnit.SECONDS))
            val manual = "example.pipeline.successor"
            var invoked = false
            val registration = f.owner.observePipeline { event ->
                if (event.stage == NotificationPipelineStage.SUBMISSION_STARTED && !invoked) {
                    check(!f.port.inSubmit)
                    invoked = true
                    f.actual.sessions.launchApp(manual, f.actual.service.context) {}
                }
            }
            try {
                f.post(); f.ready(); check(invoked)
                release.countDown()
                f.waitFor("manual successor launch missing") { f.actual.service.launches.contains(manual) }
                check(f.actual.service.launches.none { it == f.pkg })
                check(f.actual.sessions.appScales.state()?.target?.packageName == manual)
            } finally { release.countDown(); registration.close() }
        }
    }
    println("RESULT scenarios=$count failures=0 scope=actual-source-platform-boundaries-not-Android-device")
}
