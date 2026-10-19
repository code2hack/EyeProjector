package com.code2hack.eyeprojector.directhost

import java.net.Socket
import java.net.SocketAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Delayed
import java.util.concurrent.FutureTask
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLServerSocket
import kotlin.concurrent.thread
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HostLifetimeTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun crossingFormerLifetimeKeepsHostAvailableUntilExplicitClose() {
        val directory = temporary.newFolder()
        directory.resolve("endpoint.properties").writeText("host=192.168.42.1\nport=44378\n")
        val timer = VirtualTimer()
        val listener = WaitingListener()
        val states = ConcurrentLinkedQueue<String>()
        val failure = AtomicReference<Throwable?>()
        val host = Bridge(directory, directory.resolve("unused.jar"), timer, listener, states::add)
        val worker = thread { try { host.run() } catch (error: Throwable) { failure.set(error) } }
        try {
            assertTrue(listener.accepted.await(2, TimeUnit.SECONDS))
            timer.advanceTo(TimeUnit.MINUTES.toNanos(15) - 1)
            assertFalse(listener.isClosed)
            timer.advanceTo(TimeUnit.MINUTES.toNanos(16))
            assertFalse("Elapsed time alone must not close the listening host", listener.isClosed)
            assertFalse("Session safeguards still need the shared scheduler", timer.isShutdown)
            assertTrue(worker.isAlive)
            assertEquals(listOf("HOST_READY"), states.toList())

            host.close()
            worker.join(2000)
            assertFalse(worker.isAlive)
            assertTrue(listener.isClosed)
            assertTrue(timer.isShutdown)
            assertEquals(listOf("HOST_READY", "HOST_STOPPED"), states.toList())
            assertNull(failure.get())
        } finally {
            host.close()
            worker.join(2000)
        }
    }

    private class VirtualTimer : ScheduledThreadPoolExecutor(1) {
        private var now = 0L
        private val tasks = mutableListOf<Task<*>>()
        private inner class Task<V>(call: Callable<V>, val due: Long) : FutureTask<V>(call), ScheduledFuture<V> {
            override fun getDelay(unit: TimeUnit) = unit.convert(due - now, TimeUnit.NANOSECONDS)
            override fun compareTo(other: Delayed) = getDelay(TimeUnit.NANOSECONDS).compareTo(other.getDelay(TimeUnit.NANOSECONDS))
        }
        override fun schedule(command: Runnable, delay: Long, unit: TimeUnit): ScheduledFuture<*> =
            schedule(Callable { command.run() }, delay, unit)
        override fun <V> schedule(call: Callable<V>, delay: Long, unit: TimeUnit): ScheduledFuture<V> =
            Task(call, now + unit.toNanos(delay)).also { tasks.add(it) }
        fun advanceTo(time: Long) {
            now = time
            tasks.filter { !it.isDone && it.due <= now }.sortedBy { it.due }.forEach { it.run() }
        }
    }

    /** No network or TLS shortcuts enter production: only the idle accept/close lifecycle is faked. */
    private class WaitingListener : SSLServerSocket() {
        val accepted = CountDownLatch(1)
        private val closed = CountDownLatch(1)
        override fun bind(endpoint: SocketAddress, backlog: Int) = Unit
        override fun getLocalPort() = 44378
        override fun setReuseAddress(on: Boolean) = Unit
        override fun setSoTimeout(timeout: Int) = Unit
        override fun accept(): Socket {
            accepted.countDown()
            if (!closed.await(1, TimeUnit.SECONDS)) throw SocketTimeoutException()
            throw SocketException("closed")
        }
        override fun close() { closed.countDown() }
        override fun isClosed() = closed.count == 0L
        override fun getSupportedCipherSuites() = emptyArray<String>()
        override fun getEnabledCipherSuites() = emptyArray<String>()
        override fun setEnabledCipherSuites(suites: Array<String>) = Unit
        override fun getSupportedProtocols() = arrayOf("TLSv1.3")
        override fun getEnabledProtocols() = supportedProtocols
        override fun setEnabledProtocols(protocols: Array<String>) = Unit
        override fun getNeedClientAuth() = true
        override fun setNeedClientAuth(need: Boolean) = Unit
        override fun getWantClientAuth() = false
        override fun setWantClientAuth(want: Boolean) = Unit
        override fun getUseClientMode() = false
        override fun setUseClientMode(mode: Boolean) = Unit
        override fun getEnableSessionCreation() = true
        override fun setEnableSessionCreation(flag: Boolean) = Unit
    }
}
