package com.code2hack.eyeprojector.directrg

import android.view.Surface
import com.code2hack.eyeprojector.directrg.localcamera.*
import com.code2hack.eyeprojector.directrg.localcamera.forwarding.*
import com.code2hack.eyeprojector.protocol.*
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.util.ArrayDeque
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.HandshakeCompletedListener
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocket

/** Explicit queued owner-delivery boundary; finite waits belong only to these new host controls. */
internal class CameraOwnerLane {
    private val work = LinkedBlockingQueue<() -> Unit>()
    fun deliver(action: () -> Unit) { work.add(action) }
    /** Capture the next actual delivery without executing it; preserves its original queue order. */
    fun take(label: String): () -> Unit = checkNotNull(work.poll(5, TimeUnit.SECONDS)) {
        "Fixture owner delivery did not arrive: $label"
    }
    /** Observe an actual worker receipt while deliberately retaining every queued Owner action. */
    fun awaitWithoutDelivery(label: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition()) {
            check(System.nanoTime() < deadline) { "Fixture external receipt did not arrive: $label" }
            TimeUnit.MILLISECONDS.sleep(1)
        }
    }
    fun pump() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (true) {
            val action = work.poll() ?: return
            check(System.nanoTime() < deadline) { "Fixture owner queue did not settle" }
            action()
        }
    }
    fun await(label: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition()) {
            val remaining = deadline - System.nanoTime()
            check(remaining > 0) { "Fixture owner wait did not settle: $label" }
            work.poll(minOf(remaining, TimeUnit.MILLISECONDS.toNanos(10)), TimeUnit.NANOSECONDS)?.invoke()
        }
        pump()
    }
}

internal class CameraPermissionHostProbe(private val owner: ReceiverCameraOwner) {
    val identity = Any()
    val requests = CopyOnWriteArrayList<ReceiverCameraPermissionRequest>()
    @Volatile var onRequest: ((ReceiverCameraPermissionRequest) -> Unit)? = null
    fun attach() = owner.attachPermissionHost(identity) { original ->
        requests.add(original)
        onRequest?.invoke(original)
    }
    fun detach() = owner.detachPermissionHost(identity)
}

internal class CameraForegroundProbe {
    val calls = CopyOnWriteArrayList<Thread>()
    @Volatile var failure: Throwable? = null
    @Volatile var onPromote: (() -> Unit)? = null
    fun promote() {
        calls.add(Thread.currentThread())
        onPromote?.invoke()
        failure?.let { throw it }
    }
}

/**
 * Renderer/output boundary only. These opaque resources are fixture objects: release returns
 * below are not EGL/GL/SurfaceTexture/native or optical observations. No fixture Phone mask exists.
 */
internal class CameraOwnerOutput : LocalPreviewOutput {
    val inputs = CopyOnWriteArrayList<CameraOwnerInput>()
    @Volatile var onOpen: ((LocalCameraConfiguration) -> Unit)? = null
    @Volatile var openFailure: Throwable? = null
    @Volatile var onInput: ((CameraOwnerInput) -> Unit)? = null
    override fun currentPhoneTextureLatch(): PhoneTextureLatchKey? = null
    override fun bindFixturePhoneImage(latch: PhoneTextureLatchKey, image: PhoneImageKey,
        layout: ViewfinderLayout?): Boolean = false
    override fun openLocalCamera(configuration: LocalCameraConfiguration,
        observer: LocalCameraObserver?): CameraOwnerInput {
        onOpen?.invoke(configuration)
        openFailure?.let { throw it }
        return CameraOwnerInput(configuration, observer, "owner-local-${inputs.size}").also {
            inputs.add(it)
            onInput?.invoke(it)
        }
    }
}

