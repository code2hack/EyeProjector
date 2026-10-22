package android.os

object SystemClock {
    @Volatile var elapsedNs: Long? = null
    @JvmStatic fun elapsedRealtimeNanos(): Long = elapsedNs ?: System.nanoTime()
}
