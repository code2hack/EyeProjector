package android.os
object Build { object VERSION { var SDK_INT = 32 } }
object SystemClock {
    var time = 1000L
    fun uptimeMillis() = time
}
class Looper { companion object { fun getMainLooper() = Looper() } }
class Handler(looper: Looper) {
    fun postAtTime(task: Runnable, at: Long) { tasks.add(Task(task, at)) }
    fun removeCallbacks(task: Runnable) { tasks.filter { it.run === task }.forEach { it.canceled = true } }
    data class Task(val run: Runnable, val at: Long, var canceled: Boolean = false)
    companion object {
        val tasks = mutableListOf<Task>()
        fun advance(ms: Long) {
            val end = SystemClock.time + ms
            while (true) {
                val next = tasks.filter { !it.canceled && it.at <= end }.minByOrNull { it.at } ?: break
                next.canceled = true; SystemClock.time = maxOf(SystemClock.time, next.at); next.run.run()
            }
            SystemClock.time = end
        }
    }
}
