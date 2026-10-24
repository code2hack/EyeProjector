package android.os

import java.util.concurrent.LinkedBlockingQueue

class Looper internal constructor(internal val queue: LinkedBlockingQueue<Runnable>? = null) {
    companion object { @JvmStatic fun getMainLooper() = Looper() }
}
class Handler(private val looper: Looper) {
    fun post(action: Runnable): Boolean {
        val queue = looper.queue
        if (queue == null) action.run() else queue.put(action)
        return true
    }
}
class HandlerThread(name: String) : Thread(name) {
    val looper = Looper(LinkedBlockingQueue())
    @Volatile private var running = true
    override fun run() {
        while (running || looper.queue!!.isNotEmpty()) looper.queue!!.take().run()
    }
    fun quitSafely(): Boolean { running = false; looper.queue!!.put(Runnable {}); return true }
}
