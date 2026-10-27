package android.content

import android.Manifest
import android.app.NotificationManager
import android.content.pm.PackageManager
import com.code2hack.eyeprojector.directhost.notifications.PhoneNotificationRuntime

/** Explicit app/storage boundary; the real canonical stores and Android Context are not executed. */
class Context {
    val packageName = "com.code2hack.eyeprojector.directhost"
    internal val runtime = PhoneNotificationRuntime()
    val notifications = NotificationManager(this)
    val packageManager = PackageManager(this)
    var declaredPermissions = setOf(Manifest.permission.POST_NOTIFICATIONS)
    var grantedPermissions = setOf(Manifest.permission.POST_NOTIFICATIONS)
    var chosen = setOf(packageName)
    val enabled = mutableMapOf<String, Boolean>()
    var eligibilityReads = 0
    var runtimeReads = 0
    var eligibilityFailure: RuntimeException? = null
    var packageInfoFailure: RuntimeException? = null
    fun checkSelfPermission(permission: String) = if (permission in grantedPermissions) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
    fun <T> getSystemService(type: Class<T>): T? = type.cast(notifications)
}
