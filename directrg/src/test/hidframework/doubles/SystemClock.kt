package android.os

/** The caller advances the same monotonic domain used by real classifier admission. */
object SystemClock {
    var now = 1000L
    fun uptimeMillis() = now
}
