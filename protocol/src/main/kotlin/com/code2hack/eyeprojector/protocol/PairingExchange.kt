package com.code2hack.eyeprojector.protocol

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.math.BigInteger
import java.net.Socket
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.bouncycastle.crypto.agreement.jpake.JPAKEParticipant
import org.bouncycastle.crypto.agreement.jpake.JPAKERound1Payload
import org.bouncycastle.crypto.agreement.jpake.JPAKERound2Payload
import org.bouncycastle.crypto.agreement.jpake.JPAKERound3Payload

enum class PeerRole { PHONE, GLASSES }

/** Pairing is separate from the projection Wire protocol. No media is admitted here. */
object PairingExchange {
    private const val MAGIC = 0x45505031 // EPP1
    private const val VERSION = 1
    private const val MAX_RECORD = 16_384

    fun pairPhone(socket: Socket, code: String, store: PeerStore, localPairingPort: Int, expectedIdentityId: String? = null): RememberedPeer {
        val revision = store.pairingRevision()
        val channel = authenticate(socket, code, store, PeerRole.PHONE)
        channel.sendIdentity(store, localPairingPort)
        val peer = channel.receiveIdentity(socket)
        require(expectedIdentityId == null || peer.identityId == expectedIdentityId) { "Unexpected display identity" }
        channel.send(byteArrayOf(1))
        require(channel.receive().contentEquals(byteArrayOf(2))) { "Pairing was not confirmed" }
        store.completePairing(revision, peer, socket.localAddress.hostAddress!!)
        return peer
    }

    fun pairGlasses(socket: Socket, codes: PairingCode, store: PeerStore, localPairingPort: Int): RememberedPeer {
        val revision = store.pairingRevision()
        val displayed = codes.display()
        val channel = authenticate(socket, displayed.code, store, PeerRole.GLASSES)
        val peer = channel.receiveIdentity(socket)
        channel.sendIdentity(store, localPairingPort)
        require(channel.receive().contentEquals(byteArrayOf(1)))
        require(codes.isCurrent(displayed)) { "Pairing code expired" }
        // An interrupted acknowledgment may be retried by that same authenticated peer.
        store.completePairing(revision, peer, peer.endpoint.host, peer.tlsName)
        channel.send(byteArrayOf(2))
        return peer
    }

    private fun authenticate(socket: Socket, code: String, store: PeerStore, role: PeerRole): Channel {
        require(code.matches(Regex("[0-9]{4}"))) { "Enter four digits" }
        socket.soTimeout = 15_000
        socket.tcpNoDelay = true
        val input = DataInputStream(socket.getInputStream())
        val output = DataOutputStream(socket.getOutputStream())
        val id = "${role.name}:${store.identityId()}:${UUID.randomUUID()}"
        val password = code.toCharArray()
        val participant = try { JPAKEParticipant(id, password) } finally { password.fill('\u0000') }
        output.writeInt(MAGIC); output.writeInt(VERSION)
        require(input.readInt() == MAGIC && input.readInt() == VERSION) { "Unsupported pairing protocol" }
        val r1 = participant.createRound1PayloadToSend()
        output.writeUTF(r1.participantId)
        output.big(r1.gx1); output.big(r1.gx2)
        r1.knowledgeProofForX1.forEach { output.big(it) }; r1.knowledgeProofForX2.forEach { output.big(it) }
        output.flush()
        val otherId = input.readId()
        val otherRole = if (role == PeerRole.PHONE) PeerRole.GLASSES else PeerRole.PHONE
        require(otherId.startsWith("${otherRole.name}:")) { "Unexpected peer role" }
        participant.validateRound1PayloadReceived(JPAKERound1Payload(otherId, input.big(), input.big(),
            arrayOf(input.big(), input.big()), arrayOf(input.big(), input.big())))
        val r2 = participant.createRound2PayloadToSend()
        output.writeUTF(r2.participantId); output.big(r2.a); r2.knowledgeProofForX2s.forEach { output.big(it) }; output.flush()
        participant.validateRound2PayloadReceived(JPAKERound2Payload(input.readId(), input.big(), arrayOf(input.big(), input.big())))
        val material = participant.calculateKeyingMaterial()
        val r3 = participant.createRound3PayloadToSend(material)
        output.writeUTF(r3.participantId); output.big(r3.macTag); output.flush()
        participant.validateRound3PayloadReceived(JPAKERound3Payload(input.readId(), input.big()), material)
        val phoneId = if (role == PeerRole.PHONE) id else otherId
        val glassesId = if (role == PeerRole.GLASSES) id else otherId
        val binding = "EyeProjector pairing v1\n$phoneId\n$glassesId".toByteArray(Charsets.UTF_8)
        // HKDF-SHA256 extract/expand; direction labels produce separate AEAD keys.
        val extracted = hmac(ByteArray(32), material.toByteArray())
        fun key(direction: PeerRole) = hmac(extracted, binding + direction.name.toByteArray(Charsets.UTF_8) + byteArrayOf(1))
        return Channel(input, output, key(role), key(otherRole), binding, otherId, role)
    }

