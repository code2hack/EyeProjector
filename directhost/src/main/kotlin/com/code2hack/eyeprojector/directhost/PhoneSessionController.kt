package com.code2hack.eyeprojector.directhost

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.code2hack.eyeprojector.directhost.widget.PhoneWidgetUpdates
import com.code2hack.eyeprojector.protocol.*
import com.code2hack.eyeprojector.directhost.scale.*
import com.code2hack.eyeprojector.directhost.input.PhoneHidRoute
import java.io.File
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicLong
import java.io.Closeable
import com.code2hack.eyeprojector.directhost.activation.*

/** App-scoped owner survives panel recreation and supplies the same commands to later Phone controls. */
class PhoneSessionController private constructor(context: Context) {
    private val contextAuthority = ActivationContextAuthority()
    private val fastLock = contextAuthority.lock
    private val main = Handler(Looper.getMainLooper())
    private val stateObservers = CopyOnWriteArraySet<() -> Unit>()
    private val inputInvalidators = CopyOnWriteArraySet<() -> Unit>()
    val bootstrap = HostBootstrap.get(context)
    val store = PeerStore(File(context.filesDir, "session"))
    private val featureLane = Executors.newSingleThreadScheduledExecutor()
    private val pendingBackend = AtomicReference<HostBootstrap.Status?>()
    private data class LocalRequest(val handle: ActivationHandle, val cause: ActivationCause, val expectedEpoch: String? = null)
    private val pendingRequest = AtomicReference<LocalRequest?>()
    private val pendingStops = linkedSetOf<String>()
    private val pendingReleased = AtomicReference<HostBootstrap.Status?>()
    private val featureFiles = context.filesDir
    private val drainQueued = AtomicBoolean()
    // Identity, its pending demand and retirement move together; no effects/callbacks under this lock.
    private var initialized = false // Guarded by fastLock.
    private var firstOffer: String? = null
    private var initialOfferOrigin: Pair<String, String>? = null
    private var stopInitialOwner = false
    private val currentHandle = AtomicReference<ActivationHandle?>() // H offer/alias, never D.
    private var currentDemand: ActivationHandle? = null
    private var projectedActivation: ActivationState<ProjectionContext>? = null
    private var readySerial = 0L
    private var genericProducerVersion = -1L
    private var projectedProducerVersion = -1L
    private var turnProducerVersion = -1L // Feature-lane owned capture, never refreshed by an old publisher.
    private var inputOwner: String? = null
    private var inputTarget = ProjectionContext()
    private lateinit var contextRegistration: Closeable
    private lateinit var retiredWitnessRegistration: Closeable
    private val localNonce = java.util.UUID.randomUUID().toString()
    private val offers = AtomicLong()
    private val localRevision = AtomicLong()
    @Volatile private var publishedRevision = -1L
    @Volatile private var aliases: Map<String, String> = emptyMap()
    private val pendingForget = AtomicReference<(() -> Unit)?>(null)
    @Volatile private var readyTarget = ProjectionContext()
    @Volatile private var readyAttempt: ActivationAttempt? = null
    @Volatile private var observedActivation: ActivationState<ProjectionContext>? = null
    @Volatile private var usable = false
    @Volatile private var published = SessionState("", SessionPhase.UNKNOWN)
    @Volatile private var featureDetail = "Reconciling projection ownership"
    private lateinit var controller: SessionController
    private lateinit var activation: PhoneActivationEngine
    val pairing = LanPairing(store, PeerRole.PHONE, ::invalidateState, { socket ->
        SessionControl.serve(store.directory, socket, ::command)
    })

    val appScales = AppScaleCoordinator(AppScalePreferences.open(context), object : AppScaleBackend {
        override fun launch(packageName: String, expected: ProjectionContext, complete: (ProjectionDisplayState?) -> Unit) =
            bootstrap.launchApp(packageName, expected, complete)
        override fun display(expected: ProjectionContext, complete: (ProjectionDisplayState?) -> Unit) =
            bootstrap.projectionDisplayState(expected, complete)
        override fun request(value: Double, expected: ProjectionContext, complete: (ScaleState?) -> Unit) =
            bootstrap.requestScale(value, expected, complete)
        override fun read(expected: ProjectionContext, complete: (ScaleState?) -> Unit) = bootstrap.scaleState(expected, complete)
    })

