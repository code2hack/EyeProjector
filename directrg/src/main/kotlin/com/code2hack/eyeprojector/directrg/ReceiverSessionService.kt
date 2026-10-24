package com.code2hack.eyeprojector.directrg

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
    internal inner class LocalBinder : Binder() { val owner get() = this@ReceiverSessionService.owner }
    private val binder = LocalBinder()
    private lateinit var owner: ReceiverSessionOwner
    private lateinit var wakeLock: PowerManager.WakeLock
    private lateinit var wifiLock: WifiManager.WifiLock
    private val main = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Paired projection", NotificationManager.IMPORTANCE_LOW))
        wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:projection")
        @Suppress("DEPRECATION")
        val lock = (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "$packageName:projection")
        wifiLock = lock
        owner = ReceiverSessionOwner(File(filesDir, "session"), applicationContext,
            { action -> main.post { action() } }, { updateNotification() }, ::resources, ::authenticatedControl)
        startForeground(NOTIFICATION, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        owner.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == DISCONNECT) owner.disconnect()
        if (intent?.action == EXIT) stopSelf()
        return START_NOT_STICKY // Process loss is a real session failure; never claim retained continuity.
    }
    override fun onBind(intent: Intent?): IBinder = binder

    private fun resources(active: Boolean) {
        if (active) {
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
        val disconnect = PendingIntent.getService(this, 1, Intent(this, ReceiverSessionService::class.java).setAction(DISCONNECT), PendingIntent.FLAG_IMMUTABLE)
        val state = if (::owner.isInitialized) owner.snapshot() else null
        val message = when {
            state?.state == "STREAMING" -> "Projection displayed"
            state?.state == "DISPLAY_DETACHED" -> "Projection retained while display sleeps"
            state?.session != null || state?.requested == true -> "Connecting to paired Phone"
            else -> "Projection disconnected"
        }
        return Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("EyeProjector").setContentText(message).setContentIntent(open).setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Disconnect", disconnect).build()).build()
    }
    private fun updateNotification() {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification())
    }
    override fun onDestroy() {
        try { if (::owner.isInitialized) owner.close() }
        finally { if (::wakeLock.isInitialized && ::wifiLock.isInitialized) resources(false); super.onDestroy() }
    }
    companion object {
        private const val CHANNEL = "paired-projection"
        private const val NOTIFICATION = 62
        const val DISCONNECT = "com.code2hack.eyeprojector.directrg.DISCONNECT"
        const val EXIT = "com.code2hack.eyeprojector.directrg.EXIT"
    }
}
