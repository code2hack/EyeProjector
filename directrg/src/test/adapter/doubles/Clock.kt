package android.os

/** Tests drive elapsed realtime and uptime together; no wall clock or device timer runs. */
object SystemClock {
    var nowNs = 1_000_000_000L
    fun elapsedRealtimeNanos() = nowNs
    fun uptimeMillis() = nowNs / 1_000_000
}

class Looper {
    companion object {
        private val main = Looper()
        fun myLooper() = main
        fun getMainLooper() = main
    }
}
