package com.code2hack.eyeprojector.protocol

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue

/** Correlation only. These values never confer ownership of a local input, source or Session. */
data class CameraUseRef(val ownerEpoch: String, val cameraSessionReference: String, val useGeneration: Long) {
    init { require(ownerEpoch.isNotEmpty() && cameraSessionReference.isNotEmpty() && useGeneration >= 0) }
}
data class CameraStreamRef(val use: CameraUseRef, val sourceGeneration: Long, val encoderGeneration: Long) {
    init { require(sourceGeneration >= 0 && encoderGeneration >= 0) }
}
data class CameraStreamRequest(val requestedWidth: Int, val requestedHeight: Int,
    val requestedPixelFormat: Int, val requestedMime: String, val bitRate: Int? = null,
    val frameRate: Int? = null, val iFrameIntervalSeconds: Int? = null) {
    init { require(requestedWidth > 0 && requestedHeight > 0) }
}

enum class CameraEndReason(val code: Int) {
    UNKNOWN(0), ORIGINAL_INPUT_CLOSED(1), REPLACED(2), PERMISSION_DENIED(3), FOREGROUND_UNAVAILABLE(4),
    SOURCE_UNAVAILABLE(5), ENCODER_FAILURE(6), TRANSPORT_EOF(7), PROJECTION_CLOSED(8), ORIGINAL_RETIRED(9),
    PROTOCOL_FAILURE(10), LOCAL_RELEASE_FAILURE(11), CONSUMER_UNAVAILABLE(12);
    companion object { fun from(code: Int) = entries.single { it.code == code } }
}
enum class CameraFraming(val code: Int) {
    SOURCE_BYTES_UNCLASSIFIED(0), ANNEX_B_OBSERVED(1), LENGTH_PREFIXED_OBSERVED(2);
    companion object { fun from(code: Int) = entries.single { it.code == code } }
}
enum class CameraStage(val code: Int) {
    UNKNOWN(0), PERMISSION(1), FOREGROUND_CAMERA(2), SOURCE_PREPARATION(3), SOURCE_START(4), ENCODER_FORMAT(5),
    ENCODER_ACCESS_UNIT(6), SOCKET_WRITE_RETURN(7), SOCKET_FRAME_READ(8), DECODER_QUEUE_RETURN(9),
    OUTPUT_SUBMISSION_RETURN(10), END_REQUEST(11), LOCAL_RELEASE_OBSERVATION(12), ACTUAL_WORKER_JOIN(13);
    companion object { fun from(code: Int) = entries.single { it.code == code } }
}
enum class CameraFact(val code: Int) {
    UNOBSERVED(0), PENDING(1), OBSERVED_RETURN(2), UNAVAILABLE(3), REJECTED(4), FAILED(5), RETIRED(6), STALE_OR_DUPLICATE(7);
    companion object { fun from(code: Int) = entries.single { it.code == code } }
}
enum class CameraClock(val code: Int) {
    UNOBSERVED(0), JAVA_SYSTEM_NANOTIME_NS(1), ANDROID_ELAPSED_REALTIME_NANOS(2), ENCODER_PRESENTATION_US_SOURCE_UNVERIFIED(3);
    companion object { fun from(code: Int) = entries.single { it.code == code } }
}
enum class CameraAttachDisposition(val code: Int) {
    ATTACHED_TO_CAPTURED_USE(0), NO_ACTIVE_ORIGINAL_SESSION(1), UNKNOWN_ORIGINAL_USE(2), ORIGINAL_USE_RETIRED(3),
    PHONE_CONSUMER_UNAVAILABLE(4), ATTACH_REPLACEMENT_RACE_LOST(5), LOCAL_ROLE_START_FAILURE(6);
    companion object { fun from(code: Int) = entries.single { it.code == code } }
}

