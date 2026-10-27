package com.code2hack.eyeprojector.directhost.hid

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.code2hack.eyeprojector.directhost.HostActivity

/** Keeps the app's capture callback alive independently of projection and its Activity. */
class PhoneHidPinService : Service() {
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Pinned HID", NotificationManager.IMPORTANCE_LOW))
        startForeground(NOTIFICATION, notification("Restoring pinned device"))
        PhoneHidRuntime.get(this).pin.serviceStarted(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val pin = PhoneHidRuntime.get(this).pin
        if (intent?.action == UNPIN) pin.unpinAndReset() else pin.refresh()
        return START_STICKY
    }

    internal fun show(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification(text))
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, HostActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val release = PendingIntent.getService(this, 1, Intent(this, PhoneHidPinService::class.java).setAction(UNPIN),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("EyeProjector pinned HID")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Unpin and reset", release).build())
            .build()
    }

    override fun onDestroy() {
        PhoneHidRuntime.get(this).pin.serviceStopped(this)
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null

    private companion object {
        const val CHANNEL = "phone_hid_pin"
        const val NOTIFICATION = 10079
        const val UNPIN = "com.code2hack.eyeprojector.directhost.hid.UNPIN"
    }
}
