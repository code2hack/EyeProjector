package android.content
import android.net.Uri
import java.io.File
open class Context {
    open val applicationContext: Context get() = this
    val packageName = "com.code2hack.eyeprojector.directhost"
    val filesDir: File get() = root
    fun getString(id: Int): String = com.code2hack.eyeprojector.directhost.R.strings.getValue(id)
    fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
        preferences.getOrPut(filesDir.absolutePath + "/" + name) { SharedPreferences() }
    companion object {
        const val MODE_PRIVATE = 0
        var root = java.nio.file.Files.createTempDirectory("widget-entry-").toFile()
        private val preferences = mutableMapOf<String, SharedPreferences>()
    }
}
/** In-memory Android preference boundary, keyed to each fixture's private directory; no disk/device proof. */
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
data class ComponentName(val context: Context, val type: Class<*>)
class Intent() {
    var action: String? = null
    var data: Uri? = null
    var type: Class<*>? = null
    var flags = 0
    private val extras = mutableMapOf<String, Any?>()
    constructor(context: Context, component: Class<*>) : this() { type = component }
    fun putExtra(key: String, value: String?): Intent { extras[key] = value; return this }
    fun putExtra(key: String, value: Int): Intent { extras[key] = value; return this }
    fun getStringExtra(key: String) = extras[key] as? String
    fun getIntExtra(key: String, default: Int) = extras[key] as? Int ?: default
    fun addFlags(value: Int): Intent { flags = flags or value; return this }
    companion object { const val FLAG_ACTIVITY_NEW_TASK = 1; const val FLAG_ACTIVITY_NO_HISTORY = 2; const val FLAG_ACTIVITY_CLEAR_TOP = 4; const val FLAG_ACTIVITY_SINGLE_TOP = 8 }
}