    private class Channel(
        private val input: DataInputStream, private val output: DataOutputStream,
        private val sendKey: ByteArray, private val receiveKey: ByteArray,
        private val binding: ByteArray, private val otherId: String, private val role: PeerRole,
    ) {
        fun send(plain: ByteArray) {
            val nonce = ByteArray(12).also(SecureRandom()::nextBytes)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(sendKey, "AES"), GCMParameterSpec(128, nonce))
            cipher.updateAAD(binding)
            output.blob(nonce + cipher.doFinal(plain)); output.flush()
        }

        fun receive(): ByteArray {
            val sealed = input.blob(MAX_RECORD)
            require(sealed.size >= 28)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(receiveKey, "AES"), GCMParameterSpec(128, sealed.copyOfRange(0, 12)))
            cipher.updateAAD(binding)
            return cipher.doFinal(sealed, 12, sealed.size - 12)
        }

        fun sendIdentity(store: PeerStore, pairingPort: Int) {
            val certificate = store.identityCertificate()
            val body = bytes { out ->
                out.writeUTF(role.name); out.blob(certificate.encoded)
                out.writeInt(pairingPort); out.writeInt(PeerEndpoint.DEFAULT_PROJECTION_PORT)
            }
            val password = java.io.File(store.directory, "password").readText().trimEnd().toCharArray()
            val signature = try {
                val keys = KeyStore.getInstance("PKCS12")
                java.io.File(store.directory, "identity.p12").inputStream().use { keys.load(it, password) }
                val key = keys.getKey(keys.aliases().nextElement(), password) as PrivateKey
                Signature.getInstance(signatureAlgorithm(key)).run { initSign(key); update(binding); update(body); sign() }
            } finally { password.fill('\u0000') }
            send(bytes { it.blob(body); it.blob(signature) })
        }

        fun receiveIdentity(socket: Socket): RememberedPeer {
            val record = DataInputStream(ByteArrayInputStream(receive()))
            val body = record.blob(MAX_RECORD)
            val signature = record.blob(512)
            require(record.available() == 0)
            val data = DataInputStream(ByteArrayInputStream(body))
            val expected = if (role == PeerRole.PHONE) PeerRole.GLASSES else PeerRole.PHONE
            require(data.readId() == expected.name)
            val certificate = PeerStore.decodeCertificate(data.blob(8192))
            certificate.checkValidity()
            certificate.verify(certificate.publicKey)
            require(otherId.startsWith("${expected.name}:${PeerStore.fingerprint(certificate)}:")) { "Peer identity changed during pairing" }
            PeerStore.tlsName(certificate)
            val verifier = Signature.getInstance(signatureAlgorithm(certificate.publicKey)).apply { initVerify(certificate); update(binding); update(body) }
            require(verifier.verify(signature)) { "Peer does not possess its identity" }
            val endpoint = PeerEndpoint(socket.inetAddress.hostAddress!!, data.readInt(), data.readInt())
            require(data.available() == 0)
            return RememberedPeer(certificate, endpoint)
        }
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(key, "HmacSHA256")); doFinal(data)
    }
    private fun signatureAlgorithm(key: java.security.Key): String = when (key.algorithm) {
        "EC" -> "SHA256withECDSA"
        "RSA" -> "SHA256withRSA"
        else -> error("Unsupported peer identity key")
    }
    private fun bytes(write: (DataOutputStream) -> Unit): ByteArray = ByteArrayOutputStream().also {
        DataOutputStream(it).use(write)
    }.toByteArray()
    private fun DataOutputStream.blob(value: ByteArray) { require(value.size <= MAX_RECORD); writeInt(value.size); write(value) }
    private fun DataInputStream.blob(max: Int): ByteArray = ByteArray(readInt().also { require(it in 1..max) }).also(::readFully)
    private fun DataOutputStream.big(value: BigInteger) = blob(value.toByteArray())
    private fun DataInputStream.big(): BigInteger = BigInteger(blob(512))
    private fun DataInputStream.readId(): String {
        val length = readUnsignedShort().also { require(it in 1..192) }
        val data = ByteArray(length).also(::readFully)
        require(data.all { it.toInt() in 32..126 })
        return String(data, Charsets.US_ASCII)
    }
}
