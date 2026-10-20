package com.code2hack.eyeprojector.protocol

import android.app.Instrumentation
import android.os.Bundle
import android.os.SystemClock
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Standalone library test APK: never reads/writes either installed app's session. */
class PairingInstrumentation : Instrumentation() {
    private lateinit var arguments: Bundle
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); this.arguments = arguments ?: Bundle(); start() }

    override fun onStart() {
        val report = Bundle()
        try {
            when (arguments.getString("mode", "loopback")) {
                "loopback" -> loopback(report)
                "lan" -> lan(report)
                else -> error("Unknown test mode")
            }
            report.putString("result", "PASS")
            finish(-1, report)
        } catch (error: Exception) {
            report.putString("result", "FAIL")
            // Fixed classification only; no keys/passwords or exception text.
            report.putString("failureType", error.javaClass.simpleName)
            finish(0, report)
        }
    }

    private fun sandbox(name: String): PeerStore {
        val directory = File(context.filesDir, "pairing-instrumentation/$name")
        if (arguments.getString("reset", "true") == "true") directory.deleteRecursively()
        return PeerStore(directory).apply { ensureIdentity() }
    }

    private fun loopback(report: Bundle) {
        val phone = sandbox("phone")
        val glasses = sandbox("glasses")
        val listener = ServerSocket(0, 2, InetAddress.getLoopbackAddress()).apply { soTimeout = 10_000 }
        val executor = Executors.newSingleThreadExecutor()
        try {
            val future = executor.submit(Callable {
                listener.accept().use { PairingExchange.pairGlasses(it, PairingCode({ 0L }, { "0123" }), glasses, listener.localPort) }
            })
            Socket(InetAddress.getLoopbackAddress(), listener.localPort).use {
                PairingExchange.pairPhone(it, "0123", phone, listener.localPort, glasses.identityId())
            }
            future.get(40, TimeUnit.SECONDS)
            check(PeerStore(phone.directory).pairedPeer()!!.identityId == glasses.identityId())
            check(PeerStore(glasses.directory).pairedPeer()!!.identityId == phone.identityId())
            val tls = Tls.context(phone.directory).serverSocketFactory.createServerSocket(0, 2, InetAddress.getLoopbackAddress()) as javax.net.ssl.SSLServerSocket
            tls.needClientAuth = true; tls.soTimeout = 10_000
            tls.use {
                val server = executor.submit(Callable {
                    (tls.accept() as javax.net.ssl.SSLSocket).use { socket ->
                        socket.soTimeout = 15_000; socket.startHandshake(); Tls.requireTrustedPeer(socket, phone.directory)
                        socket.outputStream.write(42)
                    }
                })
                glasses.writeTransportEndpoint("127.0.0.1", tls.localPort, PeerStore.tlsName(phone.identityCertificate()))
                Tls.connect(glasses.directory).use { socket -> check(socket.inputStream.read() == 42) }
                server.get(25, TimeUnit.SECONDS)
            }
            glasses.forget()
            check(PeerStore(glasses.directory).pairedPeer() == null)
            report.putString("checks", "Android PKCS12; J-PAKE; persisted mutual identity; mTLS current IP/stable SAN; Forget")
        } finally { listener.close(); executor.shutdownNow() }
    }

    private fun lan(report: Bundle) {
        val role = PeerRole.valueOf(arguments.getString("role", "GLASSES")!!)
        val store = sandbox("lan-${role.name}")
        val listener = ServerSocket(0).apply { soTimeout = 90_000 }
        val codes = PairingCode({ SystemClock.elapsedRealtime() })
        val found = CountDownLatch(1)
        val discovered = AtomicReference<DiscoveredPeer>()
        val label = arguments.getString("display", "")!!
        val discovery = LanDiscovery(store.identityId(), role, listener.localPort, { peer ->
            if (peer.identityId.startsWith(label) && discovered.compareAndSet(null, peer)) found.countDown()
        })
        try {
            discovery.start()
            if (role == PeerRole.GLASSES) {
                val shown = codes.display()
                sendStatus(1, Bundle().apply { putString("code", shown.code); putString("display", store.identityId().take(12)) })
                listener.accept().use { PairingExchange.pairGlasses(it, codes, store, listener.localPort) }
            } else {
                check(found.await(60, TimeUnit.SECONDS)) { "No display discovered" }
                val candidate = discovered.get() ?: error("No candidate")
                val code = arguments.getString("code") ?: error("Supply displayed code")
                Socket().use {
                    it.connect(InetSocketAddress(candidate.endpoint.host, candidate.endpoint.pairingPort), 5000)
                    PairingExchange.pairPhone(it, code, store, listener.localPort, candidate.identityId)
                }
            }
            val peer = PeerStore(store.directory).pairedPeer() ?: error("Peer not persisted")
            report.putString("peerIdentity", peer.identityId)
            report.putString("peerHost", peer.endpoint.host)
            report.putString("checks", "physical LAN discovery; code authentication; identity persistence")
        } finally { listener.close(); discovery.close() }
    }
}