sealed interface CameraUseMessage {
    val use: CameraUseRef
    data class Prepare(override val use: CameraUseRef, val request: CameraStreamRequest) : CameraUseMessage
    data class Start(override val use: CameraUseRef, val request: CameraStreamRequest) : CameraUseMessage
    data class Update(override val use: CameraUseRef, val request: CameraStreamRequest) : CameraUseMessage
    data class Close(override val use: CameraUseRef, val reason: CameraEndReason) : CameraUseMessage
    data class Replace(override val use: CameraUseRef, val replacement: CameraUseRef,
        val request: CameraStreamRequest, val reason: CameraEndReason) : CameraUseMessage
}

/** Copies on both entry and exit; callers cannot mutate a published codec buffer. */
class CameraCodecData(val key: String, value: ByteArray) {
    private val storage = value.copyOf()
    val value: ByteArray get() = storage.copyOf()
}
class CameraStreamDescription(val request: CameraStreamRequest, val mime: String,
    val actualCodecName: String?, val observedWidth: Int?, val observedHeight: Int?,
    val formatGeneration: Long, val framing: CameraFraming, csd: List<CameraCodecData>) {
    val csd: List<CameraCodecData> = java.util.Collections.unmodifiableList(csd.map { CameraCodecData(it.key, it.value) })
    init { require(formatGeneration >= 0); require(this.csd.map { it.key }.toSet().size == this.csd.size) }
}
class CameraAccessUnit(val formatGeneration: Long, val sequence: Long, val ptsUs: Long, val codecFlags: Int, data: ByteArray) {
    init { require(formatGeneration >= 0 && sequence >= 0) }
    private val storage = data.copyOf()
    val data: ByteArray get() = storage.copyOf()
}
class CameraStreamChunk(val ref: CameraStreamRef, val formatGeneration: Long, val totalBytes: Int,
    val offset: Int, chunk: ByteArray) {
    private val storage = chunk.copyOf()
    val chunk: ByteArray get() = storage.copyOf()
    init { require(formatGeneration >= 0); cameraFragmentBounds(totalBytes, offset, storage.size) }
}
class CameraMediaChunk(val ref: CameraStreamRef, val formatGeneration: Long, val sequence: Long,
    val ptsUs: Long, val codecFlags: Int, val totalBytes: Int, val offset: Int, chunk: ByteArray) {
    private val storage = chunk.copyOf()
    val chunk: ByteArray get() = storage.copyOf()
    init { require(formatGeneration >= 0 && sequence >= 0); cameraFragmentBounds(totalBytes, offset, storage.size) }
}
private fun cameraFragmentBounds(total: Int, offset: Int, size: Int) {
    require(total >= 0 && offset >= 0 && offset.toLong() + size.toLong() <= total.toLong())
    require(size > 0 || total == 0 && offset == 0)
}
data class CameraProgress(val use: CameraUseRef, val stream: Pair<Long, Long>?, val stage: CameraStage,
    val fact: CameraFact, val clock: CameraClock = CameraClock.UNOBSERVED, val localReceipt: Long? = null) {
    init { require(stream == null || stream.first >= 0 && stream.second >= 0); require(clock != CameraClock.UNOBSERVED || localReceipt == null) }
}
data class CameraMediaEnd(val use: CameraUseRef, val stream: Pair<Long, Long>?, val reason: CameraEndReason,
    val factsPresent: Int = 0, val factsTrue: Int = 0) {
    init {
        require(stream == null || stream.first >= 0 && stream.second >= 0)
        require(factsPresent and 0x1f == factsPresent && factsTrue and factsPresent == factsTrue)
    }
    fun fact(bit: Int): Boolean? { require(bit in 0..4); return if (factsPresent and (1 shl bit) == 0) null else factsTrue and (1 shl bit) != 0 }
}

