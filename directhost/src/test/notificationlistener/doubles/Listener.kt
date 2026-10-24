package android.service.notification
import android.content.Context
import android.content.Intent
open class StatusBarNotification(open val packageName: String, open val key: String, open val postTime: Long) {
    val notification: Any get() = error("project must not read notification payload")
    val tag: String get() = error("project must not read notification tag")
    override fun toString(): String = error("project must not log platform notification")
}
open class NotificationListenerService : Context() {
    override val applicationContext: Context get() = app
    var suppliedSnapshot: Array<StatusBarNotification>? = emptyArray()
    var snapshotFailure = false
    var queryCount = 0
    val activeNotifications: Array<StatusBarNotification>? get() {
        queryCount++
        if (snapshotFailure) error("synthetic query failure")
        return suppliedSnapshot
    }
    open fun onListenerConnected() {}
    open fun onListenerDisconnected() {}
    open fun onNotificationPosted(sbn: StatusBarNotification) {}
    open fun onNotificationRemoved(sbn: StatusBarNotification) {}
    open fun onUnbind(intent: Intent?): Boolean = false
    open fun onDestroy() {}
    companion object { val app = Context() }
}
