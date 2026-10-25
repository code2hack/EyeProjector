package android.content

import android.content.res.Resources
import android.hardware.input.InputManager

/** In-memory preferences and service lookup only; no Android storage or service binding. */
open class Context {
    val resources = Resources()
    val filesDir: java.io.File get() = Environment.filesDir
    val inputManager get() = Environment.inputManager
    val applicationContext: Context get() = this
    val packageName = "com.code2hack.eyeprojector.directhost"
    val startedActivities get() = Environment.startedActivities
    fun <T> getSystemService(type: Class<T>): T {
        val service = when (type) {
            InputManager::class.java -> inputManager
            android.view.accessibility.AccessibilityManager::class.java -> Environment.accessibility
            else -> if (type.name == "android.app.NotificationManager") type.getDeclaredConstructor().newInstance()
                else error("Unsupported host service ${type.name}")
        }
        return type.cast(service)
    }
    fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
        preferences.getOrPut(name) { SharedPreferences() }
    fun startActivity(intent: Intent) { startedActivities.add(intent) }
    companion object {
        const val MODE_PRIVATE = 0
        val preferences = linkedMapOf<String, SharedPreferences>()
    }
}

object Environment {
    var filesDir = java.nio.file.Files.createTempDirectory("phone-hid-source-").toFile()
    val inputManager = InputManager()
    val accessibility = android.view.accessibility.AccessibilityManager()
    val startedActivities = mutableListOf<Intent>()
}

data class ComponentName(val packageName: String, val className: String) {
    constructor(context: Context, type: Class<*>) : this(context.packageName, type.name)
    fun flattenToString() = "$packageName/$className"
}

class SharedPreferences {
    val values = linkedMapOf<String, Any>()
    fun interface OnSharedPreferenceChangeListener { fun onSharedPreferenceChanged(preferences: SharedPreferences, key: String?) }
    private val listeners = linkedSetOf<OnSharedPreferenceChangeListener>()
    fun registerOnSharedPreferenceChangeListener(listener: OnSharedPreferenceChangeListener) { listeners.add(listener) }
    fun unregisterOnSharedPreferenceChangeListener(listener: OnSharedPreferenceChangeListener) { listeners.remove(listener) }
    var writable = true
    var writes = 0
    fun getString(key: String, fallback: String?): String? = values[key] as String? ?: fallback
    fun getBoolean(key: String, fallback: Boolean): Boolean = values[key] as Boolean? ?: fallback
    fun contains(key: String) = values.containsKey(key)
    fun edit() = Editor()
    inner class Editor {
        private val pending = linkedMapOf<String, Any>()
        fun putString(key: String, value: String) = apply { pending[key] = value }
        fun putBoolean(key: String, value: Boolean) = apply { pending[key] = value }
        fun commit(): Boolean { writes++; if (writable) { values.putAll(pending); pending.keys.forEach { key -> listeners.toList().forEach { it.onSharedPreferenceChanged(this@SharedPreferences, key) } } }; return writable }
        fun apply() { commit() }
    }
}

class Intent(val action: String? = null) {
    var flags = 0
    val extras = linkedMapOf<String, Any>()
    fun putExtra(key: String, value: Any) = apply { extras[key] = value }
    fun addFlags(value: Int) = apply { flags = flags or value }
    companion object {
        const val FLAG_ACTIVITY_NEW_TASK = 0x10000000
        const val EXTRA_COMPONENT_NAME = "android.intent.extra.COMPONENT_NAME"
    }
}
