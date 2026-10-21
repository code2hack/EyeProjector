package android.os
class Bundle
class Looper { companion object {
    private val main = Looper()
    var onMain = true
    fun getMainLooper() = main
    fun myLooper(): Looper? = if (onMain) main else null
} }
class Handler(looper: Looper) {
    fun post(action: () -> Unit): Boolean { queue.add(action); return true }
    companion object {
        private val queue = ArrayDeque<() -> Unit>()
        fun drain() { var n = 0; while (queue.isNotEmpty()) { check(++n < 1000) { "Observer loop" }; queue.removeFirst()() } }
    }
}
