package android.app
import android.content.ComponentName
class NotificationManager {
    var granted = false
    var fail = false
    var lastComponent: ComponentName? = null
    fun isNotificationListenerAccessGranted(component: ComponentName): Boolean {
        lastComponent = component
        if (fail) error("synthetic approval unavailable")
        return granted
    }
}
