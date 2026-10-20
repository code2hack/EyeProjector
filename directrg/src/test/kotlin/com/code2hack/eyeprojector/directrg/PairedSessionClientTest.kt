package com.code2hack.eyeprojector.directrg

import com.code2hack.eyeprojector.protocol.SessionControl
import com.code2hack.eyeprojector.protocol.SessionPhase
import com.code2hack.eyeprojector.protocol.SessionState
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.net.Socket
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real client executor/cancellation with modeled network admission; TLS is tested in protocol. */
class PairedSessionClientTest {
    @Test fun disconnectCancelsBothStaleAttachedEpochAndAnAcceptedStartWithoutAReply() {
        exercise(close = false)
    }

    @Test fun pauseClosesTheInflightSocketAndCancelsBothRelevantEpochs() {
        exercise(close = true)
    }

    private fun exercise(close: Boolean) {
        val accepted = CountDownLatch(1); val stopped = CountDownLatch(2)
        val targets = Collections.synchronizedList(mutableListOf<String>())
        val reported = Collections.synchronizedList(mutableListOf<SessionState>())
        var proposed = ""
        val client = PairedSessionClient(File("unused-synthetic-directory"), reported::add, {}, { request, opened ->
            when (request.operation) {
                SessionControl.Operation.STATUS -> SessionState("observed-inactive", SessionPhase.INACTIVE)
                SessionControl.Operation.START -> {
                    proposed = request.startEpoch
                    Socket().use { socket ->
                        opened(socket); accepted.countDown()
                        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
                        while (!socket.isClosed && System.nanoTime() < deadline) Thread.sleep(1)
                        check(socket.isClosed)
                        throw IOException("Canceled reply after simulated Start admission")
                    }
                }
                SessionControl.Operation.STOP -> {
                    targets.add(request.epoch); stopped.countDown()
                    SessionState("retired", SessionPhase.INACTIVE)
                }
                else -> error("Unexpected command")
            }
        })
        try {
            client.connect()
            assertTrue(accepted.await(2, TimeUnit.SECONDS))
            if (close) client.close("stale-attached") else {
                val suppressed = client.disconnect("stale-attached")
                assertEquals(setOf("stale-attached", proposed), suppressed.toSet())
            }
            assertTrue(stopped.await(3, TimeUnit.SECONDS))
            assertEquals(setOf("stale-attached", proposed), targets.toSet())
            assertTrue(reported.none { it.phase.active })
        } finally { client.close() }
    }
}
