package com.code2hack.eyeprojector.directrg.localcamera.forwarding

import com.code2hack.eyeprojector.protocol.*
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.IdentityHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import javax.net.ssl.SSLSocket

/** Opaque reference to one authenticated projection18 event, including its original local ledger. */
class CapturedPhoneCameraUse internal constructor(internal val owner: ReceiverCameraRoleClient,
    internal val handle: CameraUseLedger.Handle) {
    val ref: CameraUseRef get() = handle.ref
    val request: CameraStreamRequest get() = handle.request
    val started: Boolean get() = handle.started
    val live: Boolean get() = owner.live(this)
}
data class CameraUseEvent(val message: CameraUseMessage, val original: CapturedPhoneCameraUse?,
    val replacement: CapturedPhoneCameraUse?, val fact: CameraFact)

/**
 * One local progress dispatch. Its payload fact and dispatch outcome are independent.
 * actionStarted records event-task entry; sendClaimed is a pre-call claim, never native entry.
 */
data class CameraProgressSubmissionObservation(val original: CapturedPhoneCameraUse,
    val value: CameraProgress, val submissionReturned: Boolean?, val actionStarted: Boolean,
    val sendClaimed: Boolean, val actionReturned: Boolean?, val fact: CameraFact, val failure: Throwable?)

sealed interface CameraOutboundRecord {
    data class Description(val ref: CameraStreamRef, val value: CameraStreamDescription) : CameraOutboundRecord
    data class AccessUnit(val ref: CameraStreamRef, val value: CameraAccessUnit) : CameraOutboundRecord
    data class End(val value: CameraMediaEnd) : CameraOutboundRecord
}
/** Real future S1/S4 source; next runs only on its role's writer, with direct socket backpressure. */
interface CameraOutboundMediaSource {
    fun next(): CameraOutboundRecord?
    /** Nonblocking original retirement, independently unblocks next. Output/EOS/fence duties remain with source. */
    fun requestRetire(reason: CameraEndReason)
}
interface CameraRoleEvents {
    fun changed(role: OwnedCameraRole, value: RoleCloseObservation)
    fun remoteEnd(role: OwnedCameraRole, value: CameraMediaEnd)
}
data class RoleRetirementSubmission(val fact: CameraFact)
data class RoleCloseObservation(val use: CameraUseRef, val connected: Boolean,
    val welcome: CameraAttachDisposition?, val retirementRequested: Boolean,
    val socketCloseReturned: Boolean?, val sourceRetireReturned: Boolean?,
    val writerStarted: Boolean, val writerReturned: Boolean, val readerStarted: Boolean, val readerReturned: Boolean,
    val writerJoined: Boolean, val readerJoined: Boolean, val framesWritten: Long, val framesRead: Long,
    val failure: CameraEndReason?, val completionDelivered: Boolean?, val callbackFailed: Boolean,
    val retirementWorkers: List<CameraTaskObservation>, val socketCloseResults: List<Boolean?>,
    val completions: List<CameraCompletionObservation>)

class OwnedCameraRole internal constructor(internal val owner: ReceiverCameraRoleClient,
    val original: CapturedPhoneCameraUse, internal val source: CameraOutboundMediaSource, internal val events: CameraRoleEvents) {
    internal val sockets = ArrayList<Socket>()
    internal class SocketDuty { var task: CameraTask? = null; var result: Boolean? = null }
    internal val socketDuties = IdentityHashMap<Socket, SocketDuty>()
    internal val retirementTasks = ArrayList<CameraTask>()
    internal val completions = ArrayList<CameraCompletionRecord>()
    internal var stopping = false
    internal var connected = false
    internal var welcome: CameraAttachDisposition? = null
    internal var socketCloseReturned: Boolean? = null
    internal var sourceRetireReturned: Boolean? = null
    internal var writer: Thread? = null
    internal var reader: Thread? = null
    internal var writerStarted = false
    internal var writerReturned = false
    internal var readerStarted = false
    internal var readerReturned = false
    internal var writerJoined = false
    internal var readerJoined = false
    internal var framesWritten = 0L
    internal var framesRead = 0L
    internal var failure: CameraEndReason? = null
    internal var joinClaimed = false
    internal var completionDelivered: Boolean? = null
    internal var callbackFailed = false
    internal val writerLaunchDone = CountDownLatch(1)
    internal val readerLaunchDone = CountDownLatch(1)
    internal val retirementSubmitted = CountDownLatch(1)
    fun requestStop(reason: CameraEndReason): RoleRetirementSubmission = owner.stop(this, reason)
    fun snapshot(): RoleCloseObservation = owner.snapshot(this)
    fun closeAsync(executor: Executor, completion: (RoleCloseObservation) -> Unit) = owner.closeAsync(this, executor, completion)
}

