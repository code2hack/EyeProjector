package com.code2hack.eyeprojector.directhost.notifications

import android.content.Context
import java.io.Closeable

/** This isolated listener suite does not execute activation/selection/launch.
 * Their actual composition is compiled and exercised by notificationselection/run.py.
 */
internal class NotificationPolicy {
    class Receipt
    companion object { val process = NotificationPolicy() }
}
internal class FacadeNotificationPort(facade: com.code2hack.eyeprojector.directhost.PhoneSessionController)
internal class NotificationSelectionOwner(port: FacadeNotificationPort, policy: NotificationPolicy,
    read: (String) -> Pair<Boolean, Boolean>, post: (() -> Unit) -> Unit, changed: () -> Unit) {
    val failed = false
    fun receipt(packageName: String) = NotificationPolicy.Receipt()
    fun access(value: NotificationAccessApproval) = Unit
    fun accept(observation: NotificationIngressObservation) = Unit
}
internal object AppNotificationPreferences {
    class Store { fun isEnabled(packageName: String) = true }
    fun open(context: Context) = Store()
    fun observe(context: Context, changed: () -> Unit) = Closeable {}
}