internal class CameraOwnerInput(
    override val configuration: LocalCameraConfiguration,
    private val observer: LocalCameraObserver? = null,
    label: String = "owner-local",
) : ProducerRetirementInput {
    private val gate = Any()
    private val endpoint = Surface(label)
    private var binding: Binding? = null
    private var stop: (() -> Unit)? = null
    private var safe = false
    private var stopClaimed = false
    private var finalizationClaimed = false
    private var closing = false
    private var result: LocalInputReleaseResult? = null
    private val failures = ArrayList<LocalReleaseFailure>()
    private val subscribers = ArrayList<Pair<Executor, (LocalInputReleaseResult) -> Unit>>()
    private val releases = LinkedHashMap<String, Int>()
    private val releaseReturns = LinkedHashMap<String, Int>()
    @Volatile var onBind: ((LocalProducerBinding) -> Unit)? = null
    @Volatile var onStop: (() -> Unit)? = null
    @Volatile var onRelease: ((String) -> Unit)? = null
    @Volatile override var lastFailure: LocalCameraFailure? = null
        private set
    @Volatile var borrowCount = 0
        private set
    @Volatile var stopCalls = 0
        private set
    val token: LocalProducerBinding? get() = synchronized(gate) { binding }
    val releaseCalls: Map<String, Int> get() = synchronized(gate) { Collections.unmodifiableMap(LinkedHashMap(releases)) }
    val releaseReturnedCalls: Map<String, Int> get() = synchronized(gate) { Collections.unmodifiableMap(LinkedHashMap(releaseReturns)) }
    override val surface: Surface get() = synchronized(gate) { borrowCount++; endpoint }
    override val retired: Boolean get() = synchronized(gate) { closing }
    override val producerFence: LocalProducerFence get() = synchronized(gate) { fence() }
    override val releaseResult: LocalInputReleaseResult? get() = synchronized(gate) { result }
    override val retirementFailures: List<LocalReleaseFailure> get() = synchronized(gate) { failures.toList() }

    private inner class Binding : LocalProducerBinding {
        override fun outputsSafe() {
            synchronized(gate) { if (binding === this) safe = true }
            finalizeIfSafe()
        }
    }

    override fun bindProducer(requestStop: () -> Unit): LocalProducerBinding? {
        val original = synchronized(gate) {
            if (closing || binding != null) null else Binding().also { binding = it; stop = requestStop }
        } ?: return null
        // Token and original stop are published before this callback; reentry cannot bind a successor.
        onBind?.invoke(original)
        return original
    }

    override fun close() = retire()
    override fun closeAsync(completionExecutor: Executor, onRelease: (LocalInputReleaseResult) -> Unit) {
        synchronized(gate) { subscribers.add(completionExecutor to onRelease) }
        retire()
        deliver()
    }

    private fun retire() {
        val action = synchronized(gate) {
            closing = true
            if (binding != null && !stopClaimed) {
                stopClaimed = true; stopCalls++; stop
            } else null
        }
        if (action != null) {
            try { onStop?.invoke() } catch (error: Throwable) { failed("stop-observer", error) }
            try { action() } catch (error: Throwable) { failed("producer-stop", error) }
        }
        finalizeIfSafe()
    }

    private fun fence(): LocalProducerFence = when {
        binding == null -> LocalProducerFence.NOT_BOUND_LEGACY
        safe -> LocalProducerFence.OUTPUTS_SAFE_RECEIVED
        else -> LocalProducerFence.PENDING
    }

    private fun finalizeIfSafe() {
        val claimed = synchronized(gate) {
            if (!closing || binding != null && !safe || finalizationClaimed) false
            else { finalizationClaimed = true; true }
        }
        if (!claimed) return
        val steps = LinkedHashMap<String, LocalReleaseStep>()
        for (name in listOf("surfaceRelease", "surfaceTextureRelease", "programDelete", "textureDelete")) {
            synchronized(gate) { releases[name] = (releases[name] ?: 0) + 1 }
            try {
                onRelease?.invoke(name)
                if (name == "surfaceRelease") endpoint.release()
                // Other names stand for explicit opaque output-boundary operations returning here.
                synchronized(gate) { releaseReturns[name] = (releaseReturns[name] ?: 0) + 1 }
                steps[name] = LocalReleaseStep(LocalReleaseDisposition.RETURNED)
            } catch (error: Throwable) {
                failed(name, error)
                steps[name] = LocalReleaseStep(LocalReleaseDisposition.FAILED, error)
            }
        }
        synchronized(gate) {
            result = LocalInputReleaseResult(this, fence(), LocalGlContextDisposition.NOT_NEEDED,
                Collections.unmodifiableMap(steps), Collections.unmodifiableList(ArrayList(failures)))
        }
        deliver()
    }

    private fun failed(operation: String, error: Throwable) {
        synchronized(gate) { failures.add(LocalReleaseFailure(operation, error)) }
    }

    private fun deliver() {
        val selected = synchronized(gate) {
            val released = result ?: return
            released to subscribers.toList().also { subscribers.clear() }
        }
        selected.second.forEach { (executor, callback) ->
            try { executor.execute {
                try { callback(selected.first) } catch (error: Throwable) { failed("completion-callback", error) }
            } } catch (error: Throwable) { failed("completion-dispatch", error) }
        }
    }

    fun fail(operation: String, error: Exception) {
        val failure = LocalCameraFailure(operation, error, this)
        lastFailure = failure
        observer?.failed(failure)
    }
}

