package com.code2hack.eyeprojector.directhost

import com.code2hack.eyeprojector.directhost.notifications.*

import com.code2hack.eyeprojector.directhost.activation.ActivationContextAuthority
import com.code2hack.eyeprojector.directhost.activation.ActivationContextPublisher
import java.io.Closeable
import com.code2hack.eyeprojector.protocol.ScaleState
import com.code2hack.eyeprojector.protocol.PhoneHidEdge
import com.code2hack.eyeprojector.protocol.PhoneHidPhase
import com.code2hack.eyeprojector.directhost.input.PhoneHidRoute
import com.code2hack.eyeprojector.directhost.activation.snapshot.PhoneHidSnapshotSource
import com.code2hack.eyeprojector.directhost.activation.snapshot.NativeSnapshotReadLane
import com.code2hack.eyeprojector.directhost.uiaccess.ManagedUiSource
import com.code2hack.eyeprojector.directhost.uiaccess.ManagedUiSourceProvider
import com.code2hack.eyeprojector.directhost.uiaccess.UiTargetAccess
import com.code2hack.eyeprojector.directhost.uiaccess.UiTargetSnapshot
import com.code2hack.eyeprojector.directhost.uiaccess.UiTargetSnapshotProvider
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.AtomicFile
import java.io.File
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.UUID
import org.json.JSONObject
import rikka.shizuku.Shizuku

/** One app-scoped route, also used by subsequent launcher/widget integration. */
class HostBootstrap private constructor(private val context: Context) {
    enum class Setup { MISSING, UNAVAILABLE, UPDATE_REQUIRED, AUTHORIZATION_REQUIRED, SHELL_REQUIRED, AVAILABLE }
    data class Status(val setup: Setup, val backend: String, val detail: String? = null, val sessionEpoch: String = "", val rejectedEpoch: String? = null,
        val projectionToken: String = "") {
        val projectionContext: ProjectionContext get() = ProjectionContext(sessionEpoch, projectionToken)
    }
    private enum class Action { START, STOP }
    private val main = Handler(Looper.getMainLooper())
    private val forget = HostForgetCompletion { main.post(it) }
    private val executor = Executors.newSingleThreadScheduledExecutor()
    private val displayExecutor = Executors.newSingleThreadExecutor()
    private val uiTargetExecutor = Executors.newSingleThreadExecutor()
    private val inputRoutes = java.util.concurrent.atomic.AtomicLong(System.nanoTime())
    private val scaleExecutor = Executors.newSingleThreadExecutor()
    private val panelExecutor = Executors.newSingleThreadExecutor()
    private val panelQueryExecutor = Executors.newSingleThreadExecutor()
    private val observers = CopyOnWriteArraySet<(Status) -> Unit>()
    private val retiredWitnessObservers = CopyOnWriteArraySet<(Status) -> Unit>()
    private val contextPublisher = ActivationContextPublisher()
    @Volatile private var current = Status(Setup.UNAVAILABLE, "UNKNOWN")
    @Volatile private var remote: IHostBootstrap? = null
    private var revokedBinder: IBinder? = null
    private var pending: Action? = null
    private var pendingEpoch = ""
    private var localDetail: String? = null
    private var connecting = false
    private var bindStarted = 0L
    @Volatile private var bindingGeneration = 0L
    private var acceptedGeneration = -1L
    private var connection: ServiceConnection? = null
    // A new installed APK gets a new shell process rather than reusing loaded old dex.
    private val installedRevision = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
    private val serviceRevision = (installedRevision xor (installedRevision ushr 32)).toInt()
    private val args = Shizuku.UserServiceArgs(ComponentName(context, ShizukuHostService::class.java))
        .tag("eyeprojector-host").processNameSuffix("projection_host").daemon(true)
        .version(serviceRevision)

