package android.os

class Looper private constructor() {
    companion object {
        private val main = Looper()
        private val other = Looper()
        @JvmStatic fun getMainLooper(): Looper = main
        @JvmStatic fun myLooper(): Looper? = when {
            LooperProbe.noLooper -> null
            LooperProbe.onMain -> main
            else -> other
        }
    }
}

/** Test-selected Looper identity; no Android message queue is represented. */
object LooperProbe {
    var onMain = true
    var noLooper = false
    fun reset() { onMain = true; noLooper = false }
}

object Build {
    object VERSION { var SDK_INT = 35 }
    object VERSION_CODES { const val M = 23 }
}
