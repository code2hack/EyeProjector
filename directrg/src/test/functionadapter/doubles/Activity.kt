package android.app
import android.content.Context
import android.content.Intent
import java.io.File
open class Activity : Context() {
    var focus = true
    var finished = false
    var launch: Intent? = null
    val filesDir = File("host-boundary")
    fun hasWindowFocus() = focus
    fun startActivity(intent: Intent) { launch = intent }
    fun finish() { finished = true }
    open fun onResume() {}
    open fun onPause() {}
    open fun onDestroy() {}
    open fun onWindowFocusChanged(hasFocus: Boolean) { focus = hasFocus }
    fun runOnUiThread(action: () -> Unit) = action()
    companion object { const val RESULT_CANCELED = 0 }
}
