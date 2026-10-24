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
import java.io.File

/** Keeps paired transport and its real video consumer alive independently of Activity/Surface lifetime. */
class ReceiverSessionService : Service() {
    internal inner class LocalBinder : Binder() {
        val owner get() = this@ReceiverSessionService.owner
        val feature get() = this@ReceiverSessionService.feature
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
    private lateinit var wakeLock: PowerManager.WakeLock
    private lateinit var wifiLock: WifiManager.WifiLock
    private val main = Handler(Looper.getMainLooper())
    private lateinit var feature: ReceiverActivationStore
    private lateinit var wake: ReceiverWake
    private lateinit var pairingOwner: ReceiverPairingOwner
    private lateinit var tasks: ReceiverTaskRegistry
    private var mediaActive = false
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
        owner = ReceiverSessionOwner(File(filesDir, "session"), applicationContext,
            { action -> main.post { action() } }, { updateNotification() }, ::resources, ::authenticatedControl,
            feature = feature, wake = wake::request)
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
    override fun onBind(intent: Intent?): IBinder = binder
    override fun onUnbind(intent: Intent?): Boolean {
        if (::pairingOwner.isInitialized && runCatching { pairingOwner.pairing.store.pairedPeer() == null }.getOrDefault(false)) stopSelf()
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

    private fun resources(active: Boolean) { mediaActive = active; updateResources() }
    private fun updateResources() {
        val paired = if (::pairingOwner.isInitialized) runCatching { pairingOwner.pairing.store.pairedPeer() != null }.getOrDefault(false) else false
        if (paired || mediaActive) {
            if (!wakeLock.isHeld) wakeLock.acquire()
            if (!wifiLock.isHeld) wifiLock.acquire()
        } else {
            if (wifiLock.isHeld) wifiLock.release()
            if (wakeLock.isHeld) wakeLock.release()
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
        if (!::feature.isInitialized) return
        if (::wake.isInitialized && ::owner.isInitialized) wake.reconcile(feature.presentation,
            owner.snapshot().state in setOf("RENDER_FAILED", "RENDER_SETUP_FAILED", "PEER_FAILED"))
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification())
    }
    override fun onDestroy() {
        main.removeCallbacks(reconcile)
        try { if (::owner.isInitialized) owner.close() }
        finally {
            if (::wake.isInitialized) wake.release()
            if (::pairingOwner.isInitialized) { pairingOwner.remove(pairingChanged); pairingOwner.close() }
            if (::wifiLock.isInitialized && wifiLock.isHeld) wifiLock.release()
            if (::wakeLock.isInitialized && wakeLock.isHeld) wakeLock.release()
            super.onDestroy()
        }
    }
    companion object {
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
