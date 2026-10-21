package android.app

import android.content.Context
import android.content.Intent
import android.os.Bundle

open class Activity : Context() {
    private var incoming = Intent()
    val startedActivities = mutableListOf<Intent>()
    var finishCalls = 0
        private set
    val isFinishing: Boolean get() = finishCalls != 0
    val intent get() = incoming
    fun setIntent(value: Intent) { incoming = value }
    open fun onCreate(state: Bundle?) {}
    open fun onNewIntent(intent: Intent) {}
    open fun onStart() {}
    open fun onResume() {}
    open fun onStop() {}
    open fun onDestroy() {}
    fun setContentView(view: Any) {}
    fun startActivity(intent: Intent) { startedActivities.add(intent) }
    fun finish() { finishCalls++ }
    fun getString(id: Int, vararg args: Any) = "resource:$id:" + args.joinToString()
}