/** Strict records within an already authenticated, bounded enclosing record. No modified UTF-8. */
object CameraFeed {
    private fun encode(block: Writer.() -> Unit): ByteArray = bytes { Writer(this).block() }
    private fun <T> decode(data: ByteArray, block: Reader.() -> T): T {
        val input = DataInputStream(data.copyOf().inputStream())
        return Reader(input).block().also { require(input.available() == 0) }
    }
    private class Writer(val out: DataOutputStream) {
        fun string(value: String) {
            val encoded = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value))
            out.writeInt(encoded.remaining()); val data = ByteArray(encoded.remaining()); encoded.get(data); out.write(data)
        }
        fun blob(value: ByteArray) { out.writeInt(value.size); out.write(value) }
        fun <T> optional(value: T?, write: (T) -> Unit) { out.writeByte(if (value == null) 0 else 1); if (value != null) write(value) }
        fun use(value: CameraUseRef) { string(value.ownerEpoch); string(value.cameraSessionReference); out.writeLong(value.useGeneration) }
        fun ref(value: CameraStreamRef) { use(value.use); out.writeLong(value.sourceGeneration); out.writeLong(value.encoderGeneration) }
        fun stream(value: Pair<Long, Long>?) = optional(value) { out.writeLong(it.first); out.writeLong(it.second) }
        fun request(value: CameraStreamRequest) {
            out.writeInt(value.requestedWidth); out.writeInt(value.requestedHeight); out.writeInt(value.requestedPixelFormat); string(value.requestedMime)
            optional(value.bitRate, out::writeInt); optional(value.frameRate, out::writeInt); optional(value.iFrameIntervalSeconds, out::writeInt)
        }
    }
    private class Reader(val input: DataInputStream) {
        fun blob(): ByteArray { val size = input.readInt(); require(size >= 0 && size <= input.available()); return ByteArray(size).also(input::readFully) }
        fun string(): String = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(blob())).toString()
        fun <T> optional(read: () -> T): T? = when (input.readUnsignedByte()) { 0 -> null; 1 -> read(); else -> error("Invalid presence tag") }
        fun use() = CameraUseRef(string(), string(), input.readLong())
        fun ref() = CameraStreamRef(use(), input.readLong(), input.readLong())
        fun stream() = optional { (input.readLong() to input.readLong()).also { require(it.first >= 0 && it.second >= 0) } }
        fun request() = CameraStreamRequest(input.readInt(), input.readInt(), input.readInt(), string(),
            optional(input::readInt), optional(input::readInt), optional(input::readInt))
    }
    fun useBytes(use: CameraUseRef): ByteArray = encode { use(use) }
    fun readUse(data: ByteArray): CameraUseRef = decode(data) { use() }
    fun useBytes(message: CameraUseMessage): ByteArray = encode {
        out.writeByte(when (message) { is CameraUseMessage.Prepare -> 1; is CameraUseMessage.Start -> 2
            is CameraUseMessage.Update -> 3; is CameraUseMessage.Close -> 4; is CameraUseMessage.Replace -> 5 })
        use(message.use)
        when (message) {
            is CameraUseMessage.Prepare -> request(message.request)
            is CameraUseMessage.Start -> request(message.request)
            is CameraUseMessage.Update -> request(message.request)
            is CameraUseMessage.Close -> out.writeByte(message.reason.code)
            is CameraUseMessage.Replace -> { use(message.replacement); request(message.request); out.writeByte(message.reason.code) }
        }
    }
    fun readUseMessage(data: ByteArray): CameraUseMessage = decode(data) {
        val operation = input.readUnsignedByte(); val use = use()
        when (operation) {
            1 -> CameraUseMessage.Prepare(use, request()); 2 -> CameraUseMessage.Start(use, request())
            3 -> CameraUseMessage.Update(use, request()); 4 -> CameraUseMessage.Close(use, CameraEndReason.from(input.readUnsignedByte()))
            5 -> CameraUseMessage.Replace(use, use(), request(), CameraEndReason.from(input.readUnsignedByte()))
            else -> error("Unknown camera operation")
        }
    }
    fun descriptionBytes(value: CameraStreamDescription): ByteArray = encode {
        request(value.request); string(value.mime); optional(value.actualCodecName, ::string)
        optional(value.observedWidth, out::writeInt); optional(value.observedHeight, out::writeInt)
        out.writeLong(value.formatGeneration); out.writeByte(value.framing.code)
        val entries = value.csd.sortedWith { a, b -> compareUtf8(a.key, b.key) }
        out.writeInt(entries.size); entries.forEach { string(it.key); blob(it.value) }
    }
    fun readDescription(data: ByteArray): CameraStreamDescription = decode(data) {
        val request = request(); val mime = string(); val codec = optional(::string)
        val width = optional(input::readInt); val height = optional(input::readInt)
        val generation = input.readLong(); val framing = CameraFraming.from(input.readUnsignedByte())
        val count = input.readInt(); require(count >= 0 && count.toLong() * 8 <= input.available().toLong())
        val csd = ArrayList<CameraCodecData>(); var last: String? = null
        repeat(count) {
            val key = string(); require(last == null || compareUtf8(last!!, key) < 0)
            csd.add(CameraCodecData(key, blob())); last = key
        }
        CameraStreamDescription(request, mime, codec, width, height, generation, framing, csd)
    }
    private fun compareUtf8(a: String, b: String): Int {
        val left = a.toByteArray(Charsets.UTF_8); val right = b.toByteArray(Charsets.UTF_8)
        for (i in 0 until minOf(left.size, right.size)) {
            val order = (left[i].toInt() and 255).compareTo(right[i].toInt() and 255); if (order != 0) return order
        }
        return left.size.compareTo(right.size)
    }
    fun streamBytes(value: CameraStreamChunk): ByteArray = encode {
        ref(value.ref); out.writeLong(value.formatGeneration); out.writeInt(value.totalBytes); out.writeInt(value.offset); blob(value.chunk)
    }
    fun readStream(data: ByteArray): CameraStreamChunk = decode(data) { CameraStreamChunk(ref(), input.readLong(), input.readInt(), input.readInt(), blob()) }
    fun mediaBytes(value: CameraMediaChunk): ByteArray = encode {
        ref(value.ref); out.writeLong(value.formatGeneration); out.writeLong(value.sequence); out.writeLong(value.ptsUs)
        out.writeInt(value.codecFlags); out.writeInt(value.totalBytes); out.writeInt(value.offset); blob(value.chunk)
    }
    fun readMedia(data: ByteArray): CameraMediaChunk = decode(data) {
        CameraMediaChunk(ref(), input.readLong(), input.readLong(), input.readLong(), input.readInt(), input.readInt(), input.readInt(), blob())
    }
    fun progressBytes(value: CameraProgress): ByteArray = encode {
        use(value.use); stream(value.stream); out.writeByte(value.stage.code); out.writeByte(value.fact.code); out.writeByte(value.clock.code)
        optional(value.localReceipt, out::writeLong)
    }
    fun readProgress(data: ByteArray): CameraProgress = decode(data) {
        CameraProgress(use(), stream(), CameraStage.from(input.readUnsignedByte()), CameraFact.from(input.readUnsignedByte()),
            CameraClock.from(input.readUnsignedByte()), optional(input::readLong))
    }
    fun endBytes(value: CameraMediaEnd): ByteArray = encode {
        use(value.use); stream(value.stream); out.writeByte(value.reason.code); out.writeInt(value.factsPresent); out.writeInt(value.factsTrue)
    }
    fun readEnd(data: ByteArray): CameraMediaEnd = decode(data) { CameraMediaEnd(use(), stream(), CameraEndReason.from(input.readUnsignedByte()), input.readInt(), input.readInt()) }

    private fun writeRecord(output: DataOutputStream, body: ByteArray) {
        require(body.size <= Wire.MAX_PAYLOAD); output.writeInt(body.size); output.write(body); output.flush()
    }
    private fun readRecord(input: DataInputStream): ByteArray {
        val size = input.readInt(); require(size in 0..Wire.MAX_PAYLOAD); return ByteArray(size).also(input::readFully)
    }
    fun writeHello(output: DataOutputStream, use: CameraUseRef) = writeRecord(output, useBytes(use))
    fun readHello(input: DataInputStream): CameraUseRef = readUse(readRecord(input))
    fun writeWelcome(output: DataOutputStream, use: CameraUseRef, disposition: CameraAttachDisposition) {
        output.writeInt(Wire.MAGIC); output.writeInt(Wire.VERSION); output.writeByte(Wire.CAMERA)
        writeRecord(output, encode { use(use); out.writeByte(disposition.code) })
    }
    fun readWelcome(input: DataInputStream, expected: CameraUseRef): CameraAttachDisposition {
        require(input.readInt() == Wire.MAGIC && input.readInt() == Wire.VERSION && input.readUnsignedByte() == Wire.CAMERA)
        return decode(readRecord(input)) { require(use() == expected); CameraAttachDisposition.from(this.input.readUnsignedByte()) }
    }
    fun validate(frame: Frame) {
        require(frame.stamp == 0L)
        when (frame.channel) {
            Wire.CAMERA_USE -> readUseMessage(frame.payload); Wire.CAMERA_STREAM -> readStream(frame.payload)
            Wire.CAMERA_MEDIA -> readMedia(frame.payload); Wire.CAMERA_PROGRESS -> readProgress(frame.payload)
            Wire.CAMERA_END -> readEnd(frame.payload); else -> error("Not a camera record")
        }
    }
    fun descriptionFrames(ref: CameraStreamRef, value: CameraStreamDescription): Sequence<Frame> = sequence {
        val data = descriptionBytes(value)
        val capacity = Wire.MAX_PAYLOAD - streamBytes(CameraStreamChunk(ref, value.formatGeneration, 0, 0, byteArrayOf())).size
        require(capacity > 0)
        var offset = 0
        while (offset < data.size) {
            val end = minOf(data.size.toLong(), offset.toLong() + capacity).toInt()
            yield(Frame(Wire.CAMERA_STREAM, payload = streamBytes(CameraStreamChunk(ref, value.formatGeneration, data.size, offset, data.copyOfRange(offset, end)))))
            offset = end
        }
    }
    fun mediaFrames(ref: CameraStreamRef, value: CameraAccessUnit): Sequence<Frame> = sequence {
        val data = value.data
        val capacity = Wire.MAX_PAYLOAD - mediaBytes(CameraMediaChunk(ref, value.formatGeneration, value.sequence, value.ptsUs, value.codecFlags, 0, 0, byteArrayOf())).size
        require(capacity > 0)
        var offset = 0
        do {
            val end = minOf(data.size.toLong(), offset.toLong() + capacity).toInt()
            yield(Frame(Wire.CAMERA_MEDIA, payload = mediaBytes(CameraMediaChunk(ref, value.formatGeneration, value.sequence, value.ptsUs, value.codecFlags, data.size, offset, data.copyOfRange(offset, end)))))
            offset = end
        } while (offset < data.size)
    }
}