    init {
        contextRegistration = bootstrap.registerActivationAuthority(contextAuthority)
        retiredWitnessRegistration = bootstrap.observeRetiredBackendWitness { state ->
            pendingReleased.set(state); scheduleDrain()
        }
        bootstrap.observe { state ->
            val invalid = synchronized(fastLock) {
                val fact = contextAuthority.fact
                val lost = usable && (fact?.eligible != true || fact.context != inputTarget)
                if (lost) { usable = false; clearReadyLocked() }
                lost
            }
            if (invalid) invalidateInput()
            if (state.backend == "STOPPED" || state.rejectedEpoch != null) pendingReleased.set(state)
            pendingBackend.set(state)
            appScales.contextChanged(state.projectionContext, state.backend in setOf("SESSION_STARTING", "STREAMING"))
            scheduleDrain()
        }
        featureLane.scheduleWithFixedDelay({ drain() }, 0, 1, TimeUnit.SECONDS)
        pairing.start()
    }

    fun state(): SessionState = published
    internal fun activationId(): String? = currentHandle.get()?.id
    internal fun activationStatus(): String = featureDetail
    /** Last authenticated presentation + current local context only, never backend injection acknowledgment. */
    internal fun needsActivation(): Boolean = synchronized(fastLock) {
        val current = currentHandle.get()
        val fact = contextAuthority.fact
        current == null || !usable || fact?.eligible != true || fact.context != inputTarget ||
            genericProducerVersion != contextAuthority.version || (aliases[current.id] ?: current.id) != inputOwner
    }
    internal fun onInputInvalidated(action: () -> Unit) { inputInvalidators.add(action) }
    private fun invalidateInput() {
        val cancel = { inputInvalidators.forEach { runCatching { it() } }; Unit }
        if (Looper.myLooper() == Looper.getMainLooper()) cancel() else main.post { cancel() }
    }

