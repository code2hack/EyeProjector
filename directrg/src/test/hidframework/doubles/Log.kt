package android.util

/** A diagnostic sink only; no native routing or classifier behavior is modeled here. */
object Log {
    val messages = mutableListOf<Pair<String, String>>()
    fun d(tag: String, message: String): Int { messages.add(Pair(tag, message)); return 0 }
}