/** Incremental actual bytes only: advertised totalBytes never sizes an allocation. One role reader owns this object. */
class CameraFragmentAssembler {
    private class Stream {
        var description: CameraStreamDescription? = null
        val described = HashSet<Long>()
        val sequences = HashSet<Long>()
        var descriptionHead: CameraStreamChunk? = null
        var mediaHead: CameraMediaChunk? = null
        var bytes = ByteArrayOutputStream()
    }
    private val streams = HashMap<CameraStreamRef, Stream>()
    private val ended = HashSet<CameraStreamRef>()
    fun end(ref: CameraStreamRef) {
        val state = streams[ref]; require(state?.descriptionHead == null && state?.mediaHead == null)
        require(ended.add(ref)); streams.remove(ref)
    }
    fun endUse() { require(streams.values.none { it.descriptionHead != null || it.mediaHead != null }) }
    fun description(chunk: CameraStreamChunk): CameraStreamDescription? {
        require(chunk.ref !in ended)
        val state = streams.getOrPut(chunk.ref) { Stream() }; require(state.mediaHead == null)
        val head = state.descriptionHead
        if (head == null) {
            require(chunk.offset == 0 && chunk.formatGeneration !in state.described); state.descriptionHead = chunk
        } else require(head.formatGeneration == chunk.formatGeneration && head.totalBytes == chunk.totalBytes)
        require(chunk.offset == state.bytes.size()); state.bytes.write(chunk.chunk)
        if (state.bytes.size() != chunk.totalBytes) return null
        val description = CameraFeed.readDescription(state.bytes.toByteArray())
        require(description.formatGeneration == chunk.formatGeneration)
        state.description = description; state.described.add(chunk.formatGeneration)
        state.descriptionHead = null; state.bytes = ByteArrayOutputStream()
        return description
    }
    fun media(chunk: CameraMediaChunk): CameraAccessUnit? {
        val state = streams[chunk.ref] ?: error("Media before description")
        require(state.descriptionHead == null && state.description?.formatGeneration == chunk.formatGeneration)
        val head = state.mediaHead
        if (head == null) { require(chunk.offset == 0 && chunk.sequence !in state.sequences); state.mediaHead = chunk }
        else require(head.formatGeneration == chunk.formatGeneration && head.sequence == chunk.sequence && head.ptsUs == chunk.ptsUs && head.codecFlags == chunk.codecFlags && head.totalBytes == chunk.totalBytes)
        require(chunk.offset == state.bytes.size()); state.bytes.write(chunk.chunk)
        if (state.bytes.size() != chunk.totalBytes) return null
        val unit = CameraAccessUnit(chunk.formatGeneration, chunk.sequence, chunk.ptsUs, chunk.codecFlags, state.bytes.toByteArray())
        state.sequences.add(chunk.sequence); state.mediaHead = null; state.bytes = ByteArrayOutputStream()
        return unit
    }
}