    /** Immediate local identity capture only. HID never waits for this writer, disk or Binder. */
    internal fun requestActivation(cause: ActivationCause): ActivationHandle {
        val handle = synchronized(fastLock) {
            val h = currentHandle.get() ?: ActivationHandle("$localNonce.${offers.incrementAndGet()}", cause).also {
                currentHandle.set(it); localRevision.incrementAndGet()
                if (!initialized && firstOffer == null) firstOffer = it.id
            }
            if (cause == ActivationCause.NOTIFICATION) {
                ActivationHandle(h.id, cause, "$localNonce.d${offers.incrementAndGet()}", ActivationGoal.PROJECTED).also { d ->
                    currentDemand = d; projectedActivation = null; projectedProducerVersion = -1
                    localRevision.incrementAndGet()
                    pendingRequest.set(LocalRequest(d,cause))
                }
            } else {
                // One existing latest-value slot; weaker input never replaces a pending PROJECTED request.
                val pending = pendingRequest.get()
                if (pending?.handle?.demandId == null) pendingRequest.set(LocalRequest(h,cause))
                h
            }
        }
        scheduleDrain(); invalidateState()
        return handle
    }
    private fun currentLocked(handle: ActivationHandle): Boolean {
        val current = currentHandle.get() ?: return false
        return (aliases[current.id] ?: current.id) == (aliases[handle.id] ?: handle.id) &&
            (handle.demandId == null || currentDemand === handle)
    }
    internal fun isActivationCurrent(handle: ActivationHandle): Boolean = synchronized(fastLock) { currentLocked(handle) }
    private fun stateLocked(handle: ActivationHandle): ActivationState<ProjectionContext> {
        if (!currentLocked(handle)) return ActivationState(handle, ActivationPhase.CANCELED)
        val value = if (handle.demandId == null) observedActivation else projectedActivation
        val captured = value?.takeIf { (aliases[it.handle.id] ?: it.handle.id) == (aliases[handle.id] ?: handle.id) &&
            it.handle.demandId == handle.demandId }?.copy(handle = handle)
            ?: return ActivationState(handle, ActivationPhase.PENDING)
        val version = if (handle.demandId == null) genericProducerVersion else projectedProducerVersion
        val fact = contextAuthority.fact
        return if (captured.phase == ActivationPhase.READY &&
            (version != contextAuthority.version || fact?.eligible != true || fact.context != captured.target))
            captured.copy(phase = ActivationPhase.AWAITING_PRESENTATION, target = null, authority = 0) else captured
    }
    internal fun observeActivation(handle: ActivationHandle, receive: (ActivationState<ProjectionContext>) -> Unit): Closeable {
        val closed = AtomicBoolean()
        lateinit var observer: () -> Unit
        observer = {
            if (!closed.get()) {
                val value = synchronized(fastLock) { stateLocked(handle) }
                if (value.phase == ActivationPhase.CANCELED) { closed.set(true); removeStateObserver(observer) }
                receive(value) // No callback under the authority lock.
            }
        }
        observeState(observer)
        return Closeable { closed.set(true); removeStateObserver(observer) }
    }
    internal fun whenActivationReady(handle: ActivationHandle, complete: (ActivationReady<ProjectionContext>) -> Unit): Closeable {
        val closed = AtomicBoolean()
        val subscription = AtomicReference<Closeable?>()
        val registration = observeActivation(handle) { value ->
            if (value.phase == ActivationPhase.CANCELED) { closed.set(true); subscription.get()?.close() }
            if (!closed.get() && value.phase == ActivationPhase.READY && value.authority != 0L &&
                value.binding != null && value.target != null && value.attempt != null && isActivationCurrent(handle) &&
                closed.compareAndSet(false,true)) {
                subscription.get()?.close()
                complete(ActivationReady(handle,value.attempt,value.target,value.binding,value.authority))
            }
        }
        subscription.set(registration)
        if (closed.get()) registration.close()
        return Closeable { closed.set(true); registration.close() }
    }
    /** Final app-side admission only; no launch, query, I/O, callback or main-thread wait. */
    internal fun tryCommitActivationReady(ready: ActivationReady<ProjectionContext>, permit: LaunchCommitPermit): Boolean = synchronized(fastLock) {
        val value = if (ready.handle.demandId == null) observedActivation else projectedActivation
        val version = if (ready.handle.demandId == null) genericProducerVersion else projectedProducerVersion
        val fact = contextAuthority.fact
        ready.handle.demandId != null && ready.handle.goal == ActivationGoal.PROJECTED &&
            currentLocked(ready.handle) && ready.binding != null && ready.authority != 0L &&
            value?.phase == ActivationPhase.READY && value.authority == ready.authority && value.binding == ready.binding &&
            value.attempt === ready.attempt && value.target == ready.target && ready.binding.attemptId == ready.attempt.id &&
            fact?.eligible == true && fact.context == ready.target && version == contextAuthority.version && permit.tryCommit()
    }
    private fun clearReadyLocked() {
        observedActivation = observedActivation?.copy(phase = ActivationPhase.AWAITING_PRESENTATION, target = null, authority = 0)
        projectedActivation = projectedActivation?.copy(phase = ActivationPhase.AWAITING_PRESENTATION, target = null, authority = 0)
        genericProducerVersion = -1; projectedProducerVersion = -1
    }
    private data class FastRetirement(val revision: Long, val notify: Boolean)
    private fun retireFastOwner(id: String, cancel: Boolean, inputLost: Boolean): FastRetirement = synchronized(fastLock) {
        val current = currentHandle.get() ?: return@synchronized FastRetirement(localRevision.get(),false)
        if ((aliases[current.id] ?: current.id) != id) return@synchronized FastRetirement(localRevision.get(),false)
        clearReadyLocked()
        val notify = usable && inputLost
        if (inputLost) usable = false
        if (cancel) {
            currentHandle.set(null); currentDemand = null; localRevision.incrementAndGet()
            pendingRequest.get()?.takeIf { (aliases[it.handle.id] ?: it.handle.id) == id }?.let { pendingRequest.set(null) }
        }
        FastRetirement(localRevision.get(),notify)
    }
    private fun scheduleDrain() {
        if (drainQueued.compareAndSet(false, true)) featureLane.execute { drainQueued.set(false); drain() }
    }
    private fun drain(control: SessionControl.Request? = null): SessionState? {
        val revision = synchronized(fastLock) {
            turnProducerVersion = contextAuthority.version
            localRevision.get()
        }
        try {
            if (!::controller.isInitialized) {
                val attempts = SessionAttemptStore(File(featureFiles, "session-attempts.properties"))
                controller = SessionController({ epoch -> bootstrap.start(epoch) }, { bootstrap.stop() }, initialPhase = SessionPhase.UNKNOWN,
                    initialCorrelation = attempts.read(), retainCorrelation = attempts::write)
            }
            if (!::activation.isInitialized) {
                val journal = ActivationStore(File(featureFiles, "activation.properties"))
                activation = PhoneActivationEngine(controller, journal::read, journal::write,
                    { if (store.pairedPeer() == null) "PAIRING_REQUIRED" else bootstrap.status().setup.takeIf { it != HostBootstrap.Setup.AVAILABLE }?.name },
                    { bootstrap.status().projectionContext }, contextRevision = { synchronized(fastLock) { contextAuthority.version } })
                synchronized(fastLock) {
                    // Capture only the journal owner observed BEFORE any feature effects. An initial
                    // offered ticket can be stopped before its alias is written; never bind it to a later H.
                    val restored = activation.handle
                    if (restored != null) {
                        firstOffer?.let { initialOfferOrigin = it to restored.id }
                        if (stopInitialOwner) synchronized(pendingStops) { pendingStops.add(restored.id) }
                    }
                    initialized = true
                    if (revision == localRevision.get() && pendingRequest.get() == null && currentHandle.get() == null)
                        currentHandle.set(restored)
                }
            }
            pendingReleased.getAndSet(null)?.let { state ->
                controller.observeBackend(state.sessionEpoch, state.backend, state.detail.orEmpty(), state.rejectedEpoch)
            }
            pendingBackend.getAndSet(null)?.let { state ->
                controller.observeBackend(state.sessionEpoch, state.backend, state.detail.orEmpty(), state.rejectedEpoch)
            }
            val stopped = synchronized(pendingStops) { pendingStops.toList().also { pendingStops.clear() } }
            stopped.forEach { id ->
                try { activation.cancel(id, initialOfferOrigin?.takeIf { it.first == id }?.second) } catch (error: Exception) { synchronized(pendingStops) { pendingStops.add(id) }; throw error }
            }
            // Retire fast authority for the ORIGINAL accepted scope before ordered feature/backend effects.
            control?.let { request ->
                val cancelId = request.cancelActivation?.activationId?.let(activation::resolve)
                    ?: request.cancelTrigger?.triggerId?.let { activation.journal.triggers[it]?.activation }
                val report = request.presentation
                val observedId = report?.takeIf(activation::acceptsPresentation)?.activationId
                val legacyOwner = if (request.operation == SessionControl.Operation.STOP)
                    controller.stopCallerOwner(request.startEpoch.ifEmpty { request.epoch }) else null
                val cleanupId = legacyOwner?.let { activation.journal.attempts[it]?.activation }
                val failedId = if (request.operation == SessionControl.Operation.FAILED && controller.ownership().state.epoch == request.epoch)
                    activation.journal.attempts[request.epoch]?.activation else null
                val affected = cancelId ?: observedId ?: cleanupId ?: failedId
                if (affected != null && retireFastOwner(affected, cancelId != null,
                        cancelId != null || report?.mode == ReceiverUsabilityMode.NONE || cleanupId != null || failedId != null).notify) invalidateInput()
            }
            val reply = control?.let(activation::command)
            synchronized(fastLock) { pendingRequest.getAndSet(null) }?.let { request ->
                try {
                    if (request.handle.id !in activation.journal.canceled &&
                        (request.expectedEpoch == null || controller.acceptsStartCaller(request.expectedEpoch)))
                        if (synchronized(fastLock) { currentLocked(request.handle) })
                            activation.request(request.cause, request.handle.id, request.handle.demandId)
                } catch (error: Exception) {
                    synchronized(fastLock) {
                        if (currentLocked(request.handle)) pendingRequest.compareAndSet(null, request)
                    }
                    throw error
                }
            }
            pendingForget.getAndSet(null)?.let { done ->
                try { activation.stop(); bootstrap.stopBeforeForget(done) }
                catch (error: Exception) { pendingForget.compareAndSet(null, done); throw error }
            }
            activation.tick(); publishActivation(revision)
            return reply
        } catch (error: Exception) {
            usable = false; featureDetail = "Activation pending: ${error.javaClass.simpleName}"
            invalidateState() // Keep durable intent pending; a later ordered turn retries reconciliation.
            return null
        }
    }
    private fun publishActivation(revision: Long) {
        published = activation.state()
        val actual = activation.handle
        val journal = activation.journal
        val mapped = journal.aliases
        val canceled = journal.canceled
        val value = actual?.let(activation.local::state)
        val backend = bootstrap.status() // Outside both publication/claim locks.
        val c = journal.current
        val input = activation.inputPresentation
        val goalReport = activation.goalPresentation
        val inputAvailable = activation.inputUsable
        val attempt = activation.attempt
        val invalidate = synchronized(fastLock) {
            if (revision != localRevision.get() || turnProducerVersion != contextAuthority.version) return@synchronized false
            aliases = mapped
            val offered = currentHandle.get()
            val pending = pendingRequest.get()
            if (pending != null && (mapped[pending.handle.id] ?: pending.handle.id) in canceled) pendingRequest.set(null)
            if (pendingRequest.get() == null) {
                val selected = if (actual != null && offered != null && (mapped[offered.id] ?: offered.id) == actual.id) offered else actual
                currentHandle.set(selected)
                if (selected == null) currentDemand = null
            }
            val h = currentHandle.get()
            val fact = contextAuthority.fact
            val matches = h != null && (mapped[h.id] ?: h.id) == actual?.id && c?.desired == true
            val wasUsable = usable
            inputOwner = actual?.id; inputTarget = backend.projectionContext
            usable = matches && inputAvailable && inputTarget.available && fact?.eligible == true && fact.context == inputTarget
            fun binding(demand: ActivationHandle?, report: PresentationReport?) = c?.let {
                ActivationBinding(it.id,demand?.demandId,demand?.goal ?: ActivationGoal.USABLE,
                    if (demand == null) report?.wakeRevision ?: it.wakeRevision else it.wakeRevision,
                    attempt?.id,report?.incarnation,report?.revision)
            }
            fun qualify(next: ActivationState<ProjectionContext>, old: ActivationState<ProjectionContext>?, oldVersion: Long): ActivationState<ProjectionContext> {
                if (next.phase != ActivationPhase.READY) return next
                val same = old?.phase == ActivationPhase.READY && old.binding == next.binding && old.attempt === next.attempt &&
                    old.target == next.target && oldVersion == contextAuthority.version
                return next.copy(authority = if (same) old!!.authority else ++readySerial)
            }
            val generic = h?.let { caller ->
                ActivationState(caller, if (usable && attempt != null) ActivationPhase.READY else value?.phase?.takeUnless { it == ActivationPhase.READY } ?: ActivationPhase.AWAITING_PRESENTATION,
                    attempt, if (usable) inputTarget else null, value?.detail.orEmpty(), binding(null,input))
            }
            observedActivation = generic?.let { qualify(it,observedActivation,genericProducerVersion) }
            genericProducerVersion = contextAuthority.version
            val d = currentDemand
            val demand = if (d != null && matches) {
                val assigned = c?.demandId == d.demandId && c.goal == d.goal
                val goalReady = assigned && value?.phase == ActivationPhase.READY && goalReport != null &&
                    fact?.eligible == true && fact.context == backend.projectionContext && value.target == backend.projectionContext
                ActivationState(d, if (goalReady) ActivationPhase.READY else if (assigned) value?.phase?.takeUnless { it == ActivationPhase.READY } ?: ActivationPhase.AWAITING_PRESENTATION else ActivationPhase.PENDING,
                    if (assigned) attempt else null, if (goalReady) backend.projectionContext else null, value?.detail.orEmpty(),
                    if (assigned) binding(d,goalReport) else null)
            } else null
            projectedActivation = demand?.let { qualify(it,projectedActivation,projectedProducerVersion) }
            projectedProducerVersion = contextAuthority.version
            readyTarget = value?.target ?: ProjectionContext(); readyAttempt = attempt; publishedRevision = revision
            featureDetail = when {
                actual == null -> "Projection inactive"
                backend.setup != HostBootstrap.Setup.AVAILABLE -> "Setup required: ${backend.setup.name}"
                projectedActivation?.phase == ActivationPhase.READY -> "Projected presentation ready"
                c?.goal == ActivationGoal.PROJECTED -> "Waiting for projected presentation"
                usable -> "Projection presented"
                value?.phase == ActivationPhase.RELEASING -> "Waiting for projection release"
                else -> "Connecting/retrying paired projection"
            }
            wasUsable && !usable
        }
        if (invalidate) invalidateInput()
        invalidateState()
    }

