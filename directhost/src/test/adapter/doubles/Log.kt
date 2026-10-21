package android.util

/** Android logging boundary only; no production transport, query or diagnostic endpoint. */
object Log {
    val entries = java.util.concurrent.CopyOnWriteArrayList<Pair<String, String>>()
    @Volatile var failWrites = false
    @JvmStatic fun i(tag: String, message: String): Int {
        if (failWrites) throw IllegalStateException("synthetic log sink failure")
        entries.add(tag to message)
        return 0
    }
}
