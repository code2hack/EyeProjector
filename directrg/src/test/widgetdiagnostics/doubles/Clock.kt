package android.os

object SystemClock {
    fun uptimeMillis() = System.nanoTime() / 1_000_000L
    fun elapsedRealtime() = uptimeMillis()
}
