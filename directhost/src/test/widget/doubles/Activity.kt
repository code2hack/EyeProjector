package android.app
import android.content.Context
import android.content.Intent
import android.os.Bundle
open class Activity : Context() {
    var intent = Intent()
    var finished = false
    var content: Any? = null
    init { instances.add(this) }
    open fun onCreate(savedInstanceState: Bundle?) {}
    open fun onDestroy() {}
    fun setContentView(view: Any) { content = view }
    fun startActivity(value: Intent) { launches.add(value) }
    open fun finish() { finished = true }
    companion object { val launches = mutableListOf<Intent>(); val instances = mutableListOf<Activity>() }
}
class PendingIntent private constructor(var intent: Intent, val flags: Int) {
    fun send(): Activity {
        val activity = intent.type!!.getDeclaredConstructor().newInstance() as Activity
        activity.intent = intent; activity.onCreate(null); return activity
    }
    companion object {
        const val FLAG_UPDATE_CURRENT = 1; const val FLAG_IMMUTABLE = 2
        private val tokens = mutableMapOf<String, PendingIntent>()
        fun getActivity(context: Context, code: Int, intent: Intent, flags: Int): PendingIntent {
            val key = "$code/${intent.type}/${intent.action}/${intent.data}"
            val token = tokens[key]
            if (token != null) { if (flags and FLAG_UPDATE_CURRENT != 0) token.intent = intent; return token }
            return PendingIntent(intent, flags).also { tokens[key] = it }
        }
    }
}
