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

    @Test fun activationScopesRoundTripIndependentlyOfAttemptsAndMedia() {
        val incarnation = ReceiverIncarnation("receiver-incarnation", 7)
        val request = SessionControl.Request(SessionControl.Operation.STATUS, "backend", "admission",
            ActivationIntent("original-trigger"), CancelTrigger("canceled-trigger", "original-admission"),
            CancelActivation("whole-activation"), incarnation,
            PresentationReport("whole-activation", "backend", 3, incarnation, 9, ReceiverUsabilityMode.NONE, "DISPLAY_DETACHED"))
        val encoded = ByteArrayOutputStream().also { SessionControl.writeRequest(DataOutputStream(it), request) }
        assertEquals(request, SessionControl.readRequest(DataInputStream(encoded.toByteArray().inputStream())))
        val state = SessionState("backend", SessionPhase.STOPPING, receipt = AttemptReceipt("admission", "backend", AttemptDisposition.CANCEL_PENDING),
            activation = ActivationReply(ActivationDirective("whole-activation", false, 3, "backend"),
                TriggerReceipt("original-trigger", "whole-activation", TriggerDisposition.CANCELED), "whole-activation"))
        val reply = ByteArrayOutputStream().also { SessionControl.writeState(DataOutputStream(it), state) }
        assertEquals(state, SessionControl.readState(DataInputStream(reply.toByteArray().inputStream())))
    }

    @Test fun typedGoalsAndPresentationModesRoundTripWithoutCollapsingLocalAndProjected() {
        val i = ReceiverIncarnation("typed-receiver", 2)
        for (mode in ReceiverUsabilityMode.entries) {
            val report = PresentationReport("H", "A", 4, i, 7, mode)
            val request = SessionControl.Request(SessionControl.Operation.STATUS, incarnation = i, presentation = report)
            val bytes = ByteArrayOutputStream().also { SessionControl.writeRequest(DataOutputStream(it), request) }
            val decoded = SessionControl.readRequest(DataInputStream(bytes.toByteArray().inputStream()))
            assertEquals(request, decoded)
            assertEquals(mode != ReceiverUsabilityMode.NONE, decoded.presentation!!.presented)
        }
        for (goal in ActivationGoal.entries) {
            val state = SessionState("A", SessionPhase.STREAMING, activation = ActivationReply(ActivationDirective("H", true, 4, "A", goal)))
            val bytes = ByteArrayOutputStream().also { SessionControl.writeState(DataOutputStream(it), state) }
            assertEquals(state, SessionControl.readState(DataInputStream(bytes.toByteArray().inputStream())))
        }
    }

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

    @Test fun admissionReceiptsRoundTripWithoutConfusingAcceptanceWithRelease() {
        for (disposition in AttemptDisposition.entries) {
            for (owner in listOf(null, "actual-owner")) {
                val state = SessionState("actual", SessionPhase.UNKNOWN, "retry detail", AttemptReceipt("before-status", owner, disposition))
                val bytes = ByteArrayOutputStream()
                SessionControl.writeState(DataOutputStream(bytes), state)
                assertEquals(state, SessionControl.readState(DataInputStream(bytes.toByteArray().inputStream())))
            }
        }
        val bytes = ByteArrayOutputStream()
        val observation = SessionControl.Request.observeAttempt("before-status")
        SessionControl.writeRequest(DataOutputStream(bytes), observation)
        assertEquals(observation, SessionControl.readRequest(DataInputStream(bytes.toByteArray().inputStream())))
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
