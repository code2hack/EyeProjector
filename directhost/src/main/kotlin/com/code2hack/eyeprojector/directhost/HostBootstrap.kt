package com.code2hack.eyeprojector.directhost

import com.code2hack.eyeprojector.protocol.ScaleState
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
    private val scaleExecutor = Executors.newSingleThreadExecutor()
    private val panelExecutor = Executors.newSingleThreadExecutor()
    private val panelQueryExecutor = Executors.newSingleThreadExecutor()
    private val observers = CopyOnWriteArraySet<(Status) -> Unit>()
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
        acceptedGeneration = if (accepted) generation else -1L
        connecting = true; bindStarted = System.nanoTime()
        return object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                executor.execute {
                    // Peek callbacks are queued before its returned revision can be checked.
                    if (generation != bindingGeneration || acceptedGeneration != generation) return@execute
                    connecting = false
                    remote = IHostBootstrap.Stub.asInterface(binder)
                    runCatching {
                        binder.linkToDeath({ executor.execute {
                            if (revokedBinder == binder) finishRevokedStop(binder)
                            else if (remote?.asBinder() == binder) { remote = null; publish("UNKNOWN", "SERVICE_DIED") }
                        } }, 0)
                        performPending()
                        refreshNow()
                    }.onFailure { remote = null; publish("UNKNOWN", "SERVICE_UNREACHABLE") }
                }
            }
            override fun onServiceDisconnected(name: ComponentName) {
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
        // Detach this callback only; never kill an owned backend to recover attachment.
        if (previous != null) runCatching { Shizuku.unbindUserService(args, previous, false) }
    }
    private val received = Shizuku.OnBinderReceivedListener { refresh() }
    private val dead = Shizuku.OnBinderDeadListener {
        executor.execute { if (connecting) abandonConnection(); publish(current.backend, "SHIZUKU_DIED") }
    }
    private val permission = Shizuku.OnRequestPermissionResultListener { _, _ -> refresh() }

    init {
        Shizuku.addBinderReceivedListenerSticky(received)
        Shizuku.addBinderDeadListener(dead)
        Shizuku.addRequestPermissionResultListener(permission)
        executor.scheduleWithFixedDelay({ runCatching { refreshNow() }.onFailure { publish("UNKNOWN", "STATUS_UNAVAILABLE") } }, 0, 1, TimeUnit.SECONDS)
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
    fun stop() { executor.execute {
        requestStop()
    } }

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

    fun panelState(complete: (PanelPowerState?) -> Unit) = panelOperation(0, true,
        { service, context -> service.panelState(context.ownerEpoch, context.projectionToken) }, complete)

    private fun panelOperation(id: Long, query: Boolean, operation: (IHostBootstrap, PanelContext) -> ByteArray, complete: (PanelPowerState?) -> Unit) {
        val atEntry = current
        val context = PanelContext(atEntry.sessionEpoch, atEntry.projectionToken)
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

    fun launchApp(packageName: String, expected: ProjectionContext, complete: (ProjectionDisplayState?) -> Unit) =
        displayOperation(expected, { service, requestId -> service.launchApp(requestId, packageName, expected.ownerEpoch, expected.projectionToken) }, complete)

    fun projectionDisplayState(complete: (ProjectionDisplayState?) -> Unit) =
        projectionDisplayState(current.projectionContext, complete)

    fun projectionDisplayState(expected: ProjectionContext, complete: (ProjectionDisplayState?) -> Unit) =
        displayOperation(expected, { service, _ -> service.projectionDisplayState(expected.ownerEpoch, expected.projectionToken) }, complete)

    private fun displayOperation(expected: ProjectionContext, operation: (IHostBootstrap, Long) -> ByteArray, complete: (ProjectionDisplayState?) -> Unit) {
        // Capture before the first queue hop. The server independently validates these
        // same identities when selecting its exact Bridge/Session, including Binder reuse.
        val service = remote
        val binding = bindingGeneration
        val originalStatus = current
        val requestId = System.nanoTime()
        fun matches(): Boolean {
            val observed = current
            // Home already carries an actual context even before the first status poll.
            // Only that unchanged, not-yet-observed snapshot may defer token validation
            // to the server. A new published snapshot cannot rebind the original intent.
            val sameProjection = observed.projectionContext == expected ||
                (originalStatus.projectionToken.isEmpty() && observed === originalStatus && observed.sessionEpoch == expected.ownerEpoch)
            return service != null && remote === service && bindingGeneration == binding && sameProjection
        }
        displayExecutor.execute {
            val result = runCatching {
                if (!expected.available || !matches()) null else ProjectionDisplayState.from(operation(service!!, requestId))
            }.getOrNull()
            main.post { complete(result.takeIf { matches() && it?.context == expected }) }
        }
    }

    /** PairingPanel.beforeForget hook: completion and the caller's trust deletion run on main. */
    fun stopBeforeForget(complete: () -> Unit) { executor.execute {
        forget.request(complete)
        requestStop()
    } }

    private fun requestStop() {
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
        runCatching {
            val result = when (action) {
                Action.START -> {
                    if (setup() != Setup.AVAILABLE) { publish(current.backend, "START_SETUP_UNAVAILABLE", rejectedEpoch = pendingEpoch); return }
                    val session = File(context.filesDir, "session")
                    val files = BootstrapSessionFiles.read(session)
                    if (files == null) {
                        localDetail = "SESSION_SETUP_REQUIRED"; publish("STOPPED", localDetail, rejectedEpoch = pendingEpoch); return
                    }
                    service.start(files.identity, files.password, files.legacyPeer, files.endpoint, files.pairedPeer, pendingEpoch)
                }
                Action.STOP -> service.stop()
            }
            readStatus(result)
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
            runCatching { readStatus(service.status()) }.onFailure { remote = null; publish("UNKNOWN", "SERVICE_UNREACHABLE") }
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

    private fun readStatus(json: String) {
        val data = JSONObject(json)
        val file = AtomicFile(File(context.filesDir, "bootstrap-status.json"))
        val output = file.startWrite()
        try { output.write(json.toByteArray()); file.finishWrite(output) }
        catch (error: Exception) { file.failWrite(output); throw error }
        val backend = data.getString("state")
        publish(if (pending == Action.STOP && backend == "STOPPED") "STOPPING" else backend,
            data.optString("failure").takeIf { it.isNotBlank() && it != "null" } ?: localDetail,
            data.optString("sessionEpoch").ifEmpty { current.sessionEpoch }, projectionToken = data.optString("projectionToken"))
    }
    private fun publish(backend: String, detail: String? = null, sessionEpoch: String = current.sessionEpoch, rejectedEpoch: String? = null,
        projectionToken: String? = null) {
        val token = if (backend in setOf("SESSION_STARTING", "STREAMING"))
            projectionToken ?: current.projectionToken.takeIf { current.sessionEpoch == sessionEpoch }.orEmpty() else ""
        val value = Status(setup(), backend, detail, sessionEpoch, rejectedEpoch, token)
        if (value == current) return
        current = value
        main.post { observers.forEach { it(value) } }
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
