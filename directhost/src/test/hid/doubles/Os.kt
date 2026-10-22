package android.os

class Bundle
class Looper private constructor() {
    companion object { private val main = Looper(); fun getMainLooper() = main }
}
object SystemClock { var now = 1000L; fun uptimeMillis() = now }

/** One advancing uptime domain; scheduled callbacks require explicit test delivery. */
class Handler(val looper: Looper) {
    data class Pending(val task: Runnable, val deadline: Long, var canceled: Boolean = false)
    val pending = mutableListOf<Pending>()
    fun post(task: Runnable): Boolean { MainQueue.post(task); return true }
    fun postAtTime(task: Runnable, at: Long): Boolean { pending.add(Pending(task, at)); return true }
    fun postDelayed(task: Runnable, delay: Long): Boolean = postAtTime(task, SystemClock.now + delay)
    fun removeCallbacks(task: Runnable) { pending.filter { it.task === task }.forEach { it.canceled = true } }
    fun advance(to: Long) {
        check(to >= SystemClock.now)
        while (true) {
            val next = pending.filter { !it.canceled && it.deadline <= to }.minByOrNull { it.deadline } ?: break
            next.canceled = true
            SystemClock.now = maxOf(SystemClock.now, next.deadline)
            next.task.run()
        }
        SystemClock.now = to
    }
}

object MainQueue {
    private val queue = java.util.concurrent.ConcurrentLinkedQueue<Runnable>()
    fun post(task: Runnable) { queue.add(task) }
    fun drain() { while (true) (queue.poll() ?: break).run() }
}