/**
 * Shared launch dispatcher, prepared at owner construction. Enqueue never starts or calls foreign work
 * on a projection/control caller. Each job has its own actual thread, retained start/return/join facts,
 * and no idle per-Session pool, timer or queue limit. Media bytes never pass through this dispatcher.
 */
object CameraTasks {
    private val queue = LinkedBlockingQueue<CameraTask>()
    private val dispatcher = Thread({ while (true) queue.take().startOwned() }, "eyeprojector-camera-launch").apply { isDaemon = true; start() }
    fun prepare() { /* Access initializes the shared dispatcher before any caller's close gate. */ }
    internal fun enqueue(task: CameraTask) { queue.add(task) }
}
class CameraTask private constructor(private val name: String, private val action: () -> Unit) {
    private val submitted = java.util.concurrent.atomic.AtomicBoolean()
    private val launched = CountDownLatch(1)
    private val gate = Any()
    @Volatile private var observation = CameraTaskObservation(false, false, false, false)
    @Volatile private var thread: Thread? = null
    val started: Boolean get() = observation.started
    val returned: Boolean get() = observation.returned
    val joined: Boolean get() = observation.joined
    val failed: Boolean get() = observation.failed
    fun submit() { if (submitted.compareAndSet(false, true)) CameraTasks.enqueue(this) }
    internal fun startOwned() {
        try {
            // Thread construction can call inherited thread-local hooks, so it belongs here too.
            val owned = Thread({
                synchronized(gate) { observation = observation.copy(started = true) }
                try { action() } catch (_: Throwable) { synchronized(gate) { observation = observation.copy(failed = true) } }
                finally { synchronized(gate) { observation = observation.copy(returned = true) } }
            }, name).apply { isDaemon = true }
            thread = owned; owned.start()
        } catch (_: Throwable) { synchronized(gate) { observation = observation.copy(failed = true) } }
        finally { launched.countDown() }
    }
    /** Only call on another owned job, never a UI/control/projection thread. */
    fun await() {
        launched.await()
        val owned = thread
        if (owned != null && owned.state != Thread.State.NEW && owned !== Thread.currentThread()) {
            owned.join(); synchronized(gate) { observation = observation.copy(joined = true) }
        }
    }
    fun snapshot() = observation
    companion object { fun create(name: String, action: () -> Unit) = CameraTask(name, action) }
}
data class CameraTaskObservation(val started: Boolean, val returned: Boolean, val joined: Boolean, val failed: Boolean)

