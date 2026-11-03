package android.content

import android.os.IBinder

open class Context {
    val resources = android.content.res.Resources()
    val packageName = "com.code2hack.eyeprojector.directhost"
    val packageManager = android.content.pm.PackageManager()
    val preferences = mutableMapOf<String, SharedPreferences>()
    val starts = mutableListOf<Intent>()
    fun <T> getSystemService(type: Class<T>): T = type.cast(android.app.Notifications.manager)
    fun getSharedPreferences(name: String, mode: Int) = preferences.getOrPut(name) { SharedPreferences() }
    fun startForegroundService(intent: Intent) { starts.add(intent) }
    companion object { const val MODE_PRIVATE = 0 }
}
class SharedPreferences {
    val values = linkedMapOf<String, String>()
    var writable = true
    var throwing = false
    var writes = 0
    fun getString(key: String, fallback: String?): String? = values[key] ?: fallback
    fun edit() = Editor()
    inner class Editor {
        private val changes = linkedMapOf<String, String?>()
        fun putString(key: String, value: String) = apply { changes[key] = value }
        fun remove(key: String) = apply { changes[key] = null }
        fun commit(): Boolean {
            writes++
            if (throwing) error("Preference I/O failure")
            if (!writable) return false
            changes.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
            return true
        }
    }
}
class ComponentName {
    constructor(context: Context, type: Class<*>)
    constructor(context: Context, name: String)
}
class Intent(context: Context, type: Class<*>) {
    var action: String? = null
    fun setAction(value: String) = apply { action = value }
}
interface ServiceConnection {
    fun onServiceConnected(name: ComponentName, binder: IBinder)
    fun onServiceDisconnected(name: ComponentName)
}
