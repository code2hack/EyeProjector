package android.app

import android.content.Context

open class Activity : Context() {
    var resumed = 0
    var stopped = 0
    var destroyed = 0
    var backs = 0
    open fun onResume() { resumed++ }
    open fun onStop() { stopped++ }
    open fun onDestroy() { destroyed++ }
    open fun onBackPressed() { backs++ }
}
