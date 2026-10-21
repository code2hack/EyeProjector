package android.os

object SystemClock {
    var now = 1_100L
    fun uptimeMillis() = now
}

class Looper { companion object { fun getMainLooper() = Looper() } }
class Handler(looper: Looper) {
    fun postAtTime(task: Runnable, deadline: Long) = true
    fun removeCallbacks(task: Runnable) = Unit
}
