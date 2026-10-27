package com.code2hack.eyeprojector.directrg.localcamera.forwarding

import com.code2hack.eyeprojector.protocol.CameraAccessUnit
import com.code2hack.eyeprojector.protocol.CameraCodecData
import com.code2hack.eyeprojector.protocol.CameraEndReason
import com.code2hack.eyeprojector.protocol.CameraFraming
import com.code2hack.eyeprojector.protocol.CameraStreamDescription
import com.code2hack.eyeprojector.protocol.CameraStreamRef
import com.code2hack.eyeprojector.protocol.CameraStreamRequest
import java.util.Collections
import java.util.IdentityHashMap

/**
 * One original physical encoder's detached output, shared by its actual Phone-use attachments.
 * Only format/packet callbacks rendezvous with role writers. No socket, native resource operation,
 * join or owner callback runs under this object's monitor. Taking a record is not a socket receipt.
 */
internal class EncoderCameraMediaSource(
    private val onMemberRetired: (Attachment, CameraEndReason) -> Unit,
    private val onFailure: (Throwable) -> Unit,
) : SurfaceEncoderEvents {
    private val gate = Object()
    private var encoder: CameraSurfaceEncoder? = null
    private var retired: CameraEndReason? = null
    private var format: EncoderFormatObservation? = null
    private var prepared: EncoderPreparedObservation? = null
    private var lifetime: EncoderLifetimeSnapshot? = null
    private val attachments = ArrayList<Attachment>()
    private val states = IdentityHashMap<Attachment, AttachmentState>()
    private val failures = ArrayList<Throwable>()
    private var publication: Publication? = null
    private var publications = 0L
    private var ignoredCallbacks = 0L

    private sealed interface Payload {
        data class Format(val value: EncoderFormatObservation) : Payload
        data class Packet(val value: EncoderAccessUnit) : Payload
    }
    private class Publication(val ordinal: Long, val payload: Payload) {
        val waiting = IdentityHashMap<Attachment, Unit>()
    }
    private class AttachmentState(var initial: EncoderFormatObservation?) {
        var pending: Publication? = null
        var endReason: CameraEndReason? = null
        var taken = 0L
    }

    /** Bind before prepareAsync; callbacks can never choose or replace this original. */
    fun bindEncoder(original: CameraSurfaceEncoder) = synchronized(gate) {
        check(encoder == null || encoder === original) { "A media source retains its original encoder" }
        encoder = original
    }

    /** The owner decides physical compatibility and retains admission before this attachment. */
    fun attach(original: CapturedPhoneCameraUse, ref: CameraStreamRef,
        request: CameraStreamRequest): Attachment {
        require(ref.use == original.ref) { "Stream belongs to another original use" }
        check(original.live && original.started) { "Only a live started use attaches encoded media" }
        return synchronized(gate) {
            check(retired == null && encoder != null) { "Original media source is unavailable" }
            Attachment(original, ref, request).also {
                attachments.add(it)
                states[it] = AttachmentState(format)
            }
        }
    }

    /** A role gets its own source identity, even for another attempt of the same captured use. */
    internal inner class Attachment internal constructor(
        val original: CapturedPhoneCameraUse,
        val ref: CameraStreamRef,
        val request: CameraStreamRequest,
    ) : CameraOutboundMediaSource {
        /** Only the captured S3 role writer calls next; retirement independently wakes its wait. */
        override fun next(): CameraOutboundRecord? {
            var payload: Payload? = null
            var interrupted: InterruptedException? = null
            synchronized(gate) {
                val state = states.getValue(this)
                while (state.endReason == null && state.initial == null && state.pending == null) {
                    try { gate.wait() }
                    catch (error: InterruptedException) { interrupted = error; break }
                }
                if (interrupted == null && state.endReason == null) {
                    val cached = state.initial
                    if (cached != null) {
                        state.initial = null
                        payload = Payload.Format(cached)
                    } else {
                        val current = state.pending
                        if (current != null) {
                            state.pending = null
                            payload = current.payload
                            current.waiting.remove(this)
                            settle(current)
                        }
                    }
                    if (payload != null) state.taken++
                }
            }
            if (interrupted != null) {
                requestRetire(CameraEndReason.LOCAL_RELEASE_FAILURE)
                Thread.currentThread().interrupt()
                return null
            }
            return when (val value = payload) {
                is Payload.Format -> CameraOutboundRecord.Description(ref,
                    CameraStreamDescription(request, "video/avc", value.value.actualCodecName,
                        value.value.observedWidth, value.value.observedHeight,
                        value.value.formatGeneration, CameraFraming.SOURCE_BYTES_UNCLASSIFIED,
                        value.value.csd.map { (key, bytes) -> CameraCodecData(key, bytes.copyBytes()) }))
                is Payload.Packet -> CameraOutboundRecord.AccessUnit(ref,
                    CameraAccessUnit(value.value.formatGeneration!!, value.value.sequence,
                        value.value.presentationTimeUs, value.value.codecFlags, value.value.bytes.copyBytes()))
                null -> null
            }
        }

        /** Closing one role cannot close a compatible member's physical source. */
        override fun requestRetire(reason: CameraEndReason) {
            val changed = synchronized(gate) { withdraw(this, reason) }
            if (changed) notifyMember(this, reason)
        }

        fun snapshot(): EncoderMediaAttachmentObservation = synchronized(gate) {
            val state = states.getValue(this)
            EncoderMediaAttachmentObservation(this, original, ref, request, state.endReason,
                state.initial?.formatGeneration, state.pending?.ordinal, state.taken)
        }
    }

    /** The owner requests original Camera2 stop independently before waiting for these writers. */
    fun retireSource(reason: CameraEndReason) {
        val selected = synchronized(gate) {
            if (retired != null) return
            retired = reason
            attachments.filter { withdraw(it, reason) }.also { gate.notifyAll() }
        }
        selected.forEach { notifyMember(it, reason) }
    }

    override fun prepared(original: CameraSurfaceEncoder, observation: EncoderPreparedObservation) {
        synchronized(gate) {
            if (matches(original, observation.encoder)) prepared = observation
        }
    }

    override fun observed(original: CameraSurfaceEncoder, snapshot: EncoderLifetimeSnapshot) {
        // This also runs on the real join observer; it must never wait for a role writer.
        synchronized(gate) {
            if (matches(original, snapshot.encoder)) lifetime = snapshot
        }
    }

    override fun failed(original: CameraSurfaceEncoder, failure: EncoderFailure) {
        val accepted = synchronized(gate) { matches(original, original.identity) }
        if (accepted) fail(failure.error)
    }

    override fun format(original: CameraSurfaceEncoder, observation: EncoderFormatObservation) =
        publish(original, observation.encoder, Payload.Format(observation))

    override fun packet(original: CameraSurfaceEncoder, packet: EncoderAccessUnit) =
        publish(original, packet.encoder, Payload.Packet(packet))

    private fun publish(original: CameraSurfaceEncoder, identity: SurfaceEncoderIdentity,
        payload: Payload) {
        var problem: Throwable? = null
        synchronized(gate) {
            if (!matches(original, identity) || retired != null) return
            try {
                while (publication != null && retired == null) gate.wait()
                if (retired != null) return
                when (payload) {
                    is Payload.Format -> {
                        if (payload.value.formatGeneration < 0)
                            problem = IllegalArgumentException("Negative actual encoder format generation")
                        else format = payload.value
                    }
                    is Payload.Packet -> {
                        if (payload.value.stream !== original.stream)
                            problem = IllegalArgumentException("Packet belongs to another original encoder stream")
                        else if (payload.value.formatGeneration == null ||
                            payload.value.formatGeneration != format?.formatGeneration)
                            problem = IllegalStateException("Actual encoder packet has no matching observed format")
                    }
                }
                if (problem == null) {
                    val current = Publication(++publications, payload)
                    attachments.filter { states.getValue(it).endReason == null }.forEach {
                        states.getValue(it).pending = current
                        current.waiting[it] = Unit
                    }
                    if (current.waiting.isNotEmpty()) {
                        publication = current
                        gate.notifyAll()
                        while (current.waiting.isNotEmpty() && retired == null) gate.wait()
                    }
                }
            } catch (error: InterruptedException) { problem = error }
        }
        problem?.let {
            fail(it)
            if (it is InterruptedException) Thread.currentThread().interrupt()
        }
    }

    /** Monitor held. Normal withdrawal releases a publisher without throwing into the encoder. */
    private fun withdraw(attachment: Attachment, reason: CameraEndReason): Boolean {
        val state = states.getValue(attachment)
        if (state.endReason != null) return false
        state.endReason = reason
        state.initial = null
        state.pending?.let { current ->
            state.pending = null
            current.waiting.remove(attachment)
            settle(current)
        }
        gate.notifyAll()
        return true
    }

    private fun settle(current: Publication) {
        if (current.waiting.isEmpty() && publication === current) publication = null
        gate.notifyAll()
    }

    private fun matches(original: CameraSurfaceEncoder, identity: SurfaceEncoderIdentity): Boolean {
        val accepted = encoder === original && original.identity === identity
        if (!accepted) ignoredCallbacks++
        return accepted
    }

    private fun fail(error: Throwable) {
        synchronized(gate) { failures.add(error) }
        notifyFailure(error)
        retireSource(CameraEndReason.ENCODER_FAILURE)
    }

    private fun notifyMember(attachment: Attachment, reason: CameraEndReason) {
        try { onMemberRetired(attachment, reason) }
        catch (error: Throwable) { synchronized(gate) { failures.add(error) }; notifyFailure(error) }
    }

    private fun notifyFailure(error: Throwable) {
        try { onFailure(error) }
        catch (observer: Throwable) { synchronized(gate) { failures.add(observer) } }
    }

    fun snapshot(): EncoderMediaSourceObservation = synchronized(gate) {
        EncoderMediaSourceObservation(encoder?.identity, retired, format?.formatGeneration,
            publication?.ordinal, publication?.waiting?.size ?: 0, publications, ignoredCallbacks,
            prepared, lifetime, Collections.unmodifiableList(ArrayList(failures)),
            Collections.unmodifiableList(attachments.map { it.snapshot() }))
    }
}

/** Local handoff observations only: none asserts socket receipt, Camera2 safety or thread closure. */
internal data class EncoderMediaAttachmentObservation(
    val attachment: EncoderCameraMediaSource.Attachment,
    val original: CapturedPhoneCameraUse,
    val ref: CameraStreamRef,
    val request: CameraStreamRequest,
    val retiredReason: CameraEndReason?,
    val initialFormatGeneration: Long?,
    val pendingPublication: Long?,
    val recordsTaken: Long,
)

internal data class EncoderMediaSourceObservation(
    val encoder: SurfaceEncoderIdentity?,
    val retiredReason: CameraEndReason?,
    val actualFormatGeneration: Long?,
    val pendingPublication: Long?,
    val waitingAttachments: Int,
    val publications: Long,
    val ignoredCallbacks: Long,
    val prepared: EncoderPreparedObservation?,
    val lifetime: EncoderLifetimeSnapshot?,
    val failures: List<Throwable>,
    val attachments: List<EncoderMediaAttachmentObservation>,
)
