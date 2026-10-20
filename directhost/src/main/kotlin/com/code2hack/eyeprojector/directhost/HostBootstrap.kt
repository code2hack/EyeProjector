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
    private val executor = Executors.newSingleThreadScheduledExecutor()
    private val observers = CopyOnWriteArraySet<(Status) -> Unit>()
    @Volatile private var current = Status(Setup.UNAVAILABLE, "UNKNOWN")
    private var remote: IHostBootstrap? = null
    private var pending: Action? = null
    private var localDetail: String? = null
    private var connecting = false
    private var bindStarted = 0L
    // A new installed APK gets a new shell process rather than reusing loaded old dex.
    private val installedRevision = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
    private val args = Shizuku.UserServiceArgs(ComponentName(context, ShizukuHostService::class.java))
        .tag("eyeprojector-host").processNameSuffix("projection_host").daemon(true)
        .version((installedRevision xor (installedRevision ushr 32)).toInt())

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            executor.execute {
                connecting = false
                remote = IHostBootstrap.Stub.asInterface(binder)
                binder.linkToDeath({ executor.execute {
                    if (remote?.asBinder() == binder) { remote = null; publish("UNKNOWN", "SERVICE_DIED") }
                } }, 0)
                performPending()
                refreshNow()
            }
        }
        override fun onServiceDisconnected(name: ComponentName) {
            executor.execute {
                if (remote?.asBinder()?.isBinderAlive == false) {
                    remote = null; publish("UNKNOWN", "SERVICE_DISCONNECTED")
                }
                connecting = false
            }
        }
    }
    private val received = Shizuku.OnBinderReceivedListener { refresh() }
    private val dead = Shizuku.OnBinderDeadListener {
        executor.execute { connecting = false; publish(current.backend, "SHIZUKU_DIED") }
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
        if (setup() != Setup.AVAILABLE) { publish(current.backend); return@execute }
        localDetail = null
        pending = Action.START
        if (remote != null) performPending() else bind()
    } }
    fun stop() { executor.execute {
        localDetail = null
        pending = Action.STOP
        if (remote != null) performPending()
        else if (setup() == Setup.AVAILABLE) bind()
        else publish("UNKNOWN", "RESTORE_SHIZUKU_TO_VERIFY_STOP")
    } }

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
        if (connecting) return
        connecting = true; bindStarted = System.nanoTime()
        publish(if (pending == Action.STOP) "STOPPING" else "STARTING")
        runCatching { Shizuku.bindUserService(args, connection) }.onFailure {
            connecting = false; publish("UNKNOWN", "BIND_FAILED")
        }
    }

    private fun performPending() {
        val service = remote ?: return
        val action = pending ?: return
        pending = null
        runCatching {
            val result = when (action) {
                Action.START -> {
                    if (setup() != Setup.AVAILABLE) { publish(current.backend); return }
                    val session = File(context.filesDir, "session")
                    val files = listOf("identity.p12", "password", "peer.crt", "endpoint.properties").map { File(session, it) }
                    if (files.any { !it.isFile }) {
                        localDetail = "SESSION_SETUP_REQUIRED"; publish("STOPPED", localDetail); return
                    }
                    service.start(files[0].readBytes(), files[1].readBytes(), files[2].readBytes(), files[3].readBytes())
                }
                Action.STOP -> service.stop()
            }
            readStatus(result)
            if (action == Action.STOP && current.backend == "STOPPED") {
                val binder = service.asBinder()
                service.destroy()
                remote = null
                publish(if (binder.isBinderAlive) "STOPPING" else "STOPPED")
            }
        }.onFailure {
            // destroy exits before replying; binder death confirms that exact process ended.
            if (action == Action.STOP && !service.asBinder().isBinderAlive) {
                remote = null; publish("STOPPED")
            } else publish("UNKNOWN", "OPERATION_FAILED")
        }
    }

    private fun refreshNow() {
        val service = remote
        if (service != null) {
            runCatching { readStatus(service.status()) }.onFailure { remote = null; publish("UNKNOWN", "SERVICE_UNREACHABLE") }
        } else if (!connecting && setup() == Setup.AVAILABLE) {
            if (pending != null) { bind(); return }
            // Reattach after app recreation without silently starting projection.
            runCatching { Shizuku.peekUserService(args, connection) }.onSuccess { version ->
                if (version != -1) { connecting = true; bindStarted = System.nanoTime() }
                else { publish("STOPPED", localDetail); return }
            }
            publish(current.backend, current.detail)
        } else {
            val slow = connecting && System.nanoTime() - bindStarted > TimeUnit.SECONDS.toNanos(8)
            publish(current.backend, if (slow) "BIND_PENDING_REOPEN_SHIZUKU" else current.detail)
        }
    }

    private fun readStatus(json: String) {
        val data = JSONObject(json)
        val file = AtomicFile(File(context.filesDir, "bootstrap-status.json"))
        val output = file.startWrite()
        try { output.write(json.toByteArray()); file.finishWrite(output) }
        catch (error: Exception) { file.failWrite(output); throw error }
        publish(data.getString("state"), data.optString("failure").takeIf { it.isNotBlank() && it != "null" } ?: localDetail)
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
