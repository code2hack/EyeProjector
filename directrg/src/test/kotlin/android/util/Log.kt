package android.util

object Log {
    @JvmStatic fun i(tag: String, message: String) = 0
    @JvmStatic fun d(tag: String, message: String) = 0
    @JvmStatic fun e(tag: String, message: String, error: Throwable) = 0
    @JvmStatic fun w(tag: String, message: String) = 0
}
