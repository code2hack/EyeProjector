package com.code2hack.eyeprojector.directhost.notifications

import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * System binding is the sole ingress. From framework-supplied notification objects, the project
 * extracts package/key/postTime only; it never inspects contents/flags/intents or cancels them.
 */
class PhoneNotificationListener : NotificationListenerService() {
    private var runtime: PhoneNotificationRuntime? = null
    private var connection: NotificationListenerConnection? = null

    override fun onListenerConnected() {
        super.onListenerConnected()
        val owner = PhoneNotificationRuntime.get(applicationContext)
        val original = NotificationListenerConnection()
        runtime = owner
        connection = original // Installed before runtime registration/publication can reenter.
        owner.connected(original) { packages ->
            val snapshot = activeNotifications
            if (snapshot == null) NotificationSnapshot.Unavailable
            else NotificationSnapshot.Available(snapshot.mapNotNull { notification ->
                val packageName = notification.packageName
                if (packageName in packages) NotificationRecord(packageName, notification.key, notification.postTime) else null
            })
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) = receive(sbn, false)
    override fun onNotificationRemoved(sbn: StatusBarNotification) = receive(sbn, true)
    private fun receive(notification: StatusBarNotification, removed: Boolean) {
        val original = connection ?: return
        val owner = runtime ?: return
        val packageName = notification.packageName
        val metadata = { NotificationRecord(packageName, notification.key, notification.postTime) }
        if (removed) owner.removed(original, packageName, metadata) else owner.posted(original, packageName, metadata)
    }

    override fun onListenerDisconnected() { detach(); super.onListenerDisconnected() }
    override fun onUnbind(intent: Intent?): Boolean { detach(); return super.onUnbind(intent) }
    override fun onDestroy() { detach(); super.onDestroy() }
    private fun detach() {
        val original = connection ?: return
        val owner = runtime
        connection = null
        runtime = null
        owner?.disconnected(original)
    }
}
