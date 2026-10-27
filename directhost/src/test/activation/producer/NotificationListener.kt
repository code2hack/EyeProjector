package android.service.notification
import android.content.Context
import android.content.Intent
open class StatusBarNotification(val packageName:String,val key:String,val postTime:Long)
open class NotificationListenerService:Context(){
 val activeNotifications:Array<StatusBarNotification>? get()=emptyArray()
 open fun onListenerConnected(){}
 open fun onListenerDisconnected(){}
 open fun onNotificationPosted(sbn:StatusBarNotification){}
 open fun onNotificationRemoved(sbn:StatusBarNotification){}
 open fun onUnbind(intent:Intent?):Boolean=false
 open fun onDestroy(){}
}
