package android.os

class Bundle
class Looper { companion object { fun getMainLooper() = Looper() } }
class Handler(looper: Looper) {
    private val delayed = mutableSetOf<Runnable>()
    fun post(action: () -> Unit): Boolean { action(); return true }
    fun post(action: Runnable): Boolean { action.run(); return true }
    fun postDelayed(action: Runnable, delay: Long): Boolean { delayed.add(action); return true }
    fun removeCallbacks(action: Runnable) { delayed.remove(action) }
}
