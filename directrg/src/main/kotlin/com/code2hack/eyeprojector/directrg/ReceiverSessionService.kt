package com.code2hack.eyeprojector.directrg

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import com.code2hack.eyeprojector.protocol.ReceiverInput
import com.code2hack.eyeprojector.directrg.localcamera.forwarding.ReceiverCameraPermissionRequest
import java.io.File
import java.lang.ref.WeakReference
import java.util.concurrent.Executor

/** Keeps paired transport and its real video consumer alive independently of Activity/Surface lifetime. */
class ReceiverSessionService : Service() {
    internal inner class LocalBinder : Binder() {
        val owner get() = this@ReceiverSessionService.owner
        val feature get() = this@ReceiverSessionService.feature
        fun attachCameraPermissionHost(identity: Any, request: (ReceiverCameraPermissionRequest) -> Unit) =
            cameras.attachPermissionHost(identity, request)
        fun detachCameraPermissionHost(identity: Any) = cameras.detachPermissionHost(identity)
        fun cameraPermissionResult(original: ReceiverCameraPermissionRequest, granted: Boolean) =
            cameras.permissionResult(original, granted)
        fun cameraPermissionRequestFailed(original: ReceiverCameraPermissionRequest, error: Throwable) =
            cameras.permissionRequestFailed(original, error)
        fun isCameraPermissionRequestCurrent(original: ReceiverCameraPermissionRequest): Boolean =
            cameras.permissionRequestCurrent(original)
        fun register(scope: ReceiverLaunchScope, activation: String?, task: ActivityManager.AppTask?) {
            val binding = ReceiverTaskBinding(scope, activation)
            feature.registerTask(binding)
            if (task != null) tasks.register(binding) {
                if (task.taskInfo == null) ReceiverTaskRegistry.Presence.ABSENT else ReceiverTaskRegistry.Presence.PRESENT
            }
        }
    }
    private val binder = LocalBinder()
    private lateinit var owner: ReceiverSessionOwner
    private lateinit var cameras: ReceiverCameraOwner
    private lateinit var wakeLock: PowerManager.WakeLock
    private lateinit var wifiLock: WifiManager.WifiLock
    private val main = Handler(Looper.getMainLooper())
    private lateinit var feature: ReceiverActivationStore
    private lateinit var wake: ReceiverWake
    private lateinit var pairingOwner: ReceiverPairingOwner
    private lateinit var tasks: ReceiverTaskRegistry
    private var mediaActive = false
    private var bindingPresent = false
    private var pendingUnpairedStop = false
    private var destroying = false
    @Volatile private var cameraForeground = false
    private val pairingChanged: () -> Unit = { main.post { updateResources(); updateNotification() } }
    private val reconcile = object : Runnable {
        override fun run() {
            owner.retryCancellation(); wake.retryPending(); reconcileTasks()
            main.postDelayed(this, 1000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Paired projection", NotificationManager.IMPORTANCE_LOW))
        wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:projection")
        @Suppress("DEPRECATION")
        val lock = (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "$packageName:projection")
        wifiLock = lock
        feature = ReceiverActivationStore(File(filesDir, "receiver-activation.properties"))
        tasks = ReceiverTaskRegistry(feature.state.tasks.values)
        wake = ReceiverWake(this, feature)
        pairingOwner = ReceiverPairingOwner.get(this)
        pairingOwner.observe(pairingChanged); pairingOwner.start()
        val originalService = WeakReference(this)
        val originalMain = main
        cameras = ReceiverCameraOwner(applicationContext, { action -> originalMain.post { action() } }, {
            val source = originalService.get() ?: error("Original foreground service unavailable")
            source.promoteCameraForeground()
        }, { originalMain.post { originalService.get()?.cameraResourcesChanged() } })
        owner = ReceiverSessionOwner(File(filesDir, "session"), applicationContext,
            { action -> originalMain.post { action() } }, { originalService.get()?.updateNotification() },
            { active -> originalService.get()?.resources(active) },
            { source, input -> originalService.get()?.authenticatedControl(source, input) },
            feature = feature, wake = wake::request, cameraOwner = cameras)
        startForeground(NOTIFICATION, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        updateResources(); owner.start(); main.post(reconcile)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == DISCONNECT || intent?.action == EXIT) {
            // PendingIntent identity carries its original H; stale notification actions cannot cancel H2.
            val h = intent.getStringExtra(ReceiverWake.ACTIVATION)
            val trigger = intent.getStringExtra(TRIGGER)
            val originalScope = intent.getStringExtra(SCOPE)?.let { id ->
                ReceiverLaunchScope(id, trigger.orEmpty(), intent.getIntExtra(TASK, -1))
            } ?: trigger?.let { feature.state.triggers[it]?.scope }
            owner.disconnect(scope = originalScope, activationId = h)
        }
        // Null intents restore admission/tombstones only; never synthesize a launch or replay Start.
        return START_STICKY
    }
    override fun onBind(intent: Intent?): IBinder { bindingPresent = true; pendingUnpairedStop = false; return binder }
    override fun onUnbind(intent: Intent?): Boolean {
        bindingPresent = false
        if (::pairingOwner.isInitialized && runCatching { pairingOwner.pairing.store.pairedPeer() == null }.getOrDefault(false)) {
            pendingUnpairedStop = true
            // Retire the captured media first; a later actual bind can still use this live owner.
            owner.disconnect(notifyPeer = false)
            finishControlledStop()
        }
        return super.onUnbind(intent)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        reconcileTasks()
        super.onTaskRemoved(rootIntent)
    }
    private fun reconcileTasks() {
        tasks.reconcile().forEach { binding ->
            try {
                // Retain observed absence until its original scope is durably retired.
                feature.cancel(binding.scope, binding.activation)
                tasks.acknowledged(binding)
                owner.disconnect(scope = binding.scope, activationId = binding.activation)
            } catch (_: Exception) { /* Exact absent registration remains for the next ordered retry. */ }
        }
    }

    private fun resources(active: Boolean) {
        mediaActive = active
        if (destroying) return
        updateResources(); finishControlledStop()
    }
    private fun promoteCameraForeground() {
        check(!destroying) { "Original service retired before foreground submission" }
        // Actual platform admission is authoritative; no inferred background eligibility.
        startForeground(NOTIFICATION, notification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        cameraForeground = true
    }
    private fun cameraResourcesChanged() {
        if (destroying) return
        updateResources(); updateNotification(); finishControlledStop()
    }
    private fun finishControlledStop() {
        if (destroying || !pendingUnpairedStop || bindingPresent) return
        val unpaired = runCatching { pairingOwner.pairing.store.pairedPeer() == null }.getOrDefault(false)
        if (!unpaired) { pendingUnpairedStop = false; return }
        if (owner.hasRetainedResources() || cameras.hasResources()) return
        pendingUnpairedStop = false
        stopSelf()
    }
    private fun updateResources() {
        if (destroying) return
        val paired = if (::pairingOwner.isInitialized) runCatching { pairingOwner.pairing.store.pairedPeer() != null }.getOrDefault(false) else false
        val cameraResources = ::cameras.isInitialized && cameras.hasResources()
        if (paired || mediaActive || cameraResources) {
            if (!wakeLock.isHeld) wakeLock.acquire()
            if (!wifiLock.isHeld) wifiLock.acquire()
        } else {
            if (wifiLock.isHeld) wifiLock.release()
            if (wakeLock.isHeld) wakeLock.release()
        }
        if (cameraForeground && !cameraResources && !destroying) {
            try {
                startForeground(NOTIFICATION, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
                cameraForeground = false
            } catch (error: Exception) { android.util.Log.w("EyeProjector", "Camera foreground demotion failed", error) }
        }
    }

    /** Called for current authenticated control even with no Activity; #63 can extend this owner seam. */
    private fun authenticatedControl(source: ProjectionSession, input: ReceiverInput) {
        // Existing operation events carry no wake request. Detached input is canceled, never replayed.
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, ReceiverActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val captured = feature.directive?.activationId
        val trigger = feature.state.currentTrigger
        val disconnect = PendingIntent.getService(this, captured?.hashCode() ?: 1,
            Intent(this, ReceiverSessionService::class.java).setAction(DISCONNECT)
                .setData(android.net.Uri.parse("eyeprojector-disconnect:${captured.orEmpty()}/${trigger.orEmpty()}"))
                .putExtra(ReceiverWake.ACTIVATION, captured)
                .putExtra(TRIGGER, trigger), PendingIntent.FLAG_IMMUTABLE)
        val state = if (::owner.isInitialized) owner.snapshot() else null
        val paired = runCatching { pairingOwner.pairing.store.pairedPeer() != null }.getOrDefault(false)
        val message = when {
            !paired -> "Unpaired; open EyeProjector to pair with the Phone"
            state?.state == "STREAMING" -> "Projection displayed"
            state?.state == "DISPLAY_DETACHED" -> "Projection retained while display sleeps"
            state?.session != null || state?.requested == true -> "Connecting to paired Phone"
            state?.state == "CANCEL_PENDING" -> "Saving projection cancellation"
            else -> "Paired standby; waiting for activation"
        }
        return Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("EyeProjector").setContentText(message).setContentIntent(open).setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Disconnect", disconnect).build()).build()
    }
    private fun updateNotification() {
        if (destroying) return
        if (!::feature.isInitialized) return
        if (::wake.isInitialized && ::owner.isInitialized) wake.reconcile(feature.presentation,
            owner.snapshot().state in setOf("RENDER_FAILED", "RENDER_SETUP_FAILED", "PEER_FAILED"))
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification())
    }
    override fun onDestroy() {
        destroying = true
        main.removeCallbacks(reconcile)
        val physical = ::cameras.isInitialized && (cameras.hasResources() ||
            ::owner.isInitialized && owner.hasCameraRetirementDuty())
        val closing = if (physical && ::owner.isInitialized) ClosingResources(owner, cameras, wifiLock, wakeLock)
            .also { synchronized(closingResources) { closingResources.add(it) } } else null
        if (closing != null) owner.afterResourcesReleased { closing.mediaClosed() }
        try { if (::owner.isInitialized) owner.close() }
        catch (error: Throwable) { if (closing == null) throw error else closing.failed(error) }
        finally {
            if (::cameras.isInitialized) try {
                cameras.closeAsync(Executor { it.run() }) { observation ->
                    if (!observation.resourcesHeld) closing?.cameraClosed()
                }
            } catch (error: Throwable) { closing?.failed(error) }
            if (closing == null) {
                if (::wake.isInitialized) wake.release()
                if (::pairingOwner.isInitialized) { pairingOwner.remove(pairingChanged); pairingOwner.close() }
                if (::wifiLock.isInitialized && wifiLock.isHeld) wifiLock.release()
                if (::wakeLock.isInitialized && wakeLock.isHeld) wakeLock.release()
                super.onDestroy()
            } else {
                try { if (::wake.isInitialized) wake.release() } catch (error: Throwable) { closing.failed(error) }
                try { if (::pairingOwner.isInitialized) { pairingOwner.remove(pairingChanged); pairingOwner.close() } }
                catch (error: Throwable) { closing.failed(error) }
                try { super.onDestroy() }
                finally { closing.hostCleanupSubmitted() }
            }
        }
    }
    /** Application-context original owners survive abnormal service destruction until observed closure. */
    private class ClosingResources(val original: ReceiverSessionOwner, val cameras: ReceiverCameraOwner,
        val wifi: WifiManager.WifiLock, val wake: PowerManager.WakeLock) {
        private var media = false
        private var camera = false
        private var hostCleanup = false
        private var releaseClaimed = false
        private val failures = ArrayList<Throwable>()
        fun failed(error: Throwable) { synchronized(this) { failures.add(error) } }
        fun mediaClosed() { synchronized(this) { media = true }; releaseIfKnown() }
        fun cameraClosed() { synchronized(this) { camera = true }; releaseIfKnown() }
        fun hostCleanupSubmitted() { synchronized(this) { hostCleanup = true }; releaseIfKnown() }
        private fun releaseIfKnown() {
            val release = synchronized(this) {
                if (!media || !camera || !hostCleanup || releaseClaimed) false else { releaseClaimed = true; true }
            }
            if (!release) return
            try { if (wifi.isHeld) wifi.release() } catch (error: Throwable) { failed(error) }
            try { if (wake.isHeld) wake.release() } catch (error: Throwable) { failed(error) }
            if (synchronized(this) { failures.isEmpty() }) synchronized(closingResources) { closingResources.remove(this) }
        }
    }
    companion object {
        private val closingResources = mutableSetOf<ClosingResources>()
        private const val CHANNEL = "paired-projection"
        private const val NOTIFICATION = 62
        private const val TRIGGER = "eyeprojector.trigger"
        private const val SCOPE = "eyeprojector.scope"
        private const val TASK = "eyeprojector.task"
        internal fun cancelIntent(context: android.content.Context, scope: ReceiverLaunchScope, activation: String?) =
            Intent(context, ReceiverSessionService::class.java).setAction(EXIT)
                .putExtra(ReceiverWake.ACTIVATION, activation).putExtra(TRIGGER, scope.trigger)
                .putExtra(SCOPE, scope.id).putExtra(TASK, scope.taskId)
        const val DISCONNECT = "com.code2hack.eyeprojector.directrg.DISCONNECT"
        const val EXIT = "com.code2hack.eyeprojector.directrg.EXIT"
    }
}
