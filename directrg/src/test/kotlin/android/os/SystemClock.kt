package android.os

object SystemClock {
    @Volatile var elapsedNs: Long? = null
    @JvmStatic fun uptimeMillis(): Long = System.nanoTime() / 1_000_000
    @JvmStatic fun elapsedRealtime(): Long = elapsedRealtimeNanos() / 1_000_000
    @JvmStatic fun elapsedRealtimeNanos(): Long = elapsedNs ?: System.nanoTime()
}
