package com.code2hack.eyeprojector.directhost.camera

import com.code2hack.eyeprojector.protocol.*
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.util.IdentityHashMap
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.CountDownLatch
import javax.net.ssl.SSLSocket

/** Implemented by the future actual inputConfigured/inputClosed membership owner, never by UI/pairing. */
interface OriginalPhoneDemandGroup

/** Actual future decoder/fanout boundary. Returns here do not imply decoding, output submission or capture. */
interface PhoneCameraMediaConsumer {
    fun description(original: PublishedCameraUse, role: PendingCameraRole, stream: CameraStreamRef, value: CameraStreamDescription)
    fun accessUnit(original: PublishedCameraUse, role: PendingCameraRole, stream: CameraStreamRef, value: CameraAccessUnit)
    fun remoteEnd(original: PublishedCameraUse, role: PendingCameraRole, value: CameraMediaEnd)
    /** Logical retirement of this exact ingress, not socket close, reader return or an output-use fence. */
    fun roleEnded(original: PublishedCameraUse, role: PendingCameraRole, reason: CameraEndReason)
    /** Nonblocking original-only retirement; the consumer retains its own output/fence/join obligations. */
    fun requestRetire(original: PublishedCameraUse, reason: CameraEndReason)
}

class PublishedCameraUse internal constructor(internal val owner: PhoneCameraMediaRole,
    internal val original: OriginalPhoneDemandGroup, val ref: CameraUseRef,
    internal var request: CameraStreamRequest, internal var consumer: PhoneCameraMediaConsumer?) {
    internal var started = false
    internal var retired = false
    internal var consumerRetirementRequested = false
    internal var consumerRetirementReturned: Boolean? = null
    internal var consumerRetirementTask: CameraTask? = null
    internal var remoteProgress: CameraProgress? = null
}
data class ControlSubmission(val fact: CameraFact)
data class ConsumerBindingObservation(val fact: CameraFact)
data class RoleRetirementSubmission(val fact: CameraFact)

/** A snapshot of the peer already admitted by the existing pinned mTLS checks. No new trust policy. */
class CapturedTrustedPeer private constructor(val principal: String) {
    companion object { fun authenticated(socket: SSLSocket) = CapturedTrustedPeer(socket.session.peerPrincipal.name) }
}
data class PhoneCameraRoleObservation(val use: CameraUseRef?, val disposition: CameraAttachDisposition?,
    val retirementRequested: Boolean, val socketCloseReturned: Boolean?, val readerStarted: Boolean,
    val readerReturned: Boolean, val writerStarted: Boolean, val writerReturned: Boolean,
    val readerJoined: Boolean, val writerJoined: Boolean, val failure: CameraEndReason?,
    val welcomeWriteReturned: Boolean, val framesRead: Long, val completionDelivered: Boolean?,
    val closeWorker: CameraTaskObservation?, val consumerRetireReturned: Boolean?, val completions: List<CameraCompletionObservation>,
    val inputShutdownAttempted: Boolean = false, val inputShutdownReturned: Boolean? = null,
    val roleEndedCallbackClaimed: Boolean = false, val roleEndedCallbackReturned: Boolean? = null,
    val roleEndedCallbackFailure: Throwable? = null, val roleEndedWorker: CameraTaskObservation? = null)

