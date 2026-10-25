package android.content

import android.app.NotificationManager
import com.code2hack.eyeprojector.directhost.TEST_RESOURCE_TEXT

open class Context {
    open val applicationContext: Context get() = this
    open val packageName = "com.code2hack.eyeprojector.directhost"
    val notifications = NotificationManager()
    val activities = mutableListOf<Intent>()
    val failingActions = mutableSetOf<String>()
    open fun <T> getSystemService(type: Class<T>): T = type.cast(notifications)
    open fun startActivity(intent: Intent) {
        activities.add(intent)
        if (intent.action in failingActions) error("synthetic settings unavailable")
    }
    fun getString(id: Int): String = TEST_RESOURCE_TEXT.getValue(id)
}
class ComponentName(private val context: Context, private val type: Class<*>) {
    fun flattenToString(): String = context.packageName + "/" + type.name
}
class Intent(val action: String) {
    val extras = mutableMapOf<String, String>()
    var flags = 0
    fun putExtra(key: String, value: String): Intent { extras[key] = value; return this }
    fun addFlags(value: Int): Intent { flags = flags or value; return this }
    companion object { const val FLAG_ACTIVITY_NEW_TASK = 0x10000000 }
}
