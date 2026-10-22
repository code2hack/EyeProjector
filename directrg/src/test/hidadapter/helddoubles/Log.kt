package android.util

object Log {
    val messages = mutableListOf<Pair<String, String>>()
    var onWrite: ((String, String) -> Unit)? = null
    fun d(tag: String, message: String): Int {
        onWrite?.invoke(tag, message)
        messages.add(Pair(tag, message))
        return 0
    }
    fun w(tag: String, message: String) = 0
}
