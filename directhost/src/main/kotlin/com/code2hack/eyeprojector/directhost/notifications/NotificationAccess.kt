package com.code2hack.eyeprojector.directhost.notifications

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings

internal object NotificationAccess {
    fun component(context: Context) = ComponentName(context, PhoneNotificationListener::class.java)
    fun approval(context: Context): NotificationAccessApproval = try {
        if (context.getSystemService(NotificationManager::class.java).isNotificationListenerAccessGranted(component(context)))
            NotificationAccessApproval.GRANTED else NotificationAccessApproval.DENIED
    } catch (_: Exception) { NotificationAccessApproval.UNKNOWN }

    /** Opening ordinary Settings neither grants access nor proves callback connection. */
    fun openSettings(context: Context): Boolean {
        val detail = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
            .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, component(context).flattenToString())
        for (intent in listOf(detail, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))) {
            try { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return true }
            catch (_: Exception) { /* Try the supported broad page if detail cannot be opened. */ }
        }
        return false
    }
}
