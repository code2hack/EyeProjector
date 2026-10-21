package android.content

import java.io.File

/** In-memory API boundary only. No Android Activity or preference persistence claim. */
open class Context(val filesDir: File = HarnessFiles.directory) {
    val applicationContext: Context get() = this
    val packageManager = android.content.pm.PackageManager()
    fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
        HarnessFiles.preferences.getOrPut(filesDir.absolutePath + "/" + name) { SharedPreferences() }
    companion object { const val MODE_PRIVATE = 0 }
}

object HarnessFiles {
    var directory = File(".")
    val preferences = mutableMapOf<String, SharedPreferences>()
}

class SharedPreferences {
    private val values = mutableMapOf<String, String>()
    fun getString(key: String, fallback: String?): String? = values[key] ?: fallback
    fun edit() = Editor()
    inner class Editor {
        private val pending = mutableMapOf<String, String>()
        fun putString(key: String, value: String) = apply { pending[key] = value }
        fun commit(): Boolean { values.putAll(pending); return true }
    }
}

class Intent {
    constructor()
    constructor(action: String, uri: android.net.Uri) { this.action = action; this.uri = uri }
    constructor(context: Context, component: Class<*>) { this.component = component.name }
    var action: String? = null
    var uri: android.net.Uri? = null
    var component: String? = null
    private val extras = mutableMapOf<String, String>()
    fun putExtra(key: String, value: String) = apply { extras[key] = value }
    fun getStringExtra(key: String): String? = extras[key]
    companion object { const val ACTION_VIEW = "android.intent.action.VIEW" }
}