    private fun newConnection(accepted: Boolean): ServiceConnection {
        if (connection != null) abandonConnection()
        val generation = ++bindingGeneration
        contextPublisher.bindingChanged()
        acceptedGeneration = if (accepted) generation else -1L
        connecting = true; bindStarted = System.nanoTime()
        return object : ServiceConnection {
            @Volatile private var originalBinding: Pair<IBinder, ActivationContextPublisher.Binding>? = null
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                executor.execute {
                    // Peek callbacks are queued before its returned revision can be checked.
                    if (generation != bindingGeneration || acceptedGeneration != generation) return@execute
                    connecting = false
                    val binding = contextPublisher.bindingChanged()
                    originalBinding = binder to binding
                    remote = IHostBootstrap.Stub.asInterface(binder)
                    runCatching {
                        binder.linkToDeath({
                            contextPublisher.retireBinding(binding)
                            executor.execute {
                            if (revokedBinder == binder) finishRevokedStop(binder)
                            else if (remote?.asBinder() == binder) { remote = null; publish("UNKNOWN", "SERVICE_DIED") }
                            }
                        }, 0)
                        performPending()
                        refreshNow()
                    }.onFailure { remote = null; publish("UNKNOWN", "SERVICE_UNREACHABLE") }
                }
            }
            override fun onServiceDisconnected(name: ComponentName) {
                originalBinding?.let { original ->
                    if (!original.first.isBinderAlive) contextPublisher.retireBinding(original.second)
                }
                executor.execute {
                    if (generation != bindingGeneration) return@execute
                    if (remote?.asBinder()?.isBinderAlive == false) {
                        remote = null; publish("UNKNOWN", "SERVICE_DISCONNECTED")
                    }
                    connecting = false
                }
            }
        }.also { connection = it }
    }

    private fun abandonConnection() {
        val previous = connection
        connection = null; connecting = false
        bindingGeneration++; acceptedGeneration = -1L
        contextPublisher.bindingChanged()
        // Detach this callback only; never kill an owned backend to recover attachment.
        if (previous != null) runCatching { Shizuku.unbindUserService(args, previous, false) }
    }
    private val received = Shizuku.OnBinderReceivedListener { refresh() }
    private val dead = Shizuku.OnBinderDeadListener {
        // The delivered capability loss is known now; it is not an acknowledgment of backend release.
        contextPublisher.retire(contextPublisher.capture())
        executor.execute { if (connecting) abandonConnection(); publish(current.backend, "SHIZUKU_DIED") }
    }
    private val permission = Shizuku.OnRequestPermissionResultListener { _, _ -> refresh() }

    init {
        Shizuku.addBinderReceivedListenerSticky(received)
        Shizuku.addBinderDeadListener(dead)
        Shizuku.addRequestPermissionResultListener(permission)
        executor.scheduleWithFixedDelay({ runCatching { refreshNow() }.onFailure { publish("UNKNOWN", "STATUS_UNAVAILABLE") } }, 0, 1, TimeUnit.SECONDS)
    }

    internal fun registerActivationAuthority(authority: ActivationContextAuthority): Closeable = contextPublisher.register(authority)
    internal fun observeRetiredBackendWitness(observer: (Status) -> Unit): Closeable {
        retiredWitnessObservers.add(observer)
        return Closeable { retiredWitnessObservers.remove(observer) }
    }
    fun observe(observer: (Status) -> Unit) { observers.add(observer); main.post { observer(current) } }
    fun status(): Status = current
    fun removeObserver(observer: (Status) -> Unit) { observers.remove(observer) }
    fun refresh() { executor.execute { runCatching { refreshNow() }.onFailure { publish("UNKNOWN", "STATUS_UNAVAILABLE") } } }
    fun requestAuthorization() {
        runCatching { if (Shizuku.pingBinder()) Shizuku.requestPermission(5001) }.onFailure { refresh() }
    }
    fun start(sessionEpoch: String = UUID.randomUUID().toString()) { executor.execute {
        if (forget.pending()) { publish(current.backend, "FORGET_PENDING", rejectedEpoch = sessionEpoch); return@execute }
        if (revokedBinder != null || pending == Action.STOP) { publish("STOPPING", rejectedEpoch = sessionEpoch); return@execute }
        if (setup() != Setup.AVAILABLE) { publish(current.backend, "START_SETUP_UNAVAILABLE", rejectedEpoch = sessionEpoch); return@execute }
        localDetail = null
        pendingEpoch = sessionEpoch
        pending = Action.START
        publish("STARTING", sessionEpoch = sessionEpoch)
        if (remote != null) performPending() else bind()
    } }
    fun stop() {
        contextPublisher.retire(contextPublisher.capture(), blockBinding = true)
        executor.execute { requestStop() }
    }

    /** Local typed Binder operations; no peer TLS keys or new network trust are involved. */
    fun requestScale(value: Double, complete: (ScaleState?) -> Unit) = requestScaleOperation(value, null, complete)

    fun requestScale(value: Double, expected: ProjectionContext, complete: (ScaleState?) -> Unit) =
        requestScaleOperation(value, expected, complete)

    private fun requestScaleOperation(value: Double, expected: ProjectionContext?, complete: (ScaleState?) -> Unit) {
        val id = System.nanoTime()
        scaleOperation(expected, { service, context -> service.requestScale(id, value, context.ownerEpoch, context.projectionToken) }, complete)
    }

    fun scaleState(complete: (ScaleState?) -> Unit) = scaleOperation(null,
        { service, context -> service.scaleState(context.ownerEpoch, context.projectionToken) }, complete)

    fun scaleState(expected: ProjectionContext, complete: (ScaleState?) -> Unit) = scaleOperation(expected,
        { service, context -> service.scaleState(context.ownerEpoch, context.projectionToken) }, complete)

    /** Capture context on the lifecycle lane; Binder work cannot occupy that lane. */
    fun requestPanelOff(complete: (PanelPowerState?) -> Unit) {
        val id = System.nanoTime()
        panelOperation(id, false, { service, context -> service.requestPanelOff(id, context.ownerEpoch, context.projectionToken) }, complete)
    }

    fun requestPanelOff(expected: ProjectionContext, complete: (PanelPowerState?) -> Unit) {
        val id = System.nanoTime()
        panelOperation(id, false, { service, context -> service.requestPanelOff(id, context.ownerEpoch, context.projectionToken) },
            complete, PanelContext(expected.ownerEpoch, expected.projectionToken))
    }

    fun panelState(complete: (PanelPowerState?) -> Unit) = panelOperation(0, true,
        { service, context -> service.panelState(context.ownerEpoch, context.projectionToken) }, complete)

    private fun panelOperation(id: Long, query: Boolean, operation: (IHostBootstrap, PanelContext) -> ByteArray, complete: (PanelPowerState?) -> Unit,
        expectedContext: PanelContext? = null) {
        val atEntry = current
        val context = expectedContext ?: PanelContext(atEntry.sessionEpoch, atEntry.projectionToken)
        executor.execute {
            val service = remote
            val generation = bindingGeneration
            if (service == null) { main.post { complete(null) }; return@execute }
            if (!context.resolved || context != PanelContext(current.sessionEpoch, current.projectionToken) ||
                pending == Action.STOP || revokedBinder != null) {
                main.post { complete(PanelPowerState.unavailable(id, context)) }; return@execute
            }
            val binder = service.asBinder()
            val operations = if (query) panelQueryExecutor else panelExecutor
            operations.execute {
                // Never resolve remote or current context here: even queued calls retain their original target.
                val result = runCatching { PanelPowerState.from(operation(service, context)) }.getOrNull()
                executor.execute {
                    val currentContext = PanelContext(current.sessionEpoch, current.projectionToken)
                    val reply = result?.takeIf {
                        remote?.asBinder() == binder && bindingGeneration == generation &&
                            currentContext == context && pending != Action.STOP && revokedBinder == null && it.context == context
                    }
                    main.post { complete(reply) }
                }
            }
        }
    }

    private fun scaleOperation(expected: ProjectionContext?, operation: (IHostBootstrap, PanelContext) -> ByteArray, complete: (ScaleState?) -> Unit) {
        val atEntry = current
        val selected = expected ?: atEntry.projectionContext
        val context = PanelContext(selected.ownerEpoch, selected.projectionToken)
        fun matchesContext(): Boolean {
            val observed = current
            return observed.projectionContext == selected ||
                (expected != null && atEntry.projectionToken.isEmpty() && observed === atEntry && observed.sessionEpoch == selected.ownerEpoch)
        }
        executor.execute {
            val service = remote
            val generation = bindingGeneration
            if (service == null || !context.resolved || !matchesContext() ||
                pending == Action.STOP || revokedBinder != null) {
                main.post { complete(null) }; return@execute
            }
            val binder = service.asBinder()
            scaleExecutor.execute {
                // The accepted proxy and actual Session context belong to this intent, even across queueing.
                val result = runCatching { ScaleState.from(operation(service, context)) }.getOrNull()
                executor.execute {
                    val reply = result?.takeIf {
                        remote?.asBinder() == binder && bindingGeneration == generation &&
                            matchesContext() && pending != Action.STOP && revokedBinder == null
                    }
                    main.post { complete(reply) }
                }
            }
        }
    }

    fun launchApp(packageName: String, complete: (ProjectionDisplayState?) -> Unit) =
        launchApp(packageName, current.projectionContext, complete)

    /** Pure original capture; caller executes prepare on its non-deadline setup worker. */
    internal fun capturePhoneHidSnapshot(expected: ProjectionContext): PhoneHidSnapshotSource? {
        if (!expected.available) return null
        val service = remote ?: return null
        val binding = bindingGeneration
        fun matches() = remote === service && bindingGeneration == binding && current.projectionContext == expected
        if (!matches()) return null
        return object : PhoneHidSnapshotSource {
            override val context = expected
            override fun current() = matches()
            override fun prepare(): NativeSnapshotReadLane.Prepared? {
                if (!matches()) return null
                val descriptor = service.phoneHidSnapshot(expected.ownerEpoch, expected.projectionToken) ?: return null
                if (!matches()) { descriptor.close(); return null }
                val prepared = NativeSnapshotReadLane.prepare(descriptor, expected)
                if (!matches()) { prepared.close(); return null }
                return prepared
            }
            override fun route(down: PhoneHidEdge, regionId: String, word: Long): PhoneHidRoute? {
                if (down.phase != PhoneHidPhase.DOWN || down.repeatCount != 0 || !matches()) return null
                val id = inputRoutes.incrementAndGet()
                return PhoneHidRoute(down, binding) { edge ->
                    service.phoneHidStampedEdge(id, edge.bytes(), expected.ownerEpoch, expected.projectionToken, regionId, word)
                }.also { it.submit(down) }
            }
        }
    }

    /** Compatibility route; production HID selects its original stamped snapshot source instead. */
    fun capturePhoneHidRoute(down: PhoneHidEdge): PhoneHidRoute? {
        if (down.phase != PhoneHidPhase.DOWN || down.repeatCount != 0) return null
        val snapshot = current
        val service = remote ?: return null
        val binding = bindingGeneration
        val expected = snapshot.projectionContext.takeIf { it.available } ?: return null
        if (current !== snapshot || remote !== service || bindingGeneration != binding) return null
        val id = inputRoutes.incrementAndGet()
        val route = PhoneHidRoute(down, binding) { edge ->
            service.phoneHidEdge(id, edge.bytes(), expected.ownerEpoch, expected.projectionToken)
        }
        route.submit(down)
        return route
    }

    fun launchApp(packageName: String, expected: ProjectionContext, complete: (ProjectionDisplayState?) -> Unit) =
        displayOperation(expected, LaunchOperationDiagnostics.Kind.LAUNCH, packageName,
            { service, requestId -> service.launchApp(requestId, packageName, expected.ownerEpoch, expected.projectionToken) }, complete)

    internal fun launchNotification(carrier: NotificationLaunchCarrier, complete: (NotificationDispatchOutcome) -> Unit) {
        displayOperation(carrier.ready.target, LaunchOperationDiagnostics.Kind.LAUNCH, carrier.packageName,
            { _, _ -> error("Notification uses the fixed original Binder call") }, {}, carrier, complete)
    }

    fun projectionDisplayState(complete: (ProjectionDisplayState?) -> Unit) =
        projectionDisplayState(current.projectionContext, complete)

    fun projectionDisplayState(expected: ProjectionContext, complete: (ProjectionDisplayState?) -> Unit) =
        displayOperation(expected, LaunchOperationDiagnostics.Kind.DISPLAY_QUERY, null,
            { service, _ -> service.projectionDisplayState(expected.ownerEpoch, expected.projectionToken) }, complete)

    /** Captures one app->shell binding; neither queued work nor the second sample adopts a successor. */
    internal fun captureUiTargetSource(expected: ProjectionContext): ManagedUiSourceProvider? {
        if (!expected.available) return null
        val service = remote ?: return null
        val binding = bindingGeneration
        val original = current
        fun matches(): Boolean {
            val observed = current
            return remote === service && bindingGeneration == binding && (observed.projectionContext == expected ||
                (original.projectionToken.isEmpty() && observed === original && observed.sessionEpoch == expected.ownerEpoch))
        }
        if (!matches()) return null
        return ManagedUiSourceProvider { query ->
            if (query != expected || !matches()) null
            else service.uiTargetContext(expected.ownerEpoch, expected.projectionToken)?.let { payload ->
                ManagedUiSource.from(payload).takeIf { matches() && it.context == expected }
            }
        }
    }

    fun uiTargetContext(expected: ProjectionContext, complete: (ManagedUiSource?) -> Unit) {
        val original = captureUiTargetSource(expected)
        uiTargetExecutor.execute {
            val result = runCatching { original?.current(expected) }.getOrNull()
            main.post { complete(result) }
        }
    }

    /** Accessibility traversal runs in this app process, outside projection/input and lifecycle locks. */
    fun uiTargets(expected: ProjectionContext, selectedPackage: String, windowId: Int? = null,
        complete: (UiTargetSnapshot) -> Unit) {
        val original = captureUiTargetSource(expected)
        uiTargetExecutor.execute {
            val provider = UiTargetSnapshotProvider(original ?: ManagedUiSourceProvider { null },
                UiTargetAccess::reader, android.os.SystemClock::uptimeMillis)
            val result = provider.read(expected, selectedPackage, windowId)
            main.post { complete(result) }
        }
    }

    private fun displayOperation(expected: ProjectionContext, kind: LaunchOperationDiagnostics.Kind, selectedPackage: String?,
        operation: (IHostBootstrap, Long) -> ByteArray, complete: (ProjectionDisplayState?) -> Unit,
        notification: NotificationLaunchCarrier? = null, dispatched: ((NotificationDispatchOutcome) -> Unit)? = null) {
        // Capture before the first queue hop. The server independently validates these
        // same identities when selecting its exact Bridge/Session, including Binder reuse.
        val service = remote
        val binding = bindingGeneration
        val originalStatus = current
        val requestId = System.nanoTime()
        var match: LaunchOperationDiagnostics.Match? = null
        fun matches(): Boolean {
            val observed = current
            // Home already carries an actual context even before the first status poll.
            // Only that unchanged, not-yet-observed snapshot may defer token validation
            // to the server. A new published snapshot cannot rebind the original intent.
            val exactContext = observed.projectionContext == expected
            val sameProjection = exactContext ||
                (originalStatus.projectionToken.isEmpty() && observed === originalStatus && observed.sessionEpoch == expected.ownerEpoch)
            var sameService: Boolean? = null
            var sameBinding: Boolean? = null
            val servicePresent = service != null
            val accepted = servicePresent && (remote === service).also { sameService = it } &&
                (bindingGeneration == binding).also { sameBinding = it } && sameProjection
            try { match = LaunchOperationDiagnostics.Match(servicePresent, sameService, sameBinding, exactContext, sameProjection, accepted) }
            catch (_: Throwable) { match = null } // Diagnostic allocation cannot affect this original decision.
            return accepted
        }
        val work = Runnable {
            var committed = false
            var dispatchOutcome: NotificationDispatchOutcome? = null
            var available: Boolean? = null
            var calling = false
            var decoding = false
            val result = runCatching {
                val expectedAvailable = expected.available.also { available = it }
                if (!expectedAvailable || !matches()) {
                    if (notification != null) dispatchOutcome = if (!expectedAvailable)
                        NotificationDispatchOutcome.fault(notification, NotificationFaultStage.PRE_CALL, "INVALID_CONTEXT")
                        else NotificationDispatchOutcome.rejected(notification)
                    LaunchOperationDiagnostics.emit({ LaunchOperationDiagnostics.Event(requestId, kind, selectedPackage,
                        LaunchOperationDiagnostics.Stage.PRE_CALL, LaunchOperationDiagnostics.Outcome.SKIPPED, available, match,
                        if (!expectedAvailable) LaunchOperationDiagnostics.Reason.EXPECTED_CONTEXT_UNAVAILABLE else null) })
                    null
                } else {
                    val payload: ByteArray?
                    if (notification == null) {
                        LaunchOperationDiagnostics.emit({ LaunchOperationDiagnostics.Event(requestId, kind, selectedPackage,
                            LaunchOperationDiagnostics.Stage.PRE_CALL, LaunchOperationDiagnostics.Outcome.ADMITTED, available, match) })
                        calling = true
                        payload = operation(service!!, requestId)
                    } else {
                        val originalService = service!!
                        val originalPackage = notification.packageName
                        val originalOwner = expected.ownerEpoch
                        val originalToken = expected.projectionToken
                        if (!notification.facade.tryCommitActivationReady(notification.ready, notification.permit)) {
                            dispatchOutcome = NotificationDispatchOutcome.rejected(notification)
                            payload = null
                        } else {
                            committed = true; calling = true
                            // Only fixed original arguments. No diagnostic/callback/queue/lookup here.
                            payload = originalService.launchApp(requestId, originalPackage, originalOwner, originalToken)
                        }
                    }
                    if (payload == null) return@runCatching null
                    decoding = true
                    ProjectionDisplayState.from(payload).also {
                        LaunchOperationDiagnostics.emit({ LaunchOperationDiagnostics.Event(requestId, kind, selectedPackage,
                            LaunchOperationDiagnostics.Stage.RESULT, LaunchOperationDiagnostics.Outcome.RETURNED, available, match, result = it) })
                    }
                }
            }.onFailure {
                if (notification != null) dispatchOutcome = NotificationDispatchOutcome.fault(notification,
                    if (decoding) NotificationFaultStage.DECODE else if (calling) NotificationFaultStage.BINDER else NotificationFaultStage.PRE_CALL,
                    it.javaClass.simpleName)
                LaunchOperationDiagnostics.emit({ LaunchOperationDiagnostics.Event(requestId, kind, selectedPackage,
                    LaunchOperationDiagnostics.Stage.RESULT, if (decoding) LaunchOperationDiagnostics.Outcome.DECODE_EXCEPTION
                    else if (calling) LaunchOperationDiagnostics.Outcome.OPERATION_EXCEPTION
                    else LaunchOperationDiagnostics.Outcome.PRE_CALL_EXCEPTION, available, match, error = it) })
            }.getOrNull()
            if (committed) LaunchOperationDiagnostics.emit({ LaunchOperationDiagnostics.Event(requestId, kind, selectedPackage,
                LaunchOperationDiagnostics.Stage.AFTER_CALL, LaunchOperationDiagnostics.Outcome.ADMITTED, available, match) })
            main.post {
                val selected = result.takeIf {
                    var resultContextMatch: Boolean? = null
                    val accepted = matches() && (it?.context == expected).also { same -> resultContextMatch = same }
                    LaunchOperationDiagnostics.emit({ LaunchOperationDiagnostics.Event(requestId, kind, selectedPackage,
                        LaunchOperationDiagnostics.Stage.BEFORE_CALLBACK,
                        if (accepted && it != null) LaunchOperationDiagnostics.Outcome.VALUE_SELECTED else LaunchOperationDiagnostics.Outcome.NULL_SELECTED,
                        available, match, result = it, resultContextMatch = resultContextMatch) })
                    accepted
                }
                if (notification == null) complete(selected)
                else dispatched!!(if (committed) NotificationDispatchOutcome(NotificationCommitment.COMMITTED, selected,
                    faultStage = dispatchOutcome?.faultStage, faultCode = dispatchOutcome?.faultCode)
                    else dispatchOutcome ?: NotificationDispatchOutcome.rejected(notification))
            }
        }
        try { displayExecutor.execute(work) }
        catch (error: Exception) {
            if (notification == null) main.post { complete(null) }
            else main.post { dispatched!!(NotificationDispatchOutcome.fault(notification, NotificationFaultStage.ENQUEUE, error.javaClass.simpleName)) }
        }
    }

    /** PairingPanel.beforeForget hook: completion and the caller's trust deletion run on main. */
    fun stopBeforeForget(complete: () -> Unit) {
        contextPublisher.retire(contextPublisher.capture(), blockBinding = true)
        executor.execute { forget.request(complete); requestStop() }
    }

    private fun requestStop() {
        contextPublisher.retire(contextPublisher.capture(), blockBinding = true)
        localDetail = null
        revokedBinder?.let { binder ->
            if (!binder.isBinderAlive) finishRevokedStop(binder) else publish("STOPPING")
            return
        }
        pending = Action.STOP
        if (remote != null) performPending()
        else if (setup() == Setup.AVAILABLE) bind()
        else publish("UNKNOWN", "RESTORE_SHIZUKU_TO_VERIFY_STOP")
    }

    private fun setup(): Setup {
        val installed = runCatching { context.packageManager.getPackageInfo("moe.shizuku.privileged.api", 0) }.isSuccess
        if (!Shizuku.pingBinder()) return if (installed) Setup.UNAVAILABLE else Setup.MISSING
        return runCatching {
            when {
                Shizuku.getVersion() < 13 -> Setup.UPDATE_REQUIRED
                Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> Setup.AUTHORIZATION_REQUIRED
                Shizuku.getUid() != 2000 -> Setup.SHELL_REQUIRED
                else -> Setup.AVAILABLE
            }
        }.getOrDefault(Setup.UNAVAILABLE)
    }

    private fun bind() {
        if (connecting) {
            if (System.nanoTime() - bindStarted < TimeUnit.SECONDS.toNanos(8)) return
            abandonConnection()
        }
        val attempt = newConnection(accepted = true)
        publish(if (pending == Action.STOP) "STOPPING" else "STARTING")
        runCatching { Shizuku.bindUserService(args, attempt) }.onFailure {
            abandonConnection(); publish("UNKNOWN", "BIND_FAILED")
        }
    }

    private fun performPending() {
        val service = remote ?: return
        val action = pending ?: return
        if (action == Action.START) pending = null
        var stopRevoked = false
        val source = contextPublisher.capture() // Captured BEFORE Binder/setup work; late replies cannot revive it.
        runCatching {
            val result = when (action) {
                Action.START -> {
                    if (setup() != Setup.AVAILABLE) { publish(current.backend, "START_SETUP_UNAVAILABLE", rejectedEpoch = pendingEpoch); return }
                    val session = File(context.filesDir, "session")
                    val files = BootstrapSessionFiles.read(session)
                    if (files == null) {
                        localDetail = "SESSION_SETUP_REQUIRED"; publish("STOPPED", localDetail, rejectedEpoch = pendingEpoch); return
                    }
                    val endpoint = CameraSourceSettings.endpointForStart(context, files.endpoint)
                    service.start(files.identity, files.password, files.legacyPeer, endpoint, files.pairedPeer, pendingEpoch)
                }
                Action.STOP -> service.stop()
            }
            readStatus(result, source)
            stopRevoked = action == Action.STOP && JSONObject(result).getString("state") == "STOPPED" && JSONObject(result).optBoolean("sessionRevoked")
            if (stopRevoked) {
                val binder = service.asBinder()
                revokedBinder = binder
                service.destroy()
                if (!binder.isBinderAlive) finishRevokedStop(binder) else publish("STOPPING")
            }
        }.onFailure {
            // destroy exits before replying; binder death confirms that exact process ended.
            if (action == Action.STOP && stopRevoked) {
                val binder = service.asBinder()
                if (!binder.isBinderAlive) finishRevokedStop(binder) else publish("STOPPING")
            } else if (action == Action.STOP && !service.asBinder().isBinderAlive) {
                remote = null; publish("STOP_UNVERIFIED", "SESSION_REVOKE_PENDING")
            } else publish("UNKNOWN", "OPERATION_FAILED")
        }
    }

    private fun finishRevokedStop(binder: IBinder) {
        if (revokedBinder != binder || binder.isBinderAlive) return
        revokedBinder = null
        if (remote?.asBinder() == binder) remote = null
        pending = null
        connection = null; connecting = false; bindingGeneration++; acceptedGeneration = -1L
        contextPublisher.bindingChanged()
        publish("STOPPED")
        forget.revoked()
    }

    private fun refreshNow() {
        if (connecting && System.nanoTime() - bindStarted >= TimeUnit.SECONDS.toNanos(8)) {
            abandonConnection()
            publish("UNKNOWN", "BIND_PENDING_REOPEN_SHIZUKU")
        }
        revokedBinder?.let { binder ->
            if (!binder.isBinderAlive) finishRevokedStop(binder) else publish("STOPPING")
            return
        }
        val service = remote
        if (service != null) {
            if (pending == Action.STOP || forget.awaitingTeardown()) { pending = Action.STOP; performPending(); return }
            val source = contextPublisher.capture()
            runCatching { readStatus(service.status(), source) }.onFailure { remote = null; publish("UNKNOWN", "SERVICE_UNREACHABLE") }
        } else if (!connecting && setup() == Setup.AVAILABLE) {
            if (pending != null || forget.awaitingTeardown()) {
                if (forget.awaitingTeardown()) pending = Action.STOP
                bind(); return
            }
            // Peek can return old loaded code after an APK update. Validate its result
            // before accepting the queued callback, and replace through normal bind.
            val attempt = newConnection(accepted = false)
            runCatching { Shizuku.peekUserService(args, attempt) }.onSuccess { revision ->
                when (revision) {
                    -1 -> { abandonConnection(); publish("STOPPED", localDetail) }
                    serviceRevision -> acceptedGeneration = bindingGeneration
                    else -> { abandonConnection(); bind() }
                }
            }.onFailure { abandonConnection(); publish("UNKNOWN", "ATTACH_FAILED") }
        } else publish(current.backend, current.detail)
    }

    private fun readStatus(json: String, source: ActivationContextPublisher.Observation = contextPublisher.capture()) {
        val data = JSONObject(json)
        val backend = data.getString("state")
        val observedBackend = if (pending == Action.STOP && backend == "STOPPED") "STOPPING" else backend
        val epoch = data.optString("sessionEpoch").ifEmpty { source.context.ownerEpoch }
        val token = if (observedBackend in setOf("SESSION_STARTING", "STREAMING")) data.optString("projectionToken") else ""
        val known = contextPublisher.stage(source, ProjectionContext(epoch,token),
            observedBackend in setOf("SESSION_STARTING", "STREAMING") && pending != Action.STOP && revokedBinder == null)
        // Known loss/replacement already retired fast authority, before this potentially blocking write.
        if (known) {
            val file = AtomicFile(File(context.filesDir, "bootstrap-status.json"))
            val output = file.startWrite()
            try { output.write(json.toByteArray()); file.finishWrite(output) }
            catch (error: Exception) { file.failWrite(output); throw error }
        }
        publish(observedBackend, data.optString("failure").takeIf { it.isNotBlank() && it != "null" } ?: localDetail,
            epoch, projectionToken = token, source = source)
    }
    private fun publish(backend: String, detail: String? = null, sessionEpoch: String = current.sessionEpoch, rejectedEpoch: String? = null,
        projectionToken: String? = null, source: ActivationContextPublisher.Observation = contextPublisher.capture()) {
        val token = if (backend in setOf("SESSION_STARTING", "STREAMING"))
            projectionToken ?: current.projectionToken.takeIf { current.sessionEpoch == sessionEpoch }.orEmpty() else ""
        val target = ProjectionContext(sessionEpoch,token)
        val candidate = backend in setOf("SESSION_STARTING", "STREAMING") && pending != Action.STOP && revokedBinder == null
        contextPublisher.stage(source,target,candidate) // Before package/Shizuku queries, no general observer.
        val value = Status(setup(), backend, detail, sessionEpoch, rejectedEpoch, token)
        val disposition = synchronized(contextPublisher.gate) {
            // Gate -> fastLock. No caller ever takes this gate while holding fastLock.
            if (!contextPublisher.commit(source,target,candidate && value.setup == Setup.AVAILABLE)) -1
            else if (value == current) 0 else { current = value; 1 }
        }
        if (disposition == 1) main.post { observers.forEach { it(value) } }
        else if (disposition == -1 && (backend in setOf("STOPPED", "FAILED", "STOP_UNVERIFIED") || rejectedEpoch != null)) {
            // A real old-scope terminal witness remains useful to the core ledger, but cannot
            // overwrite current/fast authority or UI/scale state for a successor binding.
            // Only the ownership ledger receives this original-scope witness.
            main.post { retiredWitnessObservers.forEach { it(value) } }
        }
    }

    companion object {
        // get() passes applicationContext only; this singleton never retains an Activity.
        @SuppressLint("StaticFieldLeak")
        @Volatile private var instance: HostBootstrap? = null
        fun get(context: Context): HostBootstrap = instance ?: synchronized(this) {
            instance ?: HostBootstrap(context.applicationContext).also { instance = it }
        }
    }
}
