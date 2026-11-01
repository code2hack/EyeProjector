package com.code2hack.eyeprojector.directhost

import com.code2hack.eyeprojector.protocol.*
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

/** Optional observations only. No value here grants, delays, retries or acknowledges input. */
internal class PhoneInputTrace private constructor(
    private val file: File,
    private val context: ProjectionContext,
    private val sessionIdentity: Int,
    private val run: String,
) {
    private data class Receipt(val id: Long, val channel: Int, val stamp: Long, val bytes: Int, val monoNs: Long, val fields: Map<String, Any?>)
    private data class Fact(val sequence: Long, val monoNs: Long, val wallMs: Long, val thread: String,
        val stage: String, val receipt: Receipt?, val fields: Map<String, Any?>)
    private val sequence = AtomicLong()
    private val receipts = AtomicLong()
    private val frame = ThreadLocal<Receipt?>()
    private val pending = ConcurrentLinkedQueue<Fact>()
    private val ready = Semaphore(0)
    // Base Session plus explicit original frame/work/release obligations. No caller waits for disk.
    private val obligations = AtomicInteger(1)
    private val closing = AtomicBoolean()
    private val completion = Any()
    private val completionObservers = mutableListOf<() -> Unit>()
    @Volatile var terminated = false; private set
    internal class Obligation(private val finish: () -> Unit) : AutoCloseable {
        private val closed = AtomicBoolean()
        override fun close() { if (closed.compareAndSet(false, true)) finish() }
    }
    fun retain(): Obligation? {
        while (true) {
            val original = obligations.get()
            if (original == 0) return null
            if (obligations.compareAndSet(original, original + 1)) return Obligation {
                if (obligations.decrementAndGet() == 0) ready.release()
            }
        }
    }
    fun whenTerminated(observer: () -> Unit) {
        val completed = synchronized(completion) {
            if (terminated) true else { completionObservers.add(observer); false }
        }
        if (completed) runCatching(observer)
    }
    @Volatile var appendFailure: String? = null; private set
    @Volatile var appended = 0L; private set

    init {
        Thread({
            try { while (true) {
                ready.acquireUninterruptibly()
                while (true) {
                val fact = pending.poll() ?: break
                try {
                    val value = JSONObject().put("schema", 1).put("producer", "Phone-Shell-Session")
                        .put("run", run).put("ownerEpoch", context.ownerEpoch).put("projectionToken", context.projectionToken)
                        .put("sessionIdentity", sessionIdentity).put("observerSequence", fact.sequence).put("appendSequence", appended + 1)
                        .put("jvmMonoNs", fact.monoNs).put("wallMs", fact.wallMs).put("thread", fact.thread).put("stage", fact.stage)
                    fact.receipt?.let { value.put("frameReceiptId", it.id).put("frameChannel", it.channel)
                        .put("frameStamp", it.stamp).put("frameBytes", it.bytes).put("frameReceivedJvmMonoNs", it.monoNs)
                        it.fields.forEach { (key, field) -> value.put(key, field ?: JSONObject.NULL) } }
                    fact.fields.forEach { (key, field) -> value.put(key, field ?: JSONObject.NULL) }
                    // Open/append is solely on this daemon. One complete JSONL write per fact.
                    FileOutputStream(file, true).use { it.write((value.toString() + "\n").toByteArray(Charsets.UTF_8)) }
                    appended++
                } catch (error: Throwable) { appendFailure = error.javaClass.name }
                }
                if (obligations.get() == 0 && pending.isEmpty()) break
            } } finally {
                val observers = synchronized(completion) {
                    terminated = true
                    completionObservers.toList().also { completionObservers.clear() }
                }
                observers.forEach { runCatching(it) }
            }
        }, "eyeprojector-phone-input-trace").apply { isDaemon = true; start() }
    }

    /** Immutable primitive copies, a lock-free offer and nonblocking wake; never peer/file I/O. */
    fun record(stage: String, fields: Map<String, Any?> = emptyMap()) {
        val original = retain() ?: return
        try {
            pending.offer(Fact(sequence.getAndIncrement(), System.nanoTime(), System.currentTimeMillis(),
                Thread.currentThread().name, stage, frame.get(), fields.toMap()))
            ready.release()
        } catch (_: Throwable) { /* Optional observation cannot replace the original result/failure. */ }
        finally { original.close() }
    }

    fun <T> withFrame(original: Frame, operation: () -> T): T {
        val obligation = retain() ?: return operation()
        val previous = frame.get()
        frame.set(Receipt(receipts.getAndIncrement(), original.channel, original.stamp, original.payload.size, System.nanoTime(), decoded(original)))
        // Only metadata is captured here. Actual admission is stamped before its trace enqueue.
        var returned = false
        try { val result = operation(); returned = true; return result }
        catch (error: Throwable) { record("FRAME_THROW", mapOf("failureFamily" to error.javaClass.name)); throw error }
        finally { record("FRAME_EXIT", mapOf("returnedNormally" to returned)); frame.set(previous); obligation.close() }
    }

    private fun decoded(frame: Frame): Map<String, Any?> = try {
        when (frame.channel) {
            Wire.CONTROL_INTENT -> ReceiverControlIntent.from(frame.payload).let {
                mapOf("frameControlId" to it.id, "frameControlSource" to it.source.name,
                    "frameIncarnation" to it.context.incarnation.id, "frameInputRevision" to it.context.inputRevision)
            }
            Wire.FUNCTION_INTENT -> FunctionIntent.from(frame.payload).let {
                mapOf("frameGestureId" to it.gestureId, "frameFunctionSource" to it.source.name,
                    "frameFunctionKind" to it.kind.name, "frameFunctionSequence" to it.sequence,
                    "frameIncarnation" to it.context.usability.incarnation.id)
            }
            Wire.FUNCTION_ORIGINAL -> FunctionOriginal.from(frame.payload).let {
                mapOf("frameGestureId" to it.gestureId, "frameFunctionSource" to it.source.name,
                    "frameOriginalRetired" to it.retired, "frameSummaryOriginal" to it.summaryOriginal,
                    "frameIncarnation" to it.context.usability.incarnation.id)
            }
            Wire.TOUCH -> Touch.from(frame.payload).let { mapOf("frameTouchContactId" to it.contactId, "frameTouchPhase" to it.phase.name) }
            else -> emptyMap()
        }
    } catch (error: Throwable) { mapOf("traceDecodeFailure" to error.javaClass.name) }

    fun close() {
        if (!closing.compareAndSet(false, true)) return
        record("OBSERVER_RETIRING")
        if (obligations.decrementAndGet() == 0) ready.release()
    }

    fun status(): JSONObject = JSONObject().put("enabled", true).put("appended", appended)
        .put("appendFailure", appendFailure ?: JSONObject.NULL).put("closing", closing.get())
        .put("terminated", terminated).put("originalObligations", obligations.get())

    companion object {
        fun open(config: PhoneInputTraceConfig?, context: ProjectionContext, sessionIdentity: Int): PhoneInputTrace? = try {
            config?.let { create(File(it.path), context, sessionIdentity, it.run) }
        } catch (_: Throwable) { null }
        internal fun create(file: File, context: ProjectionContext, sessionIdentity: Int, run: String = "") =
            PhoneInputTrace(file, context, sessionIdentity, run)
    }
}
