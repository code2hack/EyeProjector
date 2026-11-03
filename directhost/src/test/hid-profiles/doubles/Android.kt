package android.content

import android.os.IBinder

open class Context {
    val resources = android.content.res.Resources()
    val applicationContext: Context get() = this
    val packageName = "com.code2hack.eyeprojector.directhost"
    val packageManager = android.content.pm.PackageManager()
    val preferences = mutableMapOf<String, SharedPreferences>()
    val starts = mutableListOf<Intent>()
    fun <T> getSystemService(type: Class<T>): T = type.cast(when (type) {
        android.hardware.input.InputManager::class.java -> ContextServices.inputManager
        android.view.accessibility.AccessibilityManager::class.java -> ContextServices.accessibility
        else -> android.app.Notifications.manager
    })
    fun getSharedPreferences(name: String, mode: Int) = preferences.getOrPut(name) { SharedPreferences() }
    fun startActivity(intent: Intent) { starts.add(intent) }
    fun startForegroundService(intent: Intent) { starts.add(intent) }
    companion object { const val MODE_PRIVATE = 0 }
}
class SharedPreferences {
    val values = linkedMapOf<String, String>()
    var writable = true
    var throwing = false
    var writes = 0
    var readFailureKey: String? = null
    val commitOutcomes = java.util.ArrayDeque<Boolean>()
    fun getString(key: String, fallback: String?): String? {
        if (key == readFailureKey) error("Preference read failure")
        return values[key] ?: fallback
    }
    fun edit() = Editor()
    inner class Editor {
        private val changes = linkedMapOf<String, String?>()
        fun putString(key: String, value: String) = apply { changes[key] = value }
        fun remove(key: String) = apply { changes[key] = null }
        fun commit(): Boolean {
            writes++
            if (throwing) error("Preference I/O failure")
            if (!(commitOutcomes.pollFirst() ?: writable)) return false
            changes.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
            return true
        }
    }
}
object ContextServices {
    val inputManager = android.hardware.input.InputManager()
    val accessibility = android.view.accessibility.AccessibilityManager()
}
data class ComponentName(val packageName: String, val className: String) {
    constructor(context: Context, type: Class<*>) : this(context.packageName, type.name)
    constructor(context: Context, name: String) : this(context.packageName, name)
}
class Intent(var action: String? = null) {
    constructor(context: Context, type: Class<*>) : this(null)
    fun setAction(value: String) = apply { action = value }
    fun putExtra(name: String, value: Any) = this
    fun addFlags(value: Int) = this
    companion object {
        const val FLAG_ACTIVITY_NEW_TASK = 1
        const val EXTRA_COMPONENT_NAME = "component"
    }
}
interface ServiceConnection {
    fun onServiceConnected(name: ComponentName, binder: IBinder)
    fun onServiceDisconnected(name: ComponentName)
}
