package android.util

/** Logging submission only; no Android logging or device observation. */
object Log {
    @JvmStatic fun i(tag: String, text: String) = 0
    @JvmStatic fun w(tag: String, text: String) = 0
    @JvmStatic fun w(tag: String, text: String, error: Throwable) = 0
}
