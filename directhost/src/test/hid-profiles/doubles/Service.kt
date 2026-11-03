package android.app

import android.content.Context
import android.content.Intent
import android.os.IBinder

open class Service : Context() {
    open fun onCreate() = Unit
    open fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
    open fun onDestroy() = Unit
    open fun onBind(intent: Intent?): IBinder? = null
    fun startForeground(id: Int, notification: Notification) { Notifications.manager.notify(id, notification) }
    fun stopForeground(flags: Int) = Unit
    fun stopSelf() = Unit
    companion object { const val STOP_FOREGROUND_REMOVE = 1; const val START_STICKY = 1 }
}
object Notifications { val manager = NotificationManager() }
class NotificationManager {
    var latest: Notification? = null
    fun createNotificationChannel(channel: NotificationChannel) = Unit
    fun notify(id: Int, notification: Notification) { latest = notification }
    companion object { const val IMPORTANCE_LOW = 2 }
}
class NotificationChannel(id: String, name: String, importance: Int)
class PendingIntent(val intent: Intent) {
    companion object {
        const val FLAG_UPDATE_CURRENT = 1; const val FLAG_IMMUTABLE = 2
        fun getActivity(context: Context, request: Int, intent: Intent, flags: Int) = PendingIntent(intent)
        fun getService(context: Context, request: Int, intent: Intent, flags: Int) = PendingIntent(intent)
    }
}
class Notification(val actions: List<Action>) {
    class Action(val text: String, val pending: PendingIntent) {
        class Builder(icon: Any?, val text: String, val pending: PendingIntent) {
            fun build() = Action(text, pending)
        }
    }
    class Builder(context: Context, channel: String) {
        private val actions = mutableListOf<Action>()
        fun setSmallIcon(icon: Int) = this
        fun setContentTitle(text: String) = this
        fun setContentText(text: String) = this
        fun setContentIntent(pending: PendingIntent) = this
        fun setOngoing(ongoing: Boolean) = this
        fun addAction(action: Action) = apply { actions.add(action) }
        fun build() = Notification(actions.toList())
    }
}
