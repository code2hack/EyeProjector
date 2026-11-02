package com.code2hack.eyeprojector.directrg

/** Framework/UI boundary; production Disconnect bodies are inserted verbatim by the runner. */
internal open class DisconnectActivityBoundary { open fun onDestroy() {} }
internal class ReceiverSessionService {
    class LocalBinder(val owner: ReceiverSessionOwner, val feature: ReceiverActivationStore) {
        fun detachCameraPermissionHost(host: Any) {}
    }
    companion object { fun cancelIntent(context: Any, scope: ReceiverLaunchScope, activation: String?) = Unit }
}
internal class ReceiverDisconnectSource(
    private var owner: ReceiverSessionOwner?,
    feature: ReceiverActivationStore,
    scope: ReceiverLaunchScope,
    private var visibleActivation: String? = null,
) : DisconnectActivityBoundary() {
    private lateinit var launchScope: ReceiverLaunchScope
    init { launchScope = scope }
    private var serviceBinder = owner?.let { ReceiverSessionService.LocalBinder(it, feature) }
    private var session = owner?.session
    private val sessionView = object : ReceiverSessionOwner.View {
        override fun state(snapshot: ReceiverSessionOwner.Snapshot) { session = snapshot.session }
        override fun input(source: ProjectionSession, event: com.code2hack.eyeprojector.protocol.ReceiverInput) {}
    }
    private var connectBeforeBinding = false
    private var disconnectBeforeBinding = false
    private var bindingValid = true
    private var resumed = true
    private var bound = false
    private val cameraPermissionHost = Any()
    private val cameraPermissionRequests = mutableMapOf<Any, Any>()
    private val serviceConnection = Any()
    private class Part { fun stop() {}; fun close() {} }
    private val nativeSwipeInput = Part(); private val longTouch = Part(); private val padContact = Part()
    private val pairingPanel = Part()
    private var pendingExit: Pair<ReceiverLaunchScope, String?>? = null
    var isFinishing = true
    var finished = false
    var beforeCancel: () -> Unit = {}
    private fun cancelInput() { beforeCancel() }
    private fun cancelInputOperation(cancelFunction: Boolean = true, reason: String = "test"): Pair<Long, Unit>? {
        beforeCancel(); return 0L to Unit
    }
    private fun unbindService(connection: Any) {}
    private fun startForegroundService(intent: Unit) {}
    private fun finish() { finished = true }
    fun finishForTest() = finishProjection()
    // ACTUAL_DISCONNECT_METHODS
}