/** Owned by one ProjectionSession, with an immutable epoch captured by its production factory. */
class ReceiverCameraRoleClient(private val directory: File, expectedPhoneOwnerEpoch: String?,
    private val sendProjection: (Frame) -> Unit,
    private val connector: (File, (Socket) -> Unit) -> SSLSocket = ::connectPinnedCamera,
    private val launch: (Thread) -> Unit = { it.start() }) {
    private val gate = Any()
    private val ledger = CameraUseLedger(expectedPhoneOwnerEpoch)
    private val handles = IdentityHashMap<CameraUseLedger.Handle, CapturedPhoneCameraUse>()
    private val roles = ArrayList<OwnedCameraRole>()
    private val sources = IdentityHashMap<CameraOutboundMediaSource, OwnedCameraRole>()
    init { CameraTasks.prepare() }
    private val events = Executors.newSingleThreadExecutor { Thread(it, "eyeprojector-camera-events") }
    private var retiring = false
    private var observer: ((CameraUseEvent) -> Unit)? = null
    private val remoteProgress = LinkedHashMap<CameraUseRef, CameraProgress>()
    private class ProgressSubmission(val original: CapturedPhoneCameraUse, val value: CameraProgress) {
        var submissionReturned: Boolean? = null
        var actionStarted = false
        var sendClaimed = false
        var actionReturned: Boolean? = null
        var fact = CameraFact.PENDING
        var failure: Throwable? = null
        fun snapshot() = CameraProgressSubmissionObservation(original, value, submissionReturned,
            actionStarted, sendClaimed, actionReturned, fact, failure)
    }
    private val progressSubmissions = ArrayList<ProgressSubmission>()
    @Volatile var eventDeliveryFailed = false; private set

    fun setUseObserver(value: ((CameraUseEvent) -> Unit)?) { synchronized(gate) { observer = value } }
    private fun captured(handle: CameraUseLedger.Handle?): CapturedPhoneCameraUse? = handle?.let {
        handles.getOrPut(it) { CapturedPhoneCameraUse(this, it) }
    }
    internal fun live(original: CapturedPhoneCameraUse): Boolean = synchronized(gate) {
        !retiring && original.owner === this && handles[original.handle] === original && ledger.live(original.handle)
    }
    fun receivedUses(): List<CapturedPhoneCameraUse> = synchronized(gate) { handles.values.toList() }
    fun observe(message: CameraUseMessage): CameraUseEvent {
        val selected = synchronized(gate) {
            val value = ledger.observe(message)
            val event = CameraUseEvent(message, captured(value.original), captured(value.replacement), value.fact)
            val stopped = if (value.fact == CameraFact.RETIRED) roles.filter { it.original === event.original } else emptyList()
            Triple(event, observer, stopped)
        }
        val reason = when (message) {
            is CameraUseMessage.Close -> message.reason
            is CameraUseMessage.Replace -> message.reason
            else -> CameraEndReason.UNKNOWN
        }
        selected.third.forEach { stop(it, reason) }
        val callback = selected.second
        if (callback != null) dispatch { callback(selected.first) }
        return selected.first
    }
    fun observeProgress(value: CameraProgress): CameraFact = synchronized(gate) {
        val original = handles.values.firstOrNull { it.ref == value.use }
        if (original == null || !live(original)) CameraFact.STALE_OR_DUPLICATE
        else { remoteProgress[value.use] = value; CameraFact.OBSERVED_RETURN }
    }
    fun remoteProgress(original: CapturedPhoneCameraUse): CameraProgress? = synchronized(gate) {
        if (original.owner === this && handles[original.handle] === original) remoteProgress[original.ref] else null
    }

    /**
     * Submit permission/foreground/source progress on the original projection's event lane, even
     * before a media role exists. PENDING is local submission; OBSERVED_RETURN is only send return.
     * No result here asserts a Phone read, decode, output submission or capture.
     */
    fun reportProgress(original: CapturedPhoneCameraUse, value: CameraProgress): CameraFact {
        val submission = synchronized(gate) {
            ProgressSubmission(original, value).also {
                progressSubmissions.add(it)
                if (original.owner !== this || handles[original.handle] !== original ||
                    !live(original) || value.use != original.ref) it.fact = CameraFact.STALE_OR_DUPLICATE
            }
        }
        if (synchronized(gate) { submission.fact == CameraFact.STALE_OR_DUPLICATE })
            return CameraFact.STALE_OR_DUPLICATE
        val frame = try { Frame(Wire.CAMERA_PROGRESS, payload = CameraFeed.progressBytes(value)) }
        catch (error: Throwable) {
            synchronized(gate) { submission.fact = CameraFact.FAILED; submission.failure = error }
            return CameraFact.FAILED
        }
        try {
            events.execute {
                val send = synchronized(gate) {
                    submission.actionStarted = true
                    if (!live(original)) { submission.fact = CameraFact.STALE_OR_DUPLICATE; false }
                    else { submission.sendClaimed = true; true }
                }
                if (send) {
                    try {
                        sendProjection(frame)
                        synchronized(gate) { submission.actionReturned = true; submission.fact = CameraFact.OBSERVED_RETURN }
                    } catch (error: Throwable) {
                        synchronized(gate) {
                            submission.actionReturned = false; submission.fact = CameraFact.FAILED; submission.failure = error
                        }
                        eventDeliveryFailed = true
                    }
                }
            }
            synchronized(gate) { submission.submissionReturned = true }
        } catch (error: Throwable) {
            synchronized(gate) {
                submission.submissionReturned = false; submission.fact = CameraFact.REJECTED; submission.failure = error
            }
            eventDeliveryFailed = true
        }
        return synchronized(gate) { submission.fact }
    }

    /** Historical local attempts remain inspectable after their original use is retired. */
    fun progressSubmissions(): List<CameraProgressSubmissionObservation> = synchronized(gate) {
        java.util.Collections.unmodifiableList(progressSubmissions.map { it.snapshot() })
    }
    private fun dispatch(action: () -> Unit) {
        try { events.execute { try { action() } catch (_: Throwable) { eventDeliveryFailed = true } } }
        catch (_: Throwable) { eventDeliveryFailed = true }
    }

    /** A concrete owner may request a fresh attempt for a still-live demand; no hidden retry or lifetime cap. */
    fun connect(original: CapturedPhoneCameraUse, source: CameraOutboundMediaSource, events: CameraRoleEvents): OwnedCameraRole {
        val role = synchronized(gate) {
            check(live(original))
            check(roles.none { it.original === original && !it.stopping })
            check(!sources.containsKey(source)) // A prior attempt keeps its source's exact retirement duty.
            OwnedCameraRole(this, original, source, events).also { roles.add(it); sources[source] = it }
        }
        val writer = try { Thread({ writeRole(role) }, "eyeprojector-camera-writer") }
        catch (_: Throwable) { stop(role, CameraEndReason.LOCAL_RELEASE_FAILURE); return role }
        val start = synchronized(gate) { role.writer = writer; !role.stopping }
        if (start) {
            try { launch(writer) } catch (_: Throwable) { stop(role, CameraEndReason.LOCAL_RELEASE_FAILURE) }
            finally { synchronized(gate) { role.writerStarted = writer.state != Thread.State.NEW }; role.writerLaunchDone.countDown() }
        } else role.writerLaunchDone.countDown()
        return role
    }
    private fun captureSocket(role: OwnedCameraRole, socket: Socket) {
        val close = synchronized(gate) {
            if (!role.socketDuties.containsKey(socket)) {
                role.sockets.add(socket); role.socketDuties[socket] = OwnedCameraRole.SocketDuty()
                if (role.socketCloseReturned != false) role.socketCloseReturned = null
            }
            role.stopping || !live(role.original)
        }
        if (close) { closeSocket(role, socket); error("Original camera role retired") }
    }
    private fun writeRole(role: OwnedCameraRole) {
        var reason = CameraEndReason.SOURCE_UNAVAILABLE
        try {
            if (!live(role.original) || synchronized(gate) { role.stopping }) return
            val socket = connector(directory) { captureSocket(role, it) }
            // Injected connectors must publish their actual returned socket too; production publishes raw and TLS.
            val known = synchronized(gate) { role.sockets.any { it === socket } }
            if (!known) captureSocket(role, socket)
            synchronized(gate) { role.connected = true }
            val output = DataOutputStream(socket.outputStream); val input = DataInputStream(socket.inputStream)
            Wire.writeRequest(output, Wire.CAMERA); CameraFeed.writeHello(output, role.original.ref)
            val disposition = CameraFeed.readWelcome(input, role.original.ref)
            synchronized(gate) { role.welcome = disposition }
            if (disposition != CameraAttachDisposition.ATTACHED_TO_CAPTURED_USE) { reason = CameraEndReason.CONSUMER_UNAVAILABLE; return }
            socket.soTimeout = 0
            val reader = try { Thread({ readRole(role, input) }, "eyeprojector-camera-reader") }
            catch (_: Throwable) { reason = CameraEndReason.LOCAL_RELEASE_FAILURE; return }
            val start = synchronized(gate) { if (role.stopping) false else { role.reader = reader; true } }
            if (!start) return
            try { launch(reader) } catch (_: Throwable) { reason = CameraEndReason.LOCAL_RELEASE_FAILURE; return }
            finally { synchronized(gate) { role.readerStarted = reader.state != Thread.State.NEW }; role.readerLaunchDone.countDown() }
            notify(role)
            val formats = HashMap<CameraStreamRef, CameraStreamDescription>()
            val sequences = HashMap<CameraStreamRef, MutableSet<Long>>()
            val ended = HashSet<CameraStreamRef>()
            while (live(role.original) && !synchronized(gate) { role.stopping }) {
                val next = role.source.next() ?: break
                if (!live(role.original) || synchronized(gate) { role.stopping }) break
                when (next) {
                    is CameraOutboundRecord.Description -> {
                        require(next.ref.use == role.original.ref && next.ref !in ended)
                        require(formats[next.ref]?.formatGeneration != next.value.formatGeneration)
                        CameraFeed.descriptionFrames(next.ref, next.value).forEach { write(role, output, it) }
                        formats[next.ref] = next.value
                    }
                    is CameraOutboundRecord.AccessUnit -> {
                        require(next.ref.use == role.original.ref && formats[next.ref]?.formatGeneration == next.value.formatGeneration)
                        require(sequences.getOrPut(next.ref) { HashSet() }.add(next.value.sequence))
                        CameraFeed.mediaFrames(next.ref, next.value).forEach { write(role, output, it) }
                    }
                    is CameraOutboundRecord.End -> {
                        require(next.value.use == role.original.ref)
                        write(role, output, Frame(Wire.CAMERA_END, payload = CameraFeed.endBytes(next.value)))
                        val stream = next.value.stream
                        if (stream == null) { reason = next.value.reason; break }
                        val ref = CameraStreamRef(role.original.ref, stream.first, stream.second)
                        require(ended.add(ref)); formats.remove(ref)
                    }
                }
            }
        } catch (_: EOFException) { reason = CameraEndReason.TRANSPORT_EOF }
        catch (_: Exception) { reason = CameraEndReason.PROTOCOL_FAILURE }
        finally { stop(role, reason); synchronized(gate) { role.writerReturned = true }; notify(role) }
    }
    private fun write(role: OwnedCameraRole, output: DataOutputStream, frame: Frame) {
        check(live(role.original) && !synchronized(gate) { role.stopping })
        Wire.writeFrame(output, frame)
        val receipt = System.nanoTime()
        synchronized(gate) { role.framesWritten++ }
        // Only this write return is observed. It says nothing about Phone decode/output/capture.
        dispatch { sendProjection(Frame(Wire.CAMERA_PROGRESS, payload = CameraFeed.progressBytes(CameraProgress(role.original.ref,
            null, CameraStage.SOCKET_WRITE_RETURN, CameraFact.OBSERVED_RETURN, CameraClock.JAVA_SYSTEM_NANOTIME_NS, receipt)))) }
    }
    private fun readRole(role: OwnedCameraRole, input: DataInputStream) {
        var reason = CameraEndReason.TRANSPORT_EOF
        try {
            while (!synchronized(gate) { role.stopping }) {
                val frame = Wire.readFrame(input); require(frame.channel == Wire.CAMERA_END)
                val end = CameraFeed.readEnd(frame.payload); require(end.use == role.original.ref)
                synchronized(gate) { role.framesRead++ }
                dispatch { role.events.remoteEnd(role, end) }
                if (end.stream == null) { reason = end.reason; break }
            }
        } catch (_: EOFException) { reason = CameraEndReason.TRANSPORT_EOF }
        catch (_: Exception) { reason = CameraEndReason.PROTOCOL_FAILURE }
        finally { stop(role, reason); synchronized(gate) { role.readerReturned = true }; notify(role) }
    }
    internal fun stop(role: OwnedCameraRole, reason: CameraEndReason): RoleRetirementSubmission {
        val sockets = synchronized(gate) {
            check(role.owner === this)
            if (role.stopping) return RoleRetirementSubmission(CameraFact.STALE_OR_DUPLICATE)
            role.stopping = true; role.failure = reason; role.sockets.toList()
        }
        // Each close and the source hook have independent actual workers. None can suppress another.
        sockets.forEach { closeSocket(role, it) }
        val retire = synchronized(gate) {
            CameraTask.create("eyeprojector-camera-source-retire") {
                val retired = runCatching { role.source.requestRetire(reason) }.isSuccess
                synchronized(gate) { role.sourceRetireReturned = retired }
                notify(role)
            }.also { role.retirementTasks.add(it) }
        }
        retire.submit()
        role.retirementSubmitted.countDown()
        return RoleRetirementSubmission(CameraFact.PENDING)
    }
    fun retireAll(reason: CameraEndReason): RoleRetirementSubmission {
        val captured = synchronized(gate) {
            if (retiring) return RoleRetirementSubmission(CameraFact.STALE_OR_DUPLICATE)
            retiring = true; ledger.retireAll(); roles.toList()
        }
        captured.forEach { stop(it, reason) }
        // Actual joins precede terminal event-lane shutdown, so no terminal notification can race it.
        CameraTask.create("eyeprojector-camera-projection-retire") {
            captured.forEach(::joinRole)
            events.shutdown()
        }.submit()
        return RoleRetirementSubmission(CameraFact.PENDING)
    }
    private fun notify(role: OwnedCameraRole) {
        dispatch {
            try { role.events.changed(role, snapshot(role)) } catch (_: Throwable) { synchronized(gate) { role.callbackFailed = true } }
        }
    }
    private fun closeSocket(role: OwnedCameraRole, socket: Socket) {
        val task = synchronized(gate) {
            val duty = role.socketDuties[socket]!!
            if (duty.task != null) null else CameraTask.create("eyeprojector-camera-socket-close") {
                val result = runCatching { socket.close() }.isSuccess
                synchronized(gate) {
                    duty.result = result
                    role.socketCloseReturned = when {
                        role.socketDuties.values.any { it.result == false } -> false
                        role.socketDuties.values.any { it.result == null } -> null
                        else -> true
                    }
                }
                notify(role)
            }.also { duty.task = it; role.retirementTasks.add(it) }
        }
        task?.submit()
    }
    internal fun snapshot(role: OwnedCameraRole): RoleCloseObservation = synchronized(gate) {
        check(role.owner === this)
        RoleCloseObservation(role.original.ref, role.connected, role.welcome, role.stopping, role.socketCloseReturned,
            role.sourceRetireReturned, role.writerStarted, role.writerReturned, role.readerStarted, role.readerReturned,
            role.writerJoined, role.readerJoined, role.framesWritten, role.framesRead, role.failure, role.completionDelivered, role.callbackFailed,
            role.retirementTasks.map { it.snapshot() }, role.sockets.map { role.socketDuties[it]!!.result },
            role.completions.map { it.snapshot() })
    }
    fun snapshot(): List<RoleCloseObservation> = synchronized(gate) { roles.map(::snapshot) }
    internal fun closeAsync(role: OwnedCameraRole, executor: Executor, completion: (RoleCloseObservation) -> Unit) {
        stop(role, CameraEndReason.ORIGINAL_RETIRED)
        val request = CameraCompletionRecord.create("eyeprojector-camera-join", { joinRole(role) }, executor) {
            try { completion(snapshot(role)); synchronized(gate) { role.completionDelivered = true } }
            catch (error: Throwable) { synchronized(gate) { if (role.completionDelivered != true) role.completionDelivered = false }; throw error }
        }
        synchronized(gate) { role.completions.add(request) }
        request.submit()
    }
    private fun joinRole(role: OwnedCameraRole) {
            for (writer in listOf(true, false)) {
                val thread = synchronized(gate) { if (writer) role.writer else role.reader }
                if (thread != null) {
                    if (writer) role.writerLaunchDone.await() else role.readerLaunchDone.await()
                    val started = synchronized(gate) { if (writer) role.writerStarted else role.readerStarted }
                    if (started && thread !== Thread.currentThread()) {
                        thread.join(); synchronized(gate) { if (writer) role.writerJoined = true else role.readerJoined = true }
                    }
                }
            }
            // Writer is joined: no later socket can be captured by this original attempt.
            role.retirementSubmitted.await()
            synchronized(gate) { role.retirementTasks.toList() }.forEach { it.await() }
    }
}

/** Same TLS endpoint/pin/PKIX/hostname behavior as projection, with owned sockets captured before blocking work. */
private fun connectPinnedCamera(directory: File, opened: (Socket) -> Unit): SSLSocket {
    val settings = Tls.settings(directory)
    val host = settings.getProperty("host") ?: error("Endpoint missing")
    val port = settings.getProperty("port").toInt().also { require(it in 1024..65535) }
    val factory = Tls.context(directory).socketFactory
    val raw = Socket(); opened(raw)
    raw.connect(InetSocketAddress(host, port), 3000)
    val socket = factory.createSocket(raw, settings.getProperty("peerName") ?: host, port, true) as SSLSocket
    opened(socket)
    socket.soTimeout = 4000; socket.tcpNoDelay = true; socket.sendBufferSize = 4096; socket.receiveBufferSize = 65536
    socket.enabledProtocols = socket.supportedProtocols.filter { it in listOf("TLSv1.3", "TLSv1.2") }.toTypedArray()
    socket.sslParameters = socket.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
    socket.startHandshake(); Tls.requireTrustedPeer(socket, directory)
    // Both sockets transferred through opened; the original role's claimed duties own every failure close.
    return socket
}
