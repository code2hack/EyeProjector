package com.code2hack.eyeprojector.directhost.activation.snapshot

import android.os.Parcel
import android.os.Parcelable
import android.os.SharedMemory
import android.system.OsConstants
import com.code2hack.eyeprojector.directhost.ProjectionContext
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/** Owns its FD. Binder return marshalling closes the sender's independently duplicated FD. */
class NativeSnapshotDescriptor internal constructor(
    internal val memory: SharedMemory,
    val context: ProjectionContext,
    val regionId: String,
    val cellOffset: Int,
    private val closeAfterReturn: Boolean = false,
) : Parcelable, Closeable {
    override fun describeContents() = Parcelable.CONTENTS_FILE_DESCRIPTOR
    override fun writeToParcel(dest: Parcel, flags: Int) {
        try {
            memory.writeToParcel(dest, flags)
            dest.writeString(context.ownerEpoch); dest.writeString(context.projectionToken)
            dest.writeString(regionId); dest.writeInt(cellOffset)
        } finally {
            if (closeAfterReturn && flags and Parcelable.PARCELABLE_WRITE_RETURN_VALUE != 0) close()
        }
    }
    override fun close() = memory.close()
    companion object {
        @JvmField val CREATOR = object : Parcelable.Creator<NativeSnapshotDescriptor> {
            override fun createFromParcel(source: Parcel): NativeSnapshotDescriptor {
                val memory = SharedMemory.CREATOR.createFromParcel(source)
                try {
                    return NativeSnapshotDescriptor(memory, ProjectionContext(source.readString()!!, source.readString()!!),
                        source.readString()!!, source.readInt())
                } catch (error: Throwable) { memory.close(); throw error }
            }
            override fun newArray(size: Int) = arrayOfNulls<NativeSnapshotDescriptor>(size)
        }
    }
}

internal object SnapshotLayout {
    const val MAGIC = 0x4550534e
    const val VERSION = 1
    private const val PREFIX = 16
    fun header(context: ProjectionContext, regionId: String): ByteArray {
        val identity = ByteArrayOutputStream().also { bytes -> DataOutputStream(bytes).use {
            it.writeUTF(context.ownerEpoch); it.writeUTF(context.projectionToken); it.writeUTF(regionId)
        } }.toByteArray()
        val offset = (Math.addExact(PREFIX, identity.size) + 7) and -8
        return ByteBuffer.allocate(offset).order(ByteOrder.BIG_ENDIAN)
            .putInt(MAGIC).putInt(VERSION).putInt(offset).putInt(Math.addExact(offset, 8)).put(identity).array()
    }
    fun validate(mapping: ByteBuffer, descriptor: NativeSnapshotDescriptor) {
        val expected = header(descriptor.context, descriptor.regionId)
        require(descriptor.cellOffset == expected.size && mapping.capacity() == expected.size + 8)
        val actual = ByteArray(expected.size)
        mapping.duplicate().apply { position(0); get(actual) }
        require(actual.contentEquals(expected)) { "Snapshot header does not match original descriptor" }
    }
}

/** One immutable region/subscription; caller integrates publication/retirement before any blocking cleanup later. */
internal class NativeSnapshotWriter private constructor(
    val context: ProjectionContext,
    val regionId: String,
    private val memory: SharedMemory,
    private val mapping: ByteBuffer,
    private val address: Long,
    private val offset: Int,
) : Closeable {
    enum class Publication { PUBLISHED, ROTATE_REQUIRED, RETIRED }
    private var stamp = 0L
    private var retired = false
    private var closed = false
    @Synchronized fun descriptor(): NativeSnapshotDescriptor {
        check(!closed && !retired)
        val parcel = Parcel.obtain()
        val duplicate = try {
            memory.writeToParcel(parcel, 0); parcel.setDataPosition(0)
            SharedMemory.CREATOR.createFromParcel(parcel)
        } finally { parcel.recycle() }
        return NativeSnapshotDescriptor(duplicate, context, regionId, offset, closeAfterReturn = true)
    }
    @Synchronized fun publish(mode: Int): Publication {
        require(mode in SnapshotWord.NONE..SnapshotWord.PROJECTED)
        if (retired || closed) return Publication.RETIRED
        if (stamp == SnapshotWord.MAX_STAMP) { retire(); return Publication.ROTATE_REQUIRED }
        NativeSnapshotAccess.releaseCell(address, SnapshotWord.pack(++stamp, mode))
        return Publication.PUBLISHED
    }
    @Synchronized fun retire() {
        if (retired || closed) return
        retired = true
        NativeSnapshotAccess.releaseCell(address, SnapshotWord.pack(stamp, SnapshotWord.RETIRED))
    }
    @Synchronized override fun close() {
        if (closed) return
        retire(); closed = true
        try { SharedMemory.unmap(mapping) } finally { memory.close() }
    }
    companion object {
        fun create(context: ProjectionContext): NativeSnapshotWriter {
            NativeSnapshotAccess.prepare()
            val regionId = UUID.randomUUID().toString()
            val header = SnapshotLayout.header(context, regionId)
            val memory = SharedMemory.create("EyeProjector-usability", header.size + 8)
            var mapping: ByteBuffer? = null
            try {
                val buffer = memory.mapReadWrite(); mapping = buffer
                buffer.put(header)
                val address = NativeSnapshotAccess.initializeCell(buffer, header.size)
                check(memory.setProtect(OsConstants.PROT_READ))
                return NativeSnapshotWriter(context, regionId, memory, buffer, address, header.size)
            } catch (error: Throwable) {
                try { mapping?.let(SharedMemory::unmap) } finally { memory.close() }
                throw error
            }
        }
    }
}

/**
 * Sole reader lane owns install/read/clear. No production callback lane is selected yet.
 * Setup/clear assert lane affinity; the intended fresh-key operation only loads one primitive cell.
 * Captured routes must carry context/regionId/word/proxy facts, never this address or ByteBuffer.
 */
internal class NativeSnapshotReadLane {
    private val lane = Thread.currentThread()
    private var mapping: ByteBuffer? = null
    private var address = 0L
    var context: ProjectionContext? = null; private set
    var regionId: String? = null; private set
    fun install(descriptor: NativeSnapshotDescriptor, expected: ProjectionContext) {
        var fresh: ByteBuffer? = null
        try {
            check(Thread.currentThread() === lane)
            NativeSnapshotAccess.prepare()
            require(descriptor.context == expected)
            val buffer = descriptor.memory.mapReadOnly(); fresh = buffer
            SnapshotLayout.validate(buffer, descriptor)
            val captured = NativeSnapshotAccess.bindCell(buffer, descriptor.cellOffset)
            clear()
            mapping = buffer; fresh = null; address = captured
            context = descriptor.context; regionId = descriptor.regionId
        } finally {
            try { fresh?.let(SharedMemory::unmap) } finally { descriptor.close() }
        }
    }
    /** Only call on the installation lane. No other thread may clear/unmap while this executes. */
    fun read(): Long = if (address == 0L) SnapshotWord.RETIRED.toLong() else NativeSnapshotAccess.acquireCell(address)
    fun clear() {
        check(Thread.currentThread() === lane)
        address = 0L; context = null; regionId = null
        val old = mapping; mapping = null
        old?.let(SharedMemory::unmap)
    }
}
