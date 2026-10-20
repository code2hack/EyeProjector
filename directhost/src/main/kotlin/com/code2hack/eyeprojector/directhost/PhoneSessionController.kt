package com.code2hack.eyeprojector.directhost

import android.annotation.SuppressLint
import android.content.Context
import com.code2hack.eyeprojector.protocol.*
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

    init {
        bootstrap.observe { state ->
            state.rejectedEpoch?.let { controller.rejected(it, state.detail ?: "START_REJECTED") }
            controller.restore(state.sessionEpoch, state.backend)
            controller.backend(state.sessionEpoch, state.backend, state.detail.orEmpty())
            if (state.backend == "FAILED" || state.detail == "SESSION_SETUP_REQUIRED")
                controller.backend(state.sessionEpoch, "FAILED", state.detail.orEmpty())
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
