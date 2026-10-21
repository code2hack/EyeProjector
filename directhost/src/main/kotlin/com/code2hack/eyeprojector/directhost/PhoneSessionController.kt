package com.code2hack.eyeprojector.directhost

import android.annotation.SuppressLint
import android.content.Context
import com.code2hack.eyeprojector.protocol.*
import com.code2hack.eyeprojector.directhost.scale.*
import java.io.File

/** App-scoped owner survives panel recreation and supplies the same commands to later Phone controls. */
class PhoneSessionController private constructor(context: Context) {
    val bootstrap = HostBootstrap.get(context)
    val store = PeerStore(File(context.filesDir, "session"))
    private val attempts = SessionAttemptStore(File(context.filesDir, "session-attempts.properties"))
    private val controller = SessionController({ epoch -> bootstrap.start(epoch) }, { bootstrap.stop() }, initialPhase = SessionPhase.UNKNOWN,
        initialCorrelation = attempts.read(), retainCorrelation = attempts::write)
    val pairing = LanPairing(store, PeerRole.PHONE, {}, { socket ->
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
        bootstrap.observe { state ->
            controller.observeBackend(state.sessionEpoch, state.backend, state.detail.orEmpty(), state.rejectedEpoch)
            appScales.contextChanged(state.projectionContext, state.backend in setOf("SESSION_STARTING", "STREAMING"))
        }
        pairing.start()
    }

    fun state(): SessionState = controller.state()

    fun start(): SessionState = start(null, null)
    private fun start(expectedEpoch: String?, proposedEpoch: String?): SessionState {
        val setup = bootstrap.status().setup
        return controller.start(expectedEpoch, proposedEpoch, setup.takeIf { it != HostBootstrap.Setup.AVAILABLE }?.name)
    }

    fun stop(): SessionState = controller.stop()

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

    fun panelState(complete: (PanelPowerState?) -> Unit) = bootstrap.panelState(complete)

    private fun command(request: SessionControl.Request): SessionState {
        val unavailable = if (request.operation == SessionControl.Operation.START)
            bootstrap.status().setup.takeIf { it != HostBootstrap.Setup.AVAILABLE }?.name else null
        return controller.command(request, unavailable)
    }

    companion object {
        @SuppressLint("StaticFieldLeak") // Only the applicationContext is passed to app-scoped owners.
        @Volatile private var instance: PhoneSessionController? = null
        fun get(context: Context): PhoneSessionController = instance ?: synchronized(this) {
            instance ?: PhoneSessionController(context.applicationContext).also { instance = it }
        }
    }
}