/** In-memory role framing only: no TCP, TLS handshake, peer authentication or Phone decoder. */
internal class CameraRoleSocket(val ref: CameraUseRef,
    welcomeDisposition: CameraAttachDisposition = CameraAttachDisposition.ATTACHED_TO_CAPTURED_USE) : SSLSocket() {
    private val gate = Object()
    private val bytes = ByteArrayOutputStream()
    private val inbound = ArrayDeque<ByteArray>()
    private var headOffset = 0
    private var welcomeBytesLeft = 0
    private var closed = false
    private var eof = false
    val awaitWelcome = CountDownLatch(1)
    val closeCalls = AtomicInteger()
    val emergencyWakeCalls = AtomicInteger()
    @Volatile var onRead: (() -> Unit)? = null
    @Volatile var onWrite: (() -> Unit)? = null
    @Volatile var onClose: (() -> Unit)? = null
    val written: ByteArray get() = synchronized(gate) { bytes.toByteArray() }
    val connector: (File, (Socket) -> Unit) -> SSLSocket = { _, opened -> opened(this); this }
    init {
        val welcome = ByteArrayOutputStream().also {
            CameraFeed.writeWelcome(DataOutputStream(it), ref, welcomeDisposition)
        }.toByteArray()
        inbound.add(welcome)
        welcomeBytesLeft = welcome.size
    }
    private val source = object : InputStream() {
        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 255
        }
        override fun read(target: ByteArray, offset: Int, length: Int): Int {
            onRead?.invoke()
            return synchronized(gate) {
                require(offset >= 0 && length >= 0 && offset.toLong() + length <= target.size)
                if (length == 0) return@synchronized 0
                while (inbound.isEmpty() && !closed && !eof) gate.wait()
                if (inbound.isEmpty()) return@synchronized -1
                val head = inbound.first
                val count = minOf(length, head.size - headOffset)
                head.copyInto(target, offset, headOffset, headOffset + count)
                headOffset += count
                if (welcomeBytesLeft > 0) {
                    welcomeBytesLeft -= count
                    if (welcomeBytesLeft == 0) awaitWelcome.countDown()
                }
                if (headOffset == head.size) { inbound.removeFirst(); headOffset = 0 }
                count
            }
        }
    }
    private val sink = object : OutputStream() {
        override fun write(value: Int) {
            onWrite?.invoke()
            synchronized(gate) {
                if (closed) throw IOException("Original fixture role socket closed")
                bytes.write(value)
            }
        }
        override fun write(value: ByteArray, offset: Int, length: Int) {
            onWrite?.invoke()
            synchronized(gate) {
                if (closed) throw IOException("Original fixture role socket closed")
                bytes.write(value, offset, length)
            }
        }
    }
    override fun getInputStream(): InputStream = source
    override fun getOutputStream(): OutputStream = sink
    override fun close() {
        closeCalls.incrementAndGet()
        onClose?.invoke()
        synchronized(gate) { closed = true; gate.notifyAll() }
    }
    override fun isClosed(): Boolean = synchronized(gate) { closed }
    override fun setSoTimeout(timeout: Int) = Unit
    /** Actual EOF from this in-memory input boundary, independently of close invocation/return. */
    fun remoteEof() = synchronized(gate) { eof = true; gate.notifyAll() }
    /** Teardown-only waiter wake; never changes S3's failed close receipt into a returned close. */
    fun emergencyWake() {
        emergencyWakeCalls.incrementAndGet()
        synchronized(gate) { closed = true; gate.notifyAll() }
    }
    fun remoteEnd(reason: CameraEndReason, stream: Pair<Long, Long>? = null) {
        val record = ByteArrayOutputStream().also { Wire.writeFrame(DataOutputStream(it),
            Frame(Wire.CAMERA_END, payload = CameraFeed.endBytes(CameraMediaEnd(ref, stream, reason)))) }.toByteArray()
        synchronized(gate) { check(!closed); inbound.add(record); gate.notifyAll() }
    }
    override fun getSupportedCipherSuites(): Array<String> = emptyArray()
    override fun getEnabledCipherSuites(): Array<String> = emptyArray()
    override fun setEnabledCipherSuites(suites: Array<out String>?) = Unit
    override fun getSupportedProtocols(): Array<String> = emptyArray()
    override fun getEnabledProtocols(): Array<String> = emptyArray()
    override fun setEnabledProtocols(protocols: Array<out String>?) = Unit
    override fun getSession(): SSLSession = error("No TLS session exists in the role fixture")
    override fun addHandshakeCompletedListener(listener: HandshakeCompletedListener?) = Unit
    override fun removeHandshakeCompletedListener(listener: HandshakeCompletedListener?) = Unit
    override fun startHandshake(): Unit = error("No TLS handshake exists in the role fixture")
    override fun setUseClientMode(mode: Boolean) = Unit
    override fun getUseClientMode(): Boolean = true
    override fun setNeedClientAuth(need: Boolean) = Unit
    override fun getNeedClientAuth(): Boolean = false
    override fun setWantClientAuth(want: Boolean) = Unit
    override fun getWantClientAuth(): Boolean = false
    override fun setEnableSessionCreation(flag: Boolean) = Unit
    override fun getEnableSessionCreation(): Boolean = false
}

/**
 * Locates actual S3 role objects under their own existing lock. Delivery methods call the exact
 * callbacks installed by ReceiverCameraOwner; they neither replace the owner nor manufacture joins.
 */
internal class CameraOwnerRoleControl(private val client: ReceiverCameraRoleClient) {
    private val gateField = ReceiverCameraRoleClient::class.java.getDeclaredField("gate").apply { isAccessible = true }
    private val rolesField = ReceiverCameraRoleClient::class.java.getDeclaredField("roles").apply { isAccessible = true }
    @Suppress("UNCHECKED_CAST")
    fun roles(): List<OwnedCameraRole> = synchronized(gateField.get(client)) {
        (rolesField.get(client) as List<OwnedCameraRole>).toList()
    }
    fun changed(original: OwnedCameraRole, previouslyObserved: RoleCloseObservation) =
        original.events.changed(original, previouslyObserved)
    fun useEnd(original: OwnedCameraRole, reason: CameraEndReason) =
        original.events.remoteEnd(original, CameraMediaEnd(original.original.ref, null, reason))
    fun retireAttachment(original: OwnedCameraRole, reason: CameraEndReason) = original.source.requestRetire(reason)
}