/** One completion request; submission, inline callback return and task lifecycle remain separate facts. */
class CameraCompletionRecord private constructor(name: String, awaitOriginal: () -> Unit,
    executor: java.util.concurrent.Executor, completion: () -> Unit) {
    private val gate = Any()
    private var executorReturned: Boolean? = null
    private var callbackEntered = false
    private var callbackReturned: Boolean? = null
    private var submissionFailed = false
    val task = CameraTask.create(name) {
        awaitOriginal()
        try {
            executor.execute {
                synchronized(gate) { callbackEntered = true }
                try { completion(); synchronized(gate) { callbackReturned = true } }
                catch (_: Throwable) { synchronized(gate) { callbackReturned = false } }
            }
            synchronized(gate) { executorReturned = true }
        } catch (_: Throwable) { synchronized(gate) { executorReturned = false; submissionFailed = true } }
    }
    fun submit() = task.submit()
    fun snapshot(): CameraCompletionObservation = synchronized(gate) {
        CameraCompletionObservation(task.snapshot(), executorReturned, callbackEntered, callbackReturned, submissionFailed)
    }
    companion object {
        fun create(name: String, awaitOriginal: () -> Unit, executor: java.util.concurrent.Executor, completion: () -> Unit) =
            CameraCompletionRecord(name, awaitOriginal, executor, completion)
    }
}
data class CameraCompletionObservation(val worker: CameraTaskObservation, val executorReturned: Boolean?,
    val callbackEntered: Boolean, val callbackReturned: Boolean?, val submissionFailed: Boolean)

