package android.util

/** A diagnostic sink only; no native routing or classifier behavior is modeled here. */
object Log {
    val messages = mutableListOf<Pair<String, String>>()
    var onWrite: ((String, String) -> Unit)? = null
    fun d(tag: String, message: String): Int {
        onWrite?.invoke(tag, message)
        messages.add(Pair(tag, message))
        return 0
    }
}
