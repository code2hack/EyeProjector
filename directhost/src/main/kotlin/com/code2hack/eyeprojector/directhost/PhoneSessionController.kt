package com.code2hack.eyeprojector.directhost

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.code2hack.eyeprojector.directhost.widget.PhoneWidgetUpdates
import com.code2hack.eyeprojector.protocol.*
import java.io.File
import java.util.concurrent.CopyOnWriteArraySet

/** App-scoped owner survives panel recreation and supplies the same commands to later Phone controls. */
class PhoneSessionController private constructor(context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private val stateObservers = CopyOnWriteArraySet<() -> Unit>()
    @Volatile private var lastReadState: SessionState? = null
    val bootstrap = HostBootstrap.get(context)
    val store = PeerStore(File(context.filesDir, "session"))
    private val attempts = SessionAttemptStore(File(context.filesDir, "session-attempts.properties"))
    private val controller = SessionController({ epoch -> bootstrap.start(epoch) }, { bootstrap.stop() }, initialPhase = SessionPhase.UNKNOWN,
        initialCorrelation = attempts.read(), retainCorrelation = attempts::write)
    val pairing = LanPairing(store, PeerRole.PHONE, ::invalidateState, { socket ->
        SessionControl.serve(store.directory, socket, ::command)
    })

    init {
        bootstrap.observe { state ->
            controller.observeBackend(state.sessionEpoch, state.backend, state.detail.orEmpty(), state.rejectedEpoch)
            invalidateState()
        }
        pairing.start()
    }

    fun state(): SessionState = controller.state().also {
        if (it != lastReadState) { lastReadState = it; invalidateState() }
    }

    /** Invalidation only: consumers read the latest state at main-thread delivery, outside core locks. */
    fun observeState(observer: () -> Unit) { stateObservers.add(observer); invalidateState() }
    fun removeStateObserver(observer: () -> Unit) { stateObservers.remove(observer) }
    private fun invalidateState() {
        main.post { stateObservers.forEach { observer -> runCatching { observer() } } }
    }

    fun start(): SessionState = start(null, null)
    fun start(expectedEpoch: String): SessionState = start(expectedEpoch, null)
    private fun start(expectedEpoch: String?, proposedEpoch: String?): SessionState {
        val setup = bootstrap.status().setup
        return try {
            controller.start(expectedEpoch, proposedEpoch, setup.takeIf { it != HostBootstrap.Setup.AVAILABLE }?.name)
        } finally { invalidateState() }
    }

    fun stop(): SessionState = try { controller.stop() } finally { invalidateState() }
    fun stop(expectedEpoch: String): SessionState = try { controller.stop(expectedEpoch) } finally { invalidateState() }

    /** Both launchers consume the owning backend's actual display/generation and result. */
    fun launchApp(packageName: String, complete: (ProjectionDisplayState?) -> Unit) = bootstrap.launchApp(packageName, complete)
    fun launchApp(packageName: String, expected: ProjectionContext, complete: (ProjectionDisplayState?) -> Unit) = bootstrap.launchApp(packageName, expected, complete)
    fun projectionDisplayState(complete: (ProjectionDisplayState?) -> Unit) = bootstrap.projectionDisplayState(complete)

    fun requestPanelOff(complete: (PanelPowerState?) -> Unit) = bootstrap.requestPanelOff(complete)
    fun requestPanelOff(expected: ProjectionContext, complete: (PanelPowerState?) -> Unit) = bootstrap.requestPanelOff(expected, complete)

    fun panelState(complete: (PanelPowerState?) -> Unit) = bootstrap.panelState(complete)

    private fun command(request: SessionControl.Request): SessionState {
        val unavailable = if (request.operation == SessionControl.Operation.START)
            bootstrap.status().setup.takeIf { it != HostBootstrap.Setup.AVAILABLE }?.name else null
        return try { controller.command(request, unavailable) } finally { invalidateState() }
    }

    companion object {
        @SuppressLint("StaticFieldLeak") // Only the applicationContext is passed to app-scoped owners.
        @Volatile private var instance: PhoneSessionController? = null
        fun get(context: Context): PhoneSessionController = (instance ?: synchronized(this) {
            instance ?: PhoneSessionController(context.applicationContext).also { instance = it }
        }).also { PhoneWidgetUpdates.attach(context.applicationContext, it) }
    }
}
