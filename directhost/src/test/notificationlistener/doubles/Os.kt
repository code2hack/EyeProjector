package android.os
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
class Looper private constructor() {
    companion object {
        private val main = Looper()
        private val mainThread = Thread.currentThread()
        private val tasks = LinkedBlockingQueue<() -> Unit>()
        fun getMainLooper() = main
        fun myLooper(): Looper? = if (Thread.currentThread() === mainThread) main else null
        fun post(work: () -> Unit) { tasks.add(work) }
        fun until(done: () -> Boolean) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (!done()) {
                val remaining = deadline - System.nanoTime()
                check(remaining > 0) { "synthetic main queue timed out" }
                tasks.poll(remaining, TimeUnit.NANOSECONDS)?.invoke()
            }
        }
        fun drain() { while (true) (tasks.poll() ?: return).invoke() }
    }
}
class Handler(looper: Looper) {
    fun post(work: () -> Unit): Boolean { Looper.post(work); return true }
}
