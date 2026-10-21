package android.content

open class Context {
    var receiver: BroadcastReceiver? = null
    var lastReceiver: BroadcastReceiver? = null
    var filter: IntentFilter? = null
    var registrations = 0
    var unregistrations = 0
    var registerFlags: Int? = null
    fun registerReceiver(value: BroadcastReceiver, filter: IntentFilter): Intent? = register(value, filter, null)
    fun registerReceiver(value: BroadcastReceiver, filter: IntentFilter, flags: Int): Intent? = register(value, filter, flags)
    private fun register(value: BroadcastReceiver, filter: IntentFilter, flags: Int?): Intent? {
        check(receiver == null); receiver = value; lastReceiver = value; this.filter = filter
        registrations++; registerFlags = flags; return null
    }
    fun unregisterReceiver(value: BroadcastReceiver) { check(receiver === value); receiver = null; unregistrations++ }
    companion object { const val RECEIVER_EXPORTED = 2 }
}
abstract class BroadcastReceiver {
    var isOrderedBroadcast = false
    var resultCode = 99
    var aborted = false
    fun abortBroadcast() { check(isOrderedBroadcast); aborted = true }
    abstract fun onReceive(context: Context, intent: Intent)
}
class Intent(val action: String) {
    var flags = 0
    val categories = mutableSetOf<String>()
    fun addCategory(value: String) = apply { categories.add(value) }
    fun addFlags(value: Int) = apply { flags = flags or value }
    companion object {
        const val FLAG_RECEIVER_NO_ABORT = 0x08000000
        const val FLAG_ACTIVITY_NEW_TASK = 0x10000000
        const val ACTION_MAIN = "android.intent.action.MAIN"
        const val CATEGORY_HOME = "android.intent.category.HOME"
    }
}
class IntentFilter {
    var priority = 0
    val actions = mutableSetOf<String>()
    fun addAction(value: String) { actions.add(value) }
}
