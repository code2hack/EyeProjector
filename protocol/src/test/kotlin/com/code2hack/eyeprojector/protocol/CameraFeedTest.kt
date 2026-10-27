package com.code2hack.eyeprojector.protocol

import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicInteger

class CameraFeedTest {
    private val use = CameraUseRef("phone", "original-session", 0)
    private val ref = CameraStreamRef(use, 2, 3)
    private val request = CameraStreamRequest(640, 480, 35, "video/avc", 0, null, 0)
    private fun description(generation: Long = 0, csd: List<CameraCodecData> = emptyList()) =
        CameraStreamDescription(request, "video/avc", null, 0, null, generation, CameraFraming.SOURCE_BYTES_UNCLASSIFIED, csd)
    private fun frame(channel: Int, data: ByteArray): ByteArray = bytes { Wire.writeFrame(this, Frame(channel, payload = data)) }
    private fun malformed(data: ByteArray, decode: (ByteArray) -> Any) {
        assertThrows(Exception::class.java) { decode(data + byteArrayOf(0)) }
        assertThrows(Exception::class.java) { decode(data.copyOf(data.size - 1)) }
    }
    @Test fun exactIdentityAndUseOperationBytes() {
        assertArrayEquals(bytes { writeInt(5); write("phone".toByteArray()); writeInt(16); write("original-session".toByteArray()); writeLong(0) }, CameraFeed.useBytes(use))
        val messages = listOf(CameraUseMessage.Prepare(use, request), CameraUseMessage.Start(use, request), CameraUseMessage.Update(use, request.copy(frameRate = 0)),
            CameraUseMessage.Close(use, CameraEndReason.ORIGINAL_INPUT_CLOSED), CameraUseMessage.Replace(use, use.copy(useGeneration = 1), request, CameraEndReason.REPLACED))
        for ((index, message) in messages.withIndex()) {
            val encoded = CameraFeed.useBytes(message); assertEquals(index + 1, encoded[0].toInt())
            assertEquals(message, CameraFeed.readUseMessage(encoded)); malformed(encoded, CameraFeed::readUseMessage)
            val decoded = Wire.readFrame(DataInputStream(frame(Wire.CAMERA_USE, encoded).inputStream()))
            assertEquals(0L, decoded.stamp); assertArrayEquals(encoded, decoded.payload)
        }
        assertThrows(Exception::class.java) { CameraFeed.readUse(bytes { writeInt(-1) }) }
        assertThrows(Exception::class.java) { CameraFeed.readUse(bytes { writeInt(Int.MAX_VALUE) }) }
        assertThrows(Exception::class.java) { CameraFeed.readUse(bytes { writeInt(2); write(byteArrayOf(0xc0.toByte(), 0xaf.toByte())); writeInt(1); writeByte(65); writeLong(0) }) }
        assertThrows(Exception::class.java) { CameraFeed.useBytes(use.copy(ownerEpoch = "\uD800")) }
    }
    @Test fun optionalZeroAndTriStateFactsStayDistinct() {
        for (stage in CameraStage.entries) for (fact in CameraFact.entries) {
            val value = CameraProgress(use, 0L to 0L, stage, fact, CameraClock.JAVA_SYSTEM_NANOTIME_NS, 0)
            assertEquals(value, CameraFeed.readProgress(CameraFeed.progressBytes(value)))
        }
        for (clock in CameraClock.entries) {
            val value = CameraProgress(use, null, CameraStage.UNKNOWN, CameraFact.UNOBSERVED, clock, null)
            assertEquals(value, CameraFeed.readProgress(CameraFeed.progressBytes(value)))
            malformed(CameraFeed.progressBytes(value), CameraFeed::readProgress)
        }
        val value = CameraMediaEnd(use, null, CameraEndReason.UNKNOWN, 3, 1)
        assertEquals(true, value.fact(0)); assertEquals(false, value.fact(1)); assertNull(value.fact(2))
        assertEquals(value, CameraFeed.readEnd(CameraFeed.endBytes(value))); malformed(CameraFeed.endBytes(value), CameraFeed::readEnd)
        assertThrows(Exception::class.java) { CameraMediaEnd(use, null, CameraEndReason.UNKNOWN, 0, 1) }
        assertThrows(Exception::class.java) { CameraMediaEnd(use, null, CameraEndReason.UNKNOWN, 32, 0) }
        assertThrows(Exception::class.java) { CameraProgress(use, null, CameraStage.UNKNOWN, CameraFact.UNOBSERVED, localReceipt = 0) }
    }
    @Test fun descriptionCopiesAndUsesUnsignedUtf8KeyOrder() {
        val data = byteArrayOf(1, 2, 3); val csd = CameraCodecData("z", data); data[0] = 9
        csd.value[1] = 9
        val original = description(csd = listOf(CameraCodecData("\uD800\uDC00", byteArrayOf(4)), CameraCodecData("\uE000", byteArrayOf(5)), csd))
        val decoded = CameraFeed.readDescription(CameraFeed.descriptionBytes(original))
        assertEquals(listOf("z", "\uE000", "\uD800\uDC00"), decoded.csd.map { it.key })
        assertArrayEquals(byteArrayOf(1, 2, 3), decoded.csd.first().value)
        assertEquals(0, decoded.request.bitRate); assertNull(decoded.request.frameRate); assertEquals(0, decoded.observedWidth)
        assertNull(decoded.actualCodecName); assertEquals(CameraFraming.SOURCE_BYTES_UNCLASSIFIED, decoded.framing)
        malformed(CameraFeed.descriptionBytes(original), CameraFeed::readDescription)
        assertThrows(Exception::class.java) { description(csd = listOf(csd, csd)) }
        val encoded = CameraFeed.descriptionBytes(description())
        val malformedCount = encoded.copyOf().also { java.nio.ByteBuffer.wrap(it, it.size - 4, 4).putInt(Int.MAX_VALUE) }
        assertThrows(Exception::class.java) { CameraFeed.readDescription(malformedCount) }
    }
    @Test fun largeDescriptionAndAuFragmentAtActualFrameCeilingAndInterleaveStreams() {
        val original = description(csd = listOf(CameraCodecData("csd-0", ByteArray(Wire.MAX_PAYLOAD + 37) { it.toByte() })))
        val parts = CameraFeed.descriptionFrames(ref, original).toList()
        assertTrue(parts.size > 1); assertEquals(Wire.MAX_PAYLOAD, parts.first().payload.size)
        val assembler = CameraFragmentAssembler(); var decoded: CameraStreamDescription? = null
        for (part in parts) {
            val chunk = CameraFeed.readStream(part.payload); malformed(part.payload, CameraFeed::readStream)
            decoded = assembler.description(chunk)
        }
        assertArrayEquals(original.csd.single().value, decoded!!.csd.single().value)
        val second = ref.copy(encoderGeneration = 4)
        CameraFeed.descriptionFrames(second, description()).forEach { assembler.description(CameraFeed.readStream(it.payload)) }
        val bytes = ByteArray(Wire.MAX_PAYLOAD * 2 + 19) { (it * 7).toByte() }
        val au = CameraAccessUnit(0, 8, Long.MIN_VALUE + 8, Int.MIN_VALUE or 7, bytes); bytes[0] = 99
        val media = CameraFeed.mediaFrames(ref, au).toList(); assertEquals(Wire.MAX_PAYLOAD, media.first().payload.size)
        malformed(media.first().payload, CameraFeed::readMedia)
        assertNull(assembler.media(CameraFeed.readMedia(media.first().payload)))
        val empty = CameraFeed.mediaFrames(second, CameraAccessUnit(0, 0, -1, 0, byteArrayOf())).single()
        assertArrayEquals(byteArrayOf(), assembler.media(CameraFeed.readMedia(empty.payload))!!.data)
        var complete: CameraAccessUnit? = null
        for (part in media.drop(1)) complete = assembler.media(CameraFeed.readMedia(part.payload))
        assertEquals(au.ptsUs, complete!!.ptsUs); assertEquals(au.codecFlags, complete.codecFlags); assertArrayEquals(au.data, complete.data)
        assertThrows(Exception::class.java) { assembler.media(CameraFeed.readMedia(media.first().payload)) }
        assembler.end(second)
        assertThrows(Exception::class.java) { assembler.media(CameraFeed.readMedia(empty.payload)) }
    }
    @Test fun missingConfigGapsConflictsOverflowAndStampFail() {
        val assembler = CameraFragmentAssembler()
        val chunk = CameraMediaChunk(ref, 0, 0, -9, 3, Int.MAX_VALUE, 0, byteArrayOf(1))
        assertThrows(Exception::class.java) { assembler.media(chunk) }
        CameraFeed.descriptionFrames(ref, description()).forEach { assembler.description(CameraFeed.readStream(it.payload)) }
        assertNull(assembler.media(chunk)) // Advertised Int.MAX_VALUE does not allocate that amount.
        assertThrows(Exception::class.java) { assembler.media(CameraMediaChunk(ref, 0, 0, -9, 3, Int.MAX_VALUE, 2, byteArrayOf(1))) }
        assertThrows(Exception::class.java) { assembler.media(CameraMediaChunk(ref, 0, 0, -8, 3, Int.MAX_VALUE, 1, byteArrayOf(1))) }
        assertThrows(Exception::class.java) { assembler.end(ref) }
        assertThrows(Exception::class.java) { assembler.endUse() }
        assertThrows(Exception::class.java) { CameraMediaChunk(ref, 0, 0, 0, 0, Int.MAX_VALUE, Int.MAX_VALUE, byteArrayOf(1)) }
        assertThrows(Exception::class.java) { Wire.writeFrame(DataOutputStream(ByteArrayOutputStream()), Frame(Wire.CAMERA_PROGRESS, 1, CameraFeed.progressBytes(CameraProgress(use, null, CameraStage.UNKNOWN, CameraFact.UNOBSERVED)))) }
        val invalidTag = CameraFeed.progressBytes(CameraProgress(use, null, CameraStage.UNKNOWN, CameraFact.UNOBSERVED)).also { it[CameraFeed.useBytes(use).size] = 2 }
        assertThrows(Exception::class.java) { CameraFeed.readProgress(invalidTag) }
    }
    @Test fun literalNumericFixturesAndLongUtf8References() {
        val encodedUse = CameraFeed.useBytes(use)
        val progress = encodedUse + bytes { writeByte(1); writeLong(2); writeLong(3); writeByte(7); writeByte(2); writeByte(1); writeByte(1); writeLong(0) }
        assertEquals(CameraProgress(use, 2L to 3L, CameraStage.SOCKET_WRITE_RETURN, CameraFact.OBSERVED_RETURN, CameraClock.JAVA_SYSTEM_NANOTIME_NS, 0), CameraFeed.readProgress(progress))
        val media = encodedUse + bytes { writeLong(2); writeLong(3); writeLong(4); writeLong(5); writeLong(-6); writeInt(-7); writeInt(1); writeInt(0); writeInt(1); writeByte(8) }
        val decoded = CameraFeed.readMedia(media)
        assertEquals(ref, decoded.ref); assertEquals(4L, decoded.formatGeneration); assertEquals(5L, decoded.sequence)
        assertEquals(-6L, decoded.ptsUs); assertEquals(-7, decoded.codecFlags); assertArrayEquals(byteArrayOf(8), decoded.chunk)
        val long = use.copy(cameraSessionReference = "\u00e9".repeat(40000))
        assertEquals(long, CameraFeed.readUse(CameraFeed.useBytes(long)))
        val description = CameraFeed.descriptionBytes(description())
        val wrongGeneration = CameraStreamChunk(ref, 1, description.size, 0, description)
        assertThrows(Exception::class.java) { CameraFragmentAssembler().description(wrongGeneration) }
        val partial = CameraFragmentAssembler()
        assertNull(partial.description(CameraStreamChunk(ref, 0, description.size, 0, description.copyOfRange(0, 1))))
        assertThrows(Exception::class.java) { partial.end(ref) }
        assertThrows(Exception::class.java) { partial.description(CameraStreamChunk(ref, 1, description.size, 1, description.copyOfRange(1, description.size))) }
    }
    @Test fun cameraHandshakeIsDistinctAndStrict() {
        for (disposition in CameraAttachDisposition.entries) {
            val bytes = bytes { CameraFeed.writeWelcome(this, use, disposition) }
            assertEquals(disposition, CameraFeed.readWelcome(DataInputStream(bytes.inputStream()), use))
            assertThrows(Exception::class.java) { Wire.readWelcome(DataInputStream(bytes.inputStream())) }
        }
        val hello = bytes { Wire.writeRequest(this, Wire.CAMERA); CameraFeed.writeHello(this, use) }
        val input = DataInputStream(hello.inputStream()); assertEquals(Wire.CAMERA, Wire.readRequest(input)); assertEquals(use, CameraFeed.readHello(input)); assertEquals(-1, input.read())
        for (revision in listOf(5, 6)) assertThrows(Exception::class.java) {
            CameraFeed.readWelcome(DataInputStream(bytes { writeInt(Wire.MAGIC); writeInt(revision); writeByte(Wire.CAMERA) }.inputStream()), use)
        }
        assertThrows(Exception::class.java) { CameraFeed.readWelcome(DataInputStream(bytes { CameraFeed.writeWelcome(this, use, CameraAttachDisposition.ATTACHED_TO_CAPTURED_USE) }.inputStream()), use.copy(useGeneration = 1)) }
    }
    @Test fun ledgerRetainsOriginalTombstonesWithoutDisablingFreshRoles() {
        val ledger = CameraUseLedger("phone")
        assertEquals(CameraFact.STALE_OR_DUPLICATE, ledger.observe(CameraUseMessage.Start(use, request)).fact)
        assertEquals(CameraFact.STALE_OR_DUPLICATE, ledger.observe(CameraUseMessage.Close(use, CameraEndReason.ORIGINAL_INPUT_CLOSED)).fact)
        val prepared = ledger.observe(CameraUseMessage.Prepare(use, request)).original!!
        assertFalse(CameraUseLedger("phone").live(prepared))
        assertTrue(ledger.live(prepared)); assertEquals(CameraFact.STALE_OR_DUPLICATE, ledger.observe(CameraUseMessage.Prepare(use, request)).fact)
        assertEquals(CameraFact.OBSERVED_RETURN, ledger.observe(CameraUseMessage.Start(use, request)).fact)
        val replacement = use.copy(useGeneration = 1)
        val event = ledger.observe(CameraUseMessage.Replace(use, replacement, request, CameraEndReason.REPLACED))
        val replacementHandle = requireNotNull(event.replacement)
        assertSame(prepared, event.original); assertFalse(ledger.live(prepared)); assertTrue(ledger.live(replacementHandle))
        assertEquals(CameraFact.STALE_OR_DUPLICATE, ledger.observe(CameraUseMessage.Prepare(use, request)).fact)
        assertEquals(CameraFact.STALE_OR_DUPLICATE, ledger.observe(CameraUseMessage.Prepare(use.copy(ownerEpoch = "successor"), request)).fact)
        ledger.retireAll(); assertFalse(ledger.live(replacementHandle))
    }
    @Test fun inlineCallbackThenExecutorThrowRetainsBothActualOutcomes() {
        val callbacks = AtomicInteger()
        val record = CameraCompletionRecord.create("camera-completion-test", {}, Executor {
            it.run(); throw RejectedExecutionException("inline executor threw after return")
        }) { callbacks.incrementAndGet() }
        record.submit(); record.task.await()
        val actual = record.snapshot()
        assertEquals(1, callbacks.get()); assertTrue(actual.callbackEntered); assertEquals(true, actual.callbackReturned)
        assertEquals(false, actual.executorReturned); assertTrue(actual.submissionFailed)
        assertTrue(actual.worker.started); assertTrue(actual.worker.returned); assertTrue(actual.worker.joined)
    }
}
