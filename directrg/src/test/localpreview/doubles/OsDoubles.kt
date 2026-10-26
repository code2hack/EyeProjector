package android.os

import java.util.concurrent.LinkedBlockingQueue

/** Host scheduling only: one real owner thread, FIFO posts, and a draining shutdown. */
class Looper internal constructor(val thread: Thread = Thread.currentThread()) {
    private val queue = LinkedBlockingQueue<Runnable>()
    private val stop = Runnable { }
    private var accepting = true
    /** One explicitly injected failure on this original Looper, never another owner's queue. */
    @Volatile var rejectNextPost = false
    @Volatile var rejectedPostCount = 0
        private set

    @Synchronized internal fun post(action: Runnable): Boolean {
        if (rejectNextPost) {
            rejectNextPost = false
            rejectedPostCount++
            HandlerThreadProbe.rejected(thread, Thread.currentThread(), action)
            return false
        }
        if (!accepting) return false
        queue.put(action)
        return true
    }

    @Synchronized internal fun quitSafely(): Boolean {
        if (!accepting) return false
        accepting = false
        queue.put(stop)
        return true
    }

    internal fun runLoop() {
        current.set(this)
        try {
            while (true) {
                val action = queue.take()
                if (action === stop) return
                action.run()
            }
        } finally { current.remove() }
    }

    companion object {
        private val main = Looper(Thread.currentThread())
        private val current = ThreadLocal<Looper>()
        @JvmStatic fun myLooper(): Looper? = current.get() ?: main.takeIf { Thread.currentThread() === it.thread }
        @JvmStatic fun getMainLooper(): Looper = main
    }
}

class HandlerThread(name: String) : Thread(name) {
    val looper = Looper(this)
    @Volatile var finished = false
        private set
    @Volatile var quitSafelyCount = 0
        private set

    init {
        isDaemon = true
        HandlerThreadProbe.add(this)
    }

    override fun run() {
        try {
            looper.runLoop()
        } catch (error: Throwable) {
            HandlerThreadProbe.failed(this, error)
        } finally {
            finished = true
        }
    }

    fun quitSafely(): Boolean { quitSafelyCount++; return looper.quitSafely() }
}

class Handler(val looper: Looper) {
    fun post(action: Runnable): Boolean = looper.post(action)
}

data class RejectedPost(val owner: Thread, val caller: Thread, val action: Runnable)

object HandlerThreadProbe {
    private val lock = Any()
    private val created = mutableListOf<HandlerThread>()
    private val errors = mutableListOf<Pair<HandlerThread, Throwable>>()
    private val rejectedHistory = mutableListOf<RejectedPost>()
    val threads: List<HandlerThread> get() = synchronized(lock) { created.toList() }
    val uncaught: List<Pair<HandlerThread, Throwable>> get() = synchronized(lock) { errors.toList() }
    val rejectedPosts: List<RejectedPost> get() = synchronized(lock) { rejectedHistory.toList() }
    internal fun add(thread: HandlerThread) = synchronized(lock) { created.add(thread); Unit }
    internal fun failed(thread: HandlerThread, error: Throwable) = synchronized(lock) {
        errors.add(thread to error)
        Unit
    }
    internal fun rejected(owner: Thread, caller: Thread, action: Runnable) = synchronized(lock) {
        rejectedHistory.add(RejectedPost(owner, caller, action)); Unit
    }
    fun reset() = synchronized(lock) { created.clear(); errors.clear(); rejectedHistory.clear() }
}