/** Kept by its captured Session even after failure; never looked up through a successor Session. */
class PendingCameraRole internal constructor(internal val owner: PhoneCameraMediaRole,
    internal val socket: SSLSocket, internal val input: DataInputStream, val trustedPeer: CapturedTrustedPeer) {
    internal var original: PublishedCameraUse? = null
    internal var consumer: PhoneCameraMediaConsumer? = null
    internal var disposition: CameraAttachDisposition? = null
    internal var stopping = false
    internal var socketCloseReturned: Boolean? = null
    internal var inputShutdownAttempted = false
    internal var inputShutdownReturned: Boolean? = null
    internal var reader: Thread? = null
    internal var writer: Thread? = null
    internal var readerStarted = false
    internal var readerReturned = false
    internal var writerStarted = false
    internal var writerReturned = false
    internal var readerJoined = false
    internal var writerJoined = false
    internal var failure: CameraEndReason? = null
    internal var welcomeWriteReturned = false
    internal var framesRead = 0L
    internal var joinClaimed = false
    internal var completionDelivered: Boolean? = null
    internal var retirementTask: CameraTask? = null
    internal var roleEndedCallbackClaimed = false
    internal var roleEndedCallbackReturned: Boolean? = null
    internal var roleEndedCallbackFailure: Throwable? = null
    internal var roleEndedTask: CameraTask? = null
    internal val completions = ArrayList<CameraCompletionRecord>()
    internal val outgoing = LinkedBlockingQueue<CameraMediaEnd>()
    internal val readerLaunchDone = CountDownLatch(1)
    internal val writerLaunchDone = CountDownLatch(1)
    fun startOwnedLane() = owner.startPending(this)
    fun snapshot(): PhoneCameraRoleObservation = owner.snapshot(this)
    fun requestStop(reason: CameraEndReason): RoleRetirementSubmission = owner.stop(this, reason)
    fun sendEnd(value: CameraMediaEnd): ControlSubmission = owner.sendEnd(this, value)
    fun closeAsync(executor: Executor, completion: (PhoneCameraRoleObservation) -> Unit) = owner.closeAsync(this, executor, completion)
}
data class SocketOwnershipTransfer(val claimed: Boolean, val pending: PendingCameraRole?)

/**
 * One original projection Session. Production never publishes a use until real Phone input membership exists.
 * gate owns only claims. I/O, consumers, starts, executor submissions and joins all happen outside it.
 */
