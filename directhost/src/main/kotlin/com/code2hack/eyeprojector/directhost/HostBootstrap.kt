package com.code2hack.eyeprojector.directhost

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
import org.json.JSONObject
import rikka.shizuku.Shizuku

/** One app-scoped route, also used by subsequent launcher/widget integration. */
class HostBootstrap private constructor(private val context: Context) {
    enum class Setup { MISSING, UNAVAILABLE, UPDATE_REQUIRED, AUTHORIZATION_REQUIRED, SHELL_REQUIRED, AVAILABLE }
    data class Status(val setup: Setup, val backend: String, val detail: String? = null)
    private enum class Action { START, STOP }
    private val main = Handler(Looper.getMainLooper())
    private val forget = HostForgetCompletion { main.post(it) }
    private val executor = Executors.newSingleThreadScheduledExecutor()
    private val observers = CopyOnWriteArraySet<(Status) -> Unit>()
    @Volatile private var current = Status(Setup.UNAVAILABLE, "UNKNOWN")
    private var remote: IHostBootstrap? = null
    private var revokedBinder: IBinder? = null
    private var pending: Action? = null
    private var localDetail: String? = null
    private var connecting = false
    private var bindStarted = 0L
    private var bindingGeneration = 0L
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
    fun removeObserver(observer: (Status) -> Unit) { observers.remove(observer) }
    fun refresh() { executor.execute { runCatching { refreshNow() }.onFailure { publish("UNKNOWN", "STATUS_UNAVAILABLE") } } }
    fun requestAuthorization() {
        runCatching { if (Shizuku.pingBinder()) Shizuku.requestPermission(5001) }.onFailure { refresh() }
    }
    fun start() { executor.execute {
        if (forget.pending()) { publish(current.backend, "FORGET_PENDING"); return@execute }
        if (revokedBinder != null || pending == Action.STOP) { publish("STOPPING"); return@execute }
        if (setup() != Setup.AVAILABLE) { publish(current.backend); return@execute }
        localDetail = null
        pending = Action.START
        if (remote != null) performPending() else bind()
    } }
    fun stop() { executor.execute {
        requestStop()
    } }

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
                    if (setup() != Setup.AVAILABLE) { publish(current.backend); return }
                    val session = File(context.filesDir, "session")
                    val files = BootstrapSessionFiles.read(session)
                    if (files == null) {
                        localDetail = "SESSION_SETUP_REQUIRED"; publish("STOPPED", localDetail); return
                    }
                    service.start(files.identity, files.password, files.legacyPeer, files.endpoint, files.pairedPeer)
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
            data.optString("failure").takeIf { it.isNotBlank() && it != "null" } ?: localDetail)
    }
    private fun publish(backend: String, detail: String? = null) {
        val value = Status(setup(), backend, detail)
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
