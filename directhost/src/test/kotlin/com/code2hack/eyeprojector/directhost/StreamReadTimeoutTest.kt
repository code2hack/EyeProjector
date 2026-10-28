package com.code2hack.eyeprojector.directhost

import com.code2hack.eyeprojector.protocol.Frame
import com.code2hack.eyeprojector.protocol.Wire
import org.junit.Assert.*
import org.junit.Test
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Real TCP phase checks; these do not exercise TLS or Android/device behavior. */
class StreamReadTimeoutTest {
    private companion object { const val SETUP_TIMEOUT_MS = 100 }

    private class Loopback : AutoCloseable {
        val peer: Socket
        val remote: Socket
        init {
            val address = InetAddress.getLoopbackAddress()
            val listener = ServerSocket(0, 1, address).apply { soTimeout = 2000 }
            val connecting = Socket()
            try {
                connecting.connect(InetSocketAddress(address, listener.localPort), 2000)
                connecting.soTimeout = 2000
                peer = listener.accept().apply { soTimeout = SETUP_TIMEOUT_MS }
                remote = connecting
            } catch (error: Throwable) {
                connecting.close()
                throw error
            } finally { listener.close() }
        }
        override fun close() {
            try { peer.close() } finally { remote.close() }
        }
    }

    @Test(timeout = 5000) fun successfulWelcomeAllowsAnIdleGapThenTheExactPing() {
        Loopback().use { sockets ->
            writeStreamWelcome(sockets.peer, DataOutputStream(sockets.peer.outputStream))
            Wire.readWelcome(DataInputStream(sockets.remote.inputStream))
            assertEquals(0, sockets.peer.soTimeout)
            val entered = CountDownLatch(1)
            val reader = Executors.newSingleThreadExecutor()
            try {
                val frame = reader.submit<Frame> {
                    entered.countDown()
                    Wire.readFrame(DataInputStream(sockets.peer.inputStream))
                }
                assertTrue(entered.await(1, TimeUnit.SECONDS))
                // A setup SocketTimeoutException would complete the Future exceptionally instead.
                assertThrows(TimeoutException::class.java) {
                    frame.get(3L * SETUP_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                }
                val stamp = Long.MIN_VALUE + 73
                Wire.writeFrame(DataOutputStream(sockets.remote.outputStream), Frame(Wire.PING, stamp))
                val received = frame.get(2, TimeUnit.SECONDS)
                assertEquals(Wire.PING, received.channel)
                assertEquals(stamp, received.stamp)
                assertArrayEquals(byteArrayOf(), received.payload)
            } finally {
                sockets.peer.close()
                reader.shutdownNow()
                assertTrue(reader.awaitTermination(1, TimeUnit.SECONDS))
            }
        }
    }

    @Test(timeout = 5000) fun closingTheStreamSocketUnblocksItsWaitingReader() {
        Loopback().use { sockets ->
            writeStreamWelcome(sockets.peer, DataOutputStream(sockets.peer.outputStream))
            Wire.readWelcome(DataInputStream(sockets.remote.inputStream))
            val entered = CountDownLatch(1)
            val reader = Executors.newSingleThreadExecutor()
            try {
                val frame = reader.submit<Frame> {
                    entered.countDown()
                    Wire.readFrame(DataInputStream(sockets.peer.inputStream))
                }
                assertTrue(entered.await(1, TimeUnit.SECONDS))
                assertThrows(TimeoutException::class.java) {
                    frame.get(3L * SETUP_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                }
                sockets.peer.close()
                val closed = assertThrows(ExecutionException::class.java) { frame.get(2, TimeUnit.SECONDS) }
                assertTrue(closed.cause is SocketException)
            } finally {
                sockets.peer.close()
                reader.shutdownNow()
                assertTrue(reader.awaitTermination(1, TimeUnit.SECONDS))
            }
        }
    }

    @Test(timeout = 5000) fun noWelcomeRetainsTheFiniteRequestReadTimeout() {
        Loopback().use { sockets ->
            assertEquals(SETUP_TIMEOUT_MS, sockets.peer.soTimeout)
            assertThrows(SocketTimeoutException::class.java) {
                Wire.readRequest(DataInputStream(sockets.peer.inputStream))
            }
        }
    }

    private fun failedWelcomeRetainsTheFiniteTimeout(failOnFlush: Boolean) {
        Loopback().use { sockets ->
            val failure = IOException(if (failOnFlush) "modeled welcome flush" else "modeled partial welcome write")
            val delegate = sockets.peer.outputStream
            var written = 0
            val output = object : OutputStream() {
                override fun write(value: Int) {
                    if (!failOnFlush && written == 5) throw failure
                    delegate.write(value)
                    written++
                }
                override fun write(bytes: ByteArray, offset: Int, length: Int) {
                    for (index in offset until offset + length) write(bytes[index].toInt())
                }
                override fun flush() {
                    delegate.flush()
                    if (failOnFlush) throw failure
                }
            }
            val caught = assertThrows(IOException::class.java) {
                writeStreamWelcome(sockets.peer, DataOutputStream(output))
            }
            assertSame(failure, caught)
            assertEquals(SETUP_TIMEOUT_MS, sockets.peer.soTimeout)
            if (failOnFlush) Wire.readWelcome(DataInputStream(sockets.remote.inputStream))
            else {
                assertEquals(5, written)
                DataInputStream(sockets.remote.inputStream).readFully(ByteArray(5))
            }
            assertThrows(SocketTimeoutException::class.java) {
                Wire.readFrame(DataInputStream(sockets.peer.inputStream))
            }
        }
    }

    @Test(timeout = 5000) fun partialWelcomeWriteFailureRetainsTheFiniteTimeout() =
        failedWelcomeRetainsTheFiniteTimeout(false)

    @Test(timeout = 5000) fun welcomeFlushFailureRetainsTheFiniteTimeout() =
        failedWelcomeRetainsTheFiniteTimeout(true)
}
