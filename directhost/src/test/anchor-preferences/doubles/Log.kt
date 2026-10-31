package android.util

object Log {
    @JvmStatic fun e(tag: String, message: String, failure: Throwable): Int {
        System.err.println("$tag: $message: $failure")
        return 0
    }
}