    /** Invalidation only: consumers read the latest state at main-thread delivery, outside core locks. */
    fun observeState(observer: () -> Unit) { stateObservers.add(observer); invalidateState() }
    fun removeStateObserver(observer: () -> Unit) { stateObservers.remove(observer) }
    private fun invalidateState() {
        main.post { stateObservers.forEach { observer -> runCatching { observer() } } }
    }

    fun start(): SessionState { requestActivation(ActivationCause.PHONE_START); return state() }
    fun start(expectedEpoch: String): SessionState = featureLane.submit<SessionState> {
        val revision = synchronized(fastLock) { localRevision.get() }
        drain(); activation.start(expectedEpoch).also { publishActivation(revision) }
    }.get(4, TimeUnit.SECONDS)
    fun stop(): SessionState {
        synchronized(fastLock) {
            localRevision.incrementAndGet()
            val original = currentHandle.getAndSet(null)
            if (original == null && !initialized) stopInitialOwner = true
            original?.let {
                synchronized(pendingStops) { pendingStops.add(original.id) }
                pendingRequest.get()?.takeIf { it.handle.id == original.id }?.let { pendingRequest.set(null) }
            }
            currentDemand = null; clearReadyLocked(); usable = false
        }
        invalidateInput(); scheduleDrain()
        return state()
    }
    fun stop(expectedEpoch: String): SessionState = featureLane.submit<SessionState> {
        drain()
        val owner = if (controller.acceptsStopCaller(expectedEpoch)) controller.stopCallerOwner(expectedEpoch) else null
        val h = owner?.let { activation.journal.attempts[it]?.activation }
        val retirement = h?.let { retireFastOwner(it,true,true) }
            ?: synchronized(fastLock) { FastRetirement(localRevision.get(),false) }
        if (retirement.notify) invalidateInput()
        activation.stop(expectedEpoch).also { publishActivation(retirement.revision) }
    }.get(4, TimeUnit.SECONDS)
    internal fun cancelActivation(id: String) {
        val invalidate = synchronized(fastLock) {
            synchronized(pendingStops) { pendingStops.add(id) }
            pendingRequest.get()?.takeIf { it.handle.id == id }?.let { pendingRequest.set(null) }
            val original = currentHandle.get()
            if (original != null && (aliases[original.id] ?: original.id) == (aliases[id] ?: id)) {
                currentHandle.set(null); currentDemand = null; localRevision.incrementAndGet(); clearReadyLocked(); usable = false; true
            } else false
        }
        if (invalidate) invalidateInput()
        scheduleDrain()
    }

