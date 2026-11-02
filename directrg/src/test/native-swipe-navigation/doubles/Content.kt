package android.content

/** Android registration/delivery boundary; no firmware, user routing or brightness simulation. */
class ApplicationInfo(val uid: Int = 424242, val sourceDir: String = "unused-by-host-checks")
class Assets {
    companion object { var openForTest: ((String) -> java.io.InputStream)? = null }
    fun open(name: String): java.io.InputStream = openForTest?.invoke(name) ?: error("No asset provisioning in host checks")
}
class SharedPreferences {
    private val booleans=mutableMapOf<String,Boolean>()
    fun getBoolean(key:String,fallback:Boolean)=booleans[key]?:fallback
    fun edit()=Editor()
    inner class Editor {
        fun putBoolean(key:String,value:Boolean):Editor {booleans[key]=value;return this}
        fun apply()=Unit
    }
}
open class Context(private val directory: java.io.File = java.io.File("unused-context-directory")) {
    val applicationContext: Context get() = this
    val filesDir: java.io.File get() = directory
    val packageName = "example.host.routing.fixture"
    var applicationInfo = ApplicationInfo()
    private val preferences=SharedPreferences()
    fun getSharedPreferences(name:String,mode:Int)=preferences
    val assets = Assets()
    val receivers = mutableListOf<BroadcastReceiver>()
    val attempts = mutableListOf<BroadcastReceiver>()
    var registrations = 0
    var unregistrations = 0
    var flags: Int? = null
    var filter: IntentFilter? = null
    var beforeRegister: ((BroadcastReceiver) -> Unit)? = null
    var registerFailure: RuntimeException? = null
    var failAfterInstall = false
    var unregisterFailure: RuntimeException? = null
    var beforeUnregister: ((BroadcastReceiver) -> Unit)? = null
    fun registerReceiver(receiver: BroadcastReceiver, filter: IntentFilter): Intent? = register(receiver, filter, null)
    fun registerReceiver(receiver: BroadcastReceiver, filter: IntentFilter, flags: Int): Intent? = register(receiver, filter, flags)
    private fun register(receiver: BroadcastReceiver, filter: IntentFilter, flags: Int?): Intent? {
        registrations++; attempts.add(receiver); this.filter = filter; this.flags = flags
        beforeRegister?.invoke(receiver)
        if (!failAfterInstall) registerFailure?.let { throw it }
        check(receiver !in receivers); receivers.add(receiver)
        if (failAfterInstall) registerFailure?.let { throw it }
        return null
    }
    fun unregisterReceiver(receiver: BroadcastReceiver) {
        unregistrations++
        beforeUnregister?.invoke(receiver)
        unregisterFailure?.let { throw it }
        if (!receivers.remove(receiver)) throw IllegalArgumentException("Receiver not registered")
    }
    companion object { const val RECEIVER_EXPORTED = 2; const val MODE_PRIVATE = 0 }
}

abstract class BroadcastReceiver {
    var isOrderedBroadcast = false
    var resultWrites = 0
    var resultCode = 91
        set(value) { resultWrites++; field = value }
    var aborts = 0
    fun abortBroadcast() { aborts++ }
    abstract fun onReceive(context: Context, intent: Intent)
}

class Intent(val action: String?) {
    var flags = 0
    companion object { const val FLAG_RECEIVER_NO_ABORT = 0x08000000 }
}

class IntentFilter {
    var priority = 0
    val actions = mutableListOf<String>()
    fun addAction(action: String) { actions.add(action) }
}
