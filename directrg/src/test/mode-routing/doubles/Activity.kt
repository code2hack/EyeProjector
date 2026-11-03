package android.app
import android.content.*
open class Activity: Context() {
    var intent=Intent();var isFinishing=false
    private var focus=false
    open fun onResume() {}
    open fun onPause() {}
    open fun onDestroy() {}
    open fun onWindowFocusChanged(hasFocus:Boolean) { focus=hasFocus }
    fun hasWindowFocus()=focus
    fun finish() { isFinishing=true }
    fun unbindService(connection:ServiceConnection) {}
    companion object { const val RESULT_CANCELED=0 }
}