    fun stopBeforeForget(complete: () -> Unit) {
        stop()
        pendingForget.set(complete)
        scheduleDrain()
    }

    /** Both launchers consume the owning backend's actual display/generation and result. */
    fun launchApp(packageName: String, complete: (ProjectionDisplayState?) -> Unit) {
        launchApp(packageName, bootstrap.status().projectionContext, complete)
    }
    fun launchApp(packageName: String, expected: ProjectionContext, complete: (ProjectionDisplayState?) -> Unit) {
        appScales.launch(packageName, expected, complete)
    }
    fun launchAppForScale(packageName: String, expected: ProjectionContext, complete: (ProjectionDisplayState?) -> Unit): AppScaleTarget =
        appScales.launch(packageName, expected, complete)
    fun projectionDisplayState(complete: (ProjectionDisplayState?) -> Unit) = bootstrap.projectionDisplayState(complete)

    fun requestPanelOff(complete: (PanelPowerState?) -> Unit) = bootstrap.requestPanelOff(complete)
    fun requestPanelOff(expected: ProjectionContext, complete: (PanelPowerState?) -> Unit) = bootstrap.requestPanelOff(expected, complete)

    fun panelState(complete: (PanelPowerState?) -> Unit) = bootstrap.panelState(complete)

    /** Fresh DOWN is captured and submitted once. UP/CANCEL retain this exact original route. */
    fun capturePhoneHidRoute(down: PhoneHidEdge): PhoneHidRoute? = bootstrap.capturePhoneHidRoute(down)
    fun submitPhoneHid(route: PhoneHidRoute, edge: PhoneHidEdge) = route.submit(edge)
    fun cancelPhoneHid(route: PhoneHidRoute, eventTimeMs: Long) = route.cancel(eventTimeMs)

    private fun command(request: SessionControl.Request): SessionState = featureLane.submit<SessionState> {
        drain(request) ?: state().copy(detail = "ACTIVATION_STORAGE_PENDING")
    }.get(4, TimeUnit.SECONDS)

    companion object {
        @SuppressLint("StaticFieldLeak") // Only the applicationContext is passed to app-scoped owners.
        @Volatile private var instance: PhoneSessionController? = null
        fun get(context: Context): PhoneSessionController = (instance ?: synchronized(this) {
            instance ?: PhoneSessionController(context.applicationContext).also { instance = it }
        }).also { PhoneWidgetUpdates.attach(context.applicationContext, it) }
    }
}