class PhoneCameraMediaRole(private val ownerEpoch: String, private val sendProjection: (Frame) -> Unit,
    private val launch: (Thread) -> Unit = { it.start() },
    private val controlExecutor: java.util.concurrent.ExecutorService = Executors.newSingleThreadExecutor { Thread(it, "eyeprojector-camera-controls") },
    private val projectionFailure: (Throwable) -> Unit = {}) {
    private val gate = Any()
    private val sessionReference = UUID.randomUUID().toString()
    private var generation = 0L
    private var retiring = false
    private val groups = IdentityHashMap<OriginalPhoneDemandGroup, PublishedCameraUse>()
    private val uses = LinkedHashMap<CameraUseRef, PublishedCameraUse>()
    private val roles = ArrayList<PendingCameraRole>()
    private val controlQueue = ArrayDeque<Frame>()
    private var draining = false
    private var controlBroken = false
    init { CameraTasks.prepare() }
    @Volatile var controlFailure: Boolean = false; private set
    @Volatile var projectionSendFailure: Throwable? = null; private set
    @Volatile var projectionCleanupFailure: Throwable? = null; private set

    private fun valid(original: PublishedCameraUse) = original.owner === this && uses[original.ref] === original &&
        groups[original.original] === original && !original.retired && !retiring
    private fun enqueue(message: CameraUseMessage) { controlQueue.addLast(Frame(Wire.CAMERA_USE, payload = CameraFeed.useBytes(message))) }
    private fun publish(original: OriginalPhoneDemandGroup, request: CameraStreamRequest, consumer: PhoneCameraMediaConsumer?): PublishedCameraUse {
        check(!retiring && !groups.containsKey(original))
        generation = Math.incrementExact(generation)
        return PublishedCameraUse(this, original, CameraUseRef(ownerEpoch, sessionReference, generation), request, consumer)
            .also { groups[original] = it; uses[it.ref] = it }
    }
    fun publishConfigured(original: OriginalPhoneDemandGroup, request: CameraStreamRequest, consumer: PhoneCameraMediaConsumer?): PublishedCameraUse {
        val use = synchronized(gate) { publish(original, request, consumer).also { enqueue(CameraUseMessage.Prepare(it.ref, request)) } }
        drainControls(); return use
    }
    fun start(original: PublishedCameraUse, request: CameraStreamRequest): ControlSubmission {
        val result = synchronized(gate) {
            when {
                !valid(original) || original.started -> CameraFact.STALE_OR_DUPLICATE
                original.consumer == null -> CameraFact.UNAVAILABLE
                else -> { original.started = true; original.request = request; enqueue(CameraUseMessage.Start(original.ref, request)); CameraFact.PENDING }
            }
        }
        drainControls(); return ControlSubmission(result)
    }
    fun update(original: PublishedCameraUse, request: CameraStreamRequest): ControlSubmission {
        val result = synchronized(gate) {
            if (!valid(original) || original.request == request) CameraFact.STALE_OR_DUPLICATE
            else { original.request = request; enqueue(CameraUseMessage.Update(original.ref, request)); CameraFact.PENDING }
        }
        drainControls(); return ControlSubmission(result)
    }
    fun bindConsumer(original: PublishedCameraUse, consumer: PhoneCameraMediaConsumer): ConsumerBindingObservation = synchronized(gate) {
        ConsumerBindingObservation(when {
            !valid(original) -> CameraFact.STALE_OR_DUPLICATE
            original.consumer === consumer -> CameraFact.STALE_OR_DUPLICATE
            original.consumer != null -> CameraFact.REJECTED
            else -> { original.consumer = consumer; CameraFact.OBSERVED_RETURN }
        })
    }
    fun replace(original: PublishedCameraUse, replacement: OriginalPhoneDemandGroup, request: CameraStreamRequest,
        consumer: PhoneCameraMediaConsumer?): PublishedCameraUse {
        val pair = synchronized(gate) {
            check(valid(original) && !groups.containsKey(replacement))
            val next = publish(replacement, request, consumer)
            original.retired = true
            enqueue(CameraUseMessage.Replace(original.ref, next.ref, request, CameraEndReason.REPLACED))
            next to roles.filter { it.original === original }
        }
        drainControls(); pair.second.forEach { stop(it, CameraEndReason.REPLACED) }
        retireConsumer(original, CameraEndReason.REPLACED)
        return pair.first
    }
    fun retire(original: PublishedCameraUse, reason: CameraEndReason): ControlSubmission {
        val targets = synchronized(gate) {
            if (!valid(original)) return ControlSubmission(CameraFact.STALE_OR_DUPLICATE)
            original.retired = true; enqueue(CameraUseMessage.Close(original.ref, reason))
            roles.filter { it.original === original }
        }
        drainControls(); targets.forEach { stop(it, reason) }; retireConsumer(original, reason)
        return ControlSubmission(CameraFact.PENDING)
    }
    private fun retireConsumer(original: PublishedCameraUse, reason: CameraEndReason) {
        val task = synchronized(gate) {
            if (original.consumerRetirementRequested) null
            else {
                original.consumerRetirementRequested = true
                original.consumer?.let { consumer -> CameraTask.create("eyeprojector-camera-consumer-retire") {
                    val returned = runCatching { consumer.requestRetire(original, reason) }.isSuccess
                    synchronized(gate) { original.consumerRetirementReturned = returned }
                }.also { original.consumerRetirementTask = it } }
            }
        }
        task?.submit()
    }
    private fun drainControls() {
        val claimed = synchronized(gate) { if (draining || retiring || controlBroken || controlQueue.isEmpty()) false else { draining = true; true } }
        if (!claimed) return
        try { controlExecutor.execute {
            while (true) {
                val frame = synchronized(gate) {
                    if (retiring || controlQueue.isEmpty()) { draining = false; null } else controlQueue.removeFirst()
                } ?: break
                try { sendProjection(frame) } catch (error: Throwable) {
                    synchronized(gate) {
                        controlBroken = true; draining = false; controlQueue.clear()
                        if (projectionSendFailure == null) projectionSendFailure = error
                    }
                    controlFailure = true
                    // This task is the asynchronous failure boundary. A failed release stays
                    // retryable in its actual owner without becoming an uncaught backend crash.
                    try { projectionFailure(error) }
                    catch (cleanup: Throwable) { projectionCleanupFailure = cleanup }
                    break
                }
            }
        } } catch (_: Throwable) { synchronized(gate) { draining = false }; controlFailure = true }
    }

    /** Claim only. Bridge must call startPending after releasing admission. */
    fun takePendingSocket(socket: SSLSocket, input: DataInputStream, trustedPeer: CapturedTrustedPeer): SocketOwnershipTransfer = synchronized(gate) {
        if (retiring) SocketOwnershipTransfer(false, null)
        else PendingCameraRole(this, socket, input, trustedPeer).also { roles.add(it) }.let { SocketOwnershipTransfer(true, it) }
    }
    fun startPending(pending: PendingCameraRole) {
        val thread = try { Thread({ readRole(pending) }, "eyeprojector-phone-camera") }
        catch (_: Throwable) {
            synchronized(gate) { pending.disposition = CameraAttachDisposition.LOCAL_ROLE_START_FAILURE }
            stop(pending, CameraEndReason.LOCAL_RELEASE_FAILURE); return
        }
        val start = synchronized(gate) {
            check(pending.owner === this && roles.any { it === pending })
            if (pending.reader != null || pending.stopping) false else { pending.reader = thread; true }
        }
        if (!start) return
        try { launch(thread) } catch (_: Throwable) {
            synchronized(gate) { pending.readerStarted = thread.state != Thread.State.NEW; pending.failure = CameraEndReason.LOCAL_RELEASE_FAILURE; pending.disposition = CameraAttachDisposition.LOCAL_ROLE_START_FAILURE }
            stop(pending, CameraEndReason.LOCAL_RELEASE_FAILURE)
        } finally {
            synchronized(gate) { pending.readerStarted = thread.state != Thread.State.NEW }
            pending.readerLaunchDone.countDown()
        }
    }
    fun attach(pending: PendingCameraRole, hello: CameraUseRef): CameraAttachDisposition = synchronized(gate) {
        check(pending.owner === this && roles.any { it === pending })
        val original = uses[hello]
        val disposition = when {
            retiring -> CameraAttachDisposition.NO_ACTIVE_ORIGINAL_SESSION
            pending.stopping || pending.original != null -> CameraAttachDisposition.ATTACH_REPLACEMENT_RACE_LOST
            original == null -> CameraAttachDisposition.UNKNOWN_ORIGINAL_USE
            original.retired -> CameraAttachDisposition.ORIGINAL_USE_RETIRED
            original.consumer == null -> CameraAttachDisposition.PHONE_CONSUMER_UNAVAILABLE
            roles.any { it !== pending && it.original === original && !it.stopping } -> CameraAttachDisposition.ATTACH_REPLACEMENT_RACE_LOST
            else -> { pending.original = original; pending.consumer = original.consumer; CameraAttachDisposition.ATTACHED_TO_CAPTURED_USE }
        }
        pending.disposition = disposition; disposition
    }
    private fun readRole(role: PendingCameraRole) {
        var reason = CameraEndReason.TRANSPORT_EOF
        try {
            val hello = CameraFeed.readHello(role.input)
            val disposition = attach(role, hello)
            val output = DataOutputStream(role.socket.outputStream)
            CameraFeed.writeWelcome(output, hello, disposition)
            synchronized(gate) { role.welcomeWriteReturned = true }
            if (disposition != CameraAttachDisposition.ATTACHED_TO_CAPTURED_USE) return
            role.socket.soTimeout = 0 // The existing handshake bound is not a media-use expiry.
            val original = synchronized(gate) { role.original!! }
            val consumer = synchronized(gate) { role.consumer!! }
            val writer = try { Thread({ writeRole(role, output) }, "eyeprojector-phone-camera-end") }
            catch (_: Throwable) { reason = CameraEndReason.LOCAL_RELEASE_FAILURE; return }
            val start = synchronized(gate) { if (role.stopping) false else { role.writer = writer; true } }
            if (!start) return
            try { launch(writer) } catch (_: Throwable) {
                synchronized(gate) { role.writerStarted = writer.state != Thread.State.NEW }; reason = CameraEndReason.LOCAL_RELEASE_FAILURE; return
            } finally {
                synchronized(gate) { role.writerStarted = writer.state != Thread.State.NEW }
                role.writerLaunchDone.countDown()
            }
            val assembler = CameraFragmentAssembler()
            while (synchronized(gate) { !role.stopping && valid(original) }) {
                val frame = Wire.readFrame(role.input)
                synchronized(gate) { role.framesRead++ }
                when (frame.channel) {
                    Wire.CAMERA_STREAM -> {
                        val chunk = CameraFeed.readStream(frame.payload); require(chunk.ref.use == original.ref)
                        assembler.description(chunk)?.let { value ->
                            if (deliverable(role, original, consumer)) consumer.description(original, role, chunk.ref, value)
                        }
                    }
                    Wire.CAMERA_MEDIA -> {
                        val chunk = CameraFeed.readMedia(frame.payload); require(chunk.ref.use == original.ref)
                        assembler.media(chunk)?.let { value ->
                            if (deliverable(role, original, consumer)) consumer.accessUnit(original, role, chunk.ref, value)
                        }
                    }
                    Wire.CAMERA_END -> {
                        val end = CameraFeed.readEnd(frame.payload); require(end.use == original.ref)
                        if (deliverable(role, original, consumer)) consumer.remoteEnd(original, role, end)
                        val stream = end.stream
                        if (stream != null) assembler.end(CameraStreamRef(original.ref, stream.first, stream.second))
                        else { assembler.endUse(); reason = end.reason; break }
                    }
                    else -> error("Wrong camera role direction")
                }
            }
        } catch (_: EOFException) { reason = CameraEndReason.TRANSPORT_EOF }
        catch (_: Exception) { reason = CameraEndReason.PROTOCOL_FAILURE }
        finally { synchronized(gate) { role.readerReturned = true }; stop(role, reason) }
    }
    private fun deliverable(role: PendingCameraRole, original: PublishedCameraUse, consumer: PhoneCameraMediaConsumer) = synchronized(gate) {
        !role.stopping && valid(original) && role.original === original && role.consumer === consumer
    }
    private fun writeRole(role: PendingCameraRole, output: DataOutputStream) {
        try {
            while (synchronized(gate) { !role.stopping }) {
                val end = role.outgoing.take()
                Wire.writeFrame(output, Frame(Wire.CAMERA_END, payload = CameraFeed.endBytes(end)))
            }
        } catch (_: InterruptedException) { /* Original retirement wakes an empty writer. */ }
        catch (_: Exception) { stop(role, CameraEndReason.TRANSPORT_EOF) }
        finally { synchronized(gate) { role.writerReturned = true } }
    }
    internal fun sendEnd(role: PendingCameraRole, value: CameraMediaEnd): ControlSubmission {
        val accepted = synchronized(gate) { role.owner === this && !role.stopping && role.original?.ref == value.use && role.writerStarted }
        if (!accepted) return ControlSubmission(CameraFact.STALE_OR_DUPLICATE)
        role.outgoing.add(value); return ControlSubmission(CameraFact.PENDING)
    }
    internal fun stop(role: PendingCameraRole, reason: CameraEndReason): RoleRetirementSubmission {
        val selected = synchronized(gate) {
            check(role.owner === this)
            if (role.stopping) return RoleRetirementSubmission(CameraFact.STALE_OR_DUPLICATE)
            role.stopping = true; role.failure = reason
            val writer = role.writer
            val task = CameraTask.create("eyeprojector-phone-camera-close") {
                writer?.interrupt() // Independent wake, before potentially blocking socket close.
                synchronized(gate) { role.inputShutdownAttempted = true }
                val inputShutdown = runCatching { role.socket.shutdownInput() }.isSuccess
                synchronized(gate) { role.inputShutdownReturned = inputShutdown }
                val closed = runCatching { role.socket.close() }.isSuccess
                synchronized(gate) { role.socketCloseReturned = closed }
            }
            role.retirementTask = task
            val original = role.original
            val consumer = role.consumer
            val ended = if (original != null && consumer != null) {
                role.roleEndedCallbackClaimed = true
                CameraTask.create("eyeprojector-phone-camera-role-ended") {
                    // These captured originals remain valid identities after replacement/retirement.
                    // This independent job does not wait for shutdownInput, close or a reader join.
                    try {
                        consumer.roleEnded(original, role, reason)
                        synchronized(gate) { role.roleEndedCallbackReturned = true }
                    } catch (error: Throwable) {
                        synchronized(gate) {
                            role.roleEndedCallbackReturned = false
                            role.roleEndedCallbackFailure = error
                        }
                        throw error
                    }
                }.also { role.roleEndedTask = it }
            } else null
            Triple(original, task, ended)
        }
        // A throwing consumer runs on its own job and cannot suppress the existing native close.
        // Keep that close submission independent even if logical-notification submission fails.
        try { selected.third?.submit() } finally { selected.second.submit() }
        // An attempt ending does not retire its live demand. A fresh actual-owner attempt remains possible.
        val original = selected.first
        if (original != null && synchronized(gate) { original.retired || retiring }) retireConsumer(original, reason)
        return RoleRetirementSubmission(CameraFact.PENDING)
    }
    fun retireAll(reason: CameraEndReason): RoleRetirementSubmission {
        val captured = synchronized(gate) {
            if (retiring) return RoleRetirementSubmission(CameraFact.STALE_OR_DUPLICATE)
            retiring = true; uses.values.forEach { it.retired = true }; controlQueue.clear()
            roles.toList() to uses.values.toList()
        }
        captured.first.forEach { stop(it, reason) }; captured.second.forEach { retireConsumer(it, reason) }
        // The caller may be this executor's own failed writer. Admission/queue are already
        // retired; Session socket close cancels an in-flight send without self-interruption.
        controlExecutor.shutdown()
        return RoleRetirementSubmission(CameraFact.PENDING)
    }
    fun observeProgress(value: CameraProgress): CameraFact = synchronized(gate) {
        val original = uses[value.use]
        if (retiring || original == null || original.retired) CameraFact.STALE_OR_DUPLICATE
        else { original.remoteProgress = value; CameraFact.OBSERVED_RETURN }
    }
    fun remoteProgress(original: PublishedCameraUse): CameraProgress? = synchronized(gate) {
        if (original.owner === this && uses[original.ref] === original) original.remoteProgress else null
    }
    fun snapshot(): List<PhoneCameraRoleObservation> = synchronized(gate) { roles.map(::observation) }
    internal fun snapshot(role: PendingCameraRole): PhoneCameraRoleObservation = synchronized(gate) { check(role.owner === this); observation(role) }
    private fun observation(r: PendingCameraRole) = PhoneCameraRoleObservation(r.original?.ref, r.disposition, r.stopping,
        r.socketCloseReturned, r.readerStarted, r.readerReturned, r.writerStarted, r.writerReturned,
        r.readerJoined, r.writerJoined, r.failure, r.welcomeWriteReturned, r.framesRead, r.completionDelivered,
        r.retirementTask?.snapshot(), r.original?.consumerRetirementReturned, r.completions.map { it.snapshot() },
        r.inputShutdownAttempted, r.inputShutdownReturned, r.roleEndedCallbackClaimed,
        r.roleEndedCallbackReturned, r.roleEndedCallbackFailure, r.roleEndedTask?.snapshot())
    internal fun closeAsync(role: PendingCameraRole, executor: Executor, completion: (PhoneCameraRoleObservation) -> Unit) {
        stop(role, CameraEndReason.ORIGINAL_RETIRED)
        val request = CameraCompletionRecord.create("eyeprojector-phone-camera-join", {
            for (reader in listOf(true, false)) {
                val thread = synchronized(gate) { if (reader) role.reader else role.writer }
                if (thread != null) { if (reader) role.readerLaunchDone.await() else role.writerLaunchDone.await() }
                val started = synchronized(gate) { if (reader) role.readerStarted else role.writerStarted }
                if (thread != null && started && thread !== Thread.currentThread()) {
                    thread.join()
                    synchronized(gate) { if (reader) role.readerJoined = true else role.writerJoined = true }
                }
            }
            synchronized(gate) { role.retirementTask }?.await()
        }, executor) {
            try { completion(snapshot(role)); synchronized(gate) { role.completionDelivered = true } }
            catch (error: Throwable) { synchronized(gate) { if (role.completionDelivered != true) role.completionDelivered = false }; throw error }
        }
        synchronized(gate) { role.completions.add(request) }
        request.submit()
    }
}
