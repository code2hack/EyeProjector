package android.os

/** Unit-test platform clock with independent suspend-inclusive and awake time. */
object SystemClock {
    var elapsed = 0L
    var awake = 0L
    @JvmStatic fun elapsedRealtime(): Long = elapsed
    @JvmStatic fun uptimeMillis(): Long = awake
}
