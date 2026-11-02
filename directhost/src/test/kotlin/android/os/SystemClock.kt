package android.os

/** JVM monotonic clock for Session lifecycle tests; Android suspend timing is not modeled. */
object SystemClock {
    @JvmStatic fun uptimeMillis() = System.nanoTime() / 1_000_000
    @JvmStatic fun elapsedRealtime() = System.nanoTime() / 1_000_000
    @JvmStatic fun elapsedRealtimeNanos() = System.nanoTime()
}
