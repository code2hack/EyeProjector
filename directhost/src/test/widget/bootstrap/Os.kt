package android.os

class Looper { companion object { private val main = Looper(); fun getMainLooper() = main } }
class Handler(looper: Looper) {
    fun post(callback: () -> Unit): Boolean { queue.add(callback); return true }
    companion object {
        private val queue = java.util.concurrent.ConcurrentLinkedQueue<() -> Unit>()
        fun drain() { var turns = 0; while (true) { val next = queue.poll() ?: return; check(++turns < 1000); next() } }
    }
}
interface IBinder {
    val isBinderAlive: Boolean
    fun linkToDeath(recipient: () -> Unit, flags: Int)
}
