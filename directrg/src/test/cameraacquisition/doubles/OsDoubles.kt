package android.os

import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Host FIFO scheduling on real owned threads. Quit drains accepted work; it creates no camera callback. */
class Looper internal constructor(val owner: HandlerThread) {
    private val queue = LinkedBlockingQueue<Runnable>()
    private val stop = Runnable { }
    private var accepting = true

    @Synchronized internal fun post(action: Runnable): Boolean {
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
        while (true) {
            val action = queue.take()
            if (action === stop) return
            action.run()
        }
    }

    /** Message-queue disposal on actual loop return, including the explicit abnormal-exit scenario. */
    @Synchronized internal fun ownerLoopReturned() { accepting = false }

    companion object {
        private val hostMainThread = Thread.currentThread()
        private val hostMain = Looper(HandlerThread("explicit-host-main-marker", register = false))
        @JvmStatic fun getMainLooper(): Looper = hostMain
        @JvmStatic fun myLooper(): Looper? = (Thread.currentThread() as? HandlerThread)?.looper
            ?: if (Thread.currentThread() === hostMainThread) hostMain else null
    }
}

open class HandlerThread(name: String, register: Boolean = true) : Thread(name) {
    val looper = Looper(this)
    private val startGate = if (register) HandlerThreadProbe.register(this) else null
    @Volatile var finished = false
        private set

    override fun run() {
        try {
            startGate?.await()
            looper.runLoop()
        } catch (error: Throwable) {
            HandlerThreadProbe.failed(this, error)
        } finally { looper.ownerLoopReturned(); finished = true }
    }

    fun quitSafely(): Boolean = looper.quitSafely()
}

class Handler(val looper: Looper) {
    fun post(action: Runnable): Boolean = looper.post(action)

    /** Test barrier on this original Handler, never a rerouted callback or synthetic camera event. */
    fun drain() {
        val reached = CountDownLatch(1)
        check(post { reached.countDown() }) { "Original Handler no longer accepts a barrier" }
        check(reached.await(2, TimeUnit.SECONDS)) { "Original Handler barrier was not reached" }
    }
}

object HandlerThreadProbe {
    private val lock = Any()
    private val created = mutableListOf<HandlerThread>()
    private val failures = mutableListOf<Pair<HandlerThread, Throwable>>()
    private val acknowledged = mutableListOf<Pair<HandlerThread, Throwable>>()
    private val gates = mutableListOf<CountDownLatch>()
    private var nextGate: CountDownLatch? = null
    val threads get() = synchronized(lock) { created.toList() }
    val uncaught get() = synchronized(lock) {
        failures.filterNot { failure -> acknowledged.any { it.first === failure.first && it.second === failure.second } }
    }

    fun pauseNextStart(): CountDownLatch = synchronized(lock) {
        check(nextGate == null)
        CountDownLatch(1).also { nextGate = it; gates.add(it) }
    }

    internal fun register(thread: HandlerThread): CountDownLatch? = synchronized(lock) {
        created.add(thread)
        nextGate.also { nextGate = null }
    }

    internal fun failed(thread: HandlerThread, error: Throwable) = synchronized(lock) {
        failures.add(thread to error)
    }

    fun acknowledgeExpectedLoopFailure(thread: HandlerThread, error: Throwable) = synchronized(lock) {
        check(failures.any { it.first === thread && it.second === error })
        acknowledged.add(thread to error)
    }

    fun reset() = synchronized(lock) {
        created.clear(); failures.clear(); acknowledged.clear(); gates.clear(); nextGate = null
    }

    /** Failure cleanup only. Passing scenarios separately prove the producer's own quit and join. */
    fun cleanup() {
        synchronized(lock) { gates.forEach { it.countDown() } }
        threads.forEach { it.quitSafely() }
        threads.forEach { it.join(2_000) }
        check(threads.none { it.isAlive }) { "Host camera owner thread survived harness cleanup" }
    }
}
