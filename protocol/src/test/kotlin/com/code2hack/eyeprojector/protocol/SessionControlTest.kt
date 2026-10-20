package com.code2hack.eyeprojector.protocol

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Real local TCP and mTLS exchanges with synthetic app-private identities. */
class SessionControlTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun typedStateRoundTripSeparatesListenerAndRenderedReadiness() {
        for (phase in SessionPhase.entries) {
            val bytes = ByteArrayOutputStream()
            val state = SessionState("generation", phase, "fixture")
            SessionControl.writeState(DataOutputStream(bytes), state)
            assertEquals(state, SessionControl.readState(DataInputStream(bytes.toByteArray().inputStream())))
        }
        for (operation in SessionControl.Operation.entries) {
            val bytes = ByteArrayOutputStream()
            val request = SessionControl.Request(operation, "observed-epoch")
            SessionControl.writeRequest(DataOutputStream(bytes), request)
            assertEquals(request, SessionControl.readRequest(DataInputStream(bytes.toByteArray().inputStream())))
        }
        val bytes = ByteArrayOutputStream()
        val cancellation = SessionControl.Request.cancelStart("baseline", "proposal")
        SessionControl.writeRequest(DataOutputStream(bytes), cancellation)
        assertEquals(cancellation, SessionControl.readRequest(DataInputStream(bytes.toByteArray().inputStream())))
    }

    @Test fun pairedControlUsesBothRememberedIdentityPins() {
        val phone = PeerStore(temporary.newFolder("phone"))
        val glasses = PeerStore(temporary.newFolder("glasses"))
        val phoneIdentity = phone.ensureIdentity()
        val glassesIdentity = glasses.ensureIdentity()
        val executor = Executors.newSingleThreadExecutor()
        ServerSocket(0).use { server ->
            server.soTimeout = 5000
            val endpoint = PeerEndpoint("127.0.0.1", server.localPort)
            phone.remember(RememberedPeer(glassesIdentity, endpoint))
            glasses.remember(RememberedPeer(phoneIdentity, endpoint))
            val future = executor.submit(Callable {
                server.accept().use { socket ->
                    SessionControl.serve(phone.directory, socket) { request ->
                        assertEquals(SessionControl.Operation.START, request.operation)
                        assertEquals("observed", request.epoch)
                        SessionState("fresh", SessionPhase.STARTING)
                    }
                }
            })
            try {
                val reply = SessionControl.exchange(glasses.directory, glasses.pairedPeer()!!,
                    SessionControl.Request(SessionControl.Operation.START, "observed"))
                assertEquals(SessionState("fresh", SessionPhase.STARTING), reply)
                future.get(8, TimeUnit.SECONDS)
            } finally { executor.shutdownNow() }
        }
    }

    @Test fun unpairedIdentityNeverReachesSessionCommandHandler() {
        val phone = PeerStore(temporary.newFolder("phone"))
        val glasses = PeerStore(temporary.newFolder("glasses"))
        val rogue = PeerStore(temporary.newFolder("rogue"))
        val phoneIdentity = phone.ensureIdentity()
        val glassesIdentity = glasses.ensureIdentity()
        rogue.ensureIdentity()
        val admitted = AtomicInteger()
        val executor = Executors.newSingleThreadExecutor()
        ServerSocket(0).use { server ->
            server.soTimeout = 5000
            val endpoint = PeerEndpoint("127.0.0.1", server.localPort)
            phone.remember(RememberedPeer(glassesIdentity, endpoint))
            rogue.remember(RememberedPeer(phoneIdentity, endpoint))
            val future = executor.submit(Callable {
                runCatching { server.accept().use { socket ->
                    SessionControl.serve(phone.directory, socket) {
                        admitted.incrementAndGet(); SessionState("never", SessionPhase.STARTING)
                    }
                } }
            })
            try {
                assertThrows(Exception::class.java) {
                    SessionControl.exchange(rogue.directory, rogue.pairedPeer()!!, SessionControl.Request(SessionControl.Operation.START))
                }
                future.get(8, TimeUnit.SECONDS)
                assertEquals(0, admitted.get())
            } finally { executor.shutdownNow() }
        }
    }
}