/** Semantic ordering for authenticated projection18. Tombstones remain for this original projection. */
class CameraUseLedger(private val expectedOwnerEpoch: String?) {
    class Handle internal constructor(internal val owner: CameraUseLedger, val ref: CameraUseRef, request: CameraStreamRequest) {
        @Volatile var request = request; internal set
        @Volatile var started = false; internal set
        @Volatile var retired = false; internal set
    }
    data class Observation(val message: CameraUseMessage, val original: Handle?, val replacement: Handle?, val fact: CameraFact)
    private val gate = Any()
    private var closed = false
    private var cameraSession: String? = null
    private val uses = LinkedHashMap<CameraUseRef, Handle>()
    fun live(handle: Handle): Boolean = synchronized(gate) { handle.owner === this && uses[handle.ref] === handle && !closed && !handle.retired }
    fun observe(message: CameraUseMessage): Observation = synchronized(gate) {
        fun stale() = Observation(message, uses[message.use], null, CameraFact.STALE_OR_DUPLICATE)
        val ref = message.use
        if (closed || expectedOwnerEpoch == null || ref.ownerEpoch != expectedOwnerEpoch || cameraSession != null && cameraSession != ref.cameraSessionReference) return@synchronized stale()
        val old = uses[ref]
        when (message) {
            is CameraUseMessage.Prepare -> {
                if (old != null) return@synchronized stale()
                cameraSession = ref.cameraSessionReference
                val next = Handle(this, ref, message.request); uses[ref] = next
                Observation(message, next, null, CameraFact.OBSERVED_RETURN)
            }
            is CameraUseMessage.Start -> {
                if (old == null || old.retired || old.started) return@synchronized stale()
                old.started = true; old.request = message.request; Observation(message, old, null, CameraFact.OBSERVED_RETURN)
            }
            is CameraUseMessage.Update -> {
                if (old == null || old.retired || old.request == message.request) return@synchronized stale()
                old.request = message.request; Observation(message, old, null, CameraFact.OBSERVED_RETURN)
            }
            is CameraUseMessage.Close -> {
                if (old == null || old.retired) return@synchronized stale()
                old.retired = true; Observation(message, old, null, CameraFact.RETIRED)
            }
            is CameraUseMessage.Replace -> {
                val nextRef = message.replacement
                if (old == null || old.retired || nextRef in uses || nextRef.ownerEpoch != expectedOwnerEpoch || nextRef.cameraSessionReference != cameraSession) return@synchronized stale()
                old.retired = true
                val next = Handle(this, nextRef, message.request); uses[nextRef] = next
                Observation(message, old, next, CameraFact.RETIRED)
            }
        }
    }
    fun retireAll(): List<Handle> = synchronized(gate) {
        closed = true; uses.values.onEach { it.retired = true }.toList()
    }
}
