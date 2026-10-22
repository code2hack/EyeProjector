package com.code2hack.eyeprojector.protocol

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import kotlin.math.min

data class Geometry(val width: Int, val height: Int) {
    init { require(width in 1..4096 && height in 1..4096) }
    fun bytes(): ByteArray = bytes { writeInt(width); writeInt(height) }
}

data class Tap(val x: Int, val y: Int, val geometry: Geometry) {
    init { require(x in 0 until geometry.width && y in 0 until geometry.height) }
    fun bytes(): ByteArray = bytes { writeInt(x); writeInt(y); writeInt(geometry.width); writeInt(geometry.height) }
    companion object {
        fun from(payload: ByteArray): Tap {
            require(payload.size == 16)
            val input = DataInputStream(ByteArrayInputStream(payload))
            val x = input.readInt(); val y = input.readInt()
            return Tap(x, y, Geometry(input.readInt(), input.readInt()))
        }
    }
}

/** Native wheel input at a visible target; positive pixels move content toward the top. */
data class Scroll(val target: Tap, val pixels: Int) {
    init { require(pixels in -1200..1200 && pixels != 0) }
    fun bytes(): ByteArray = target.bytes() + bytes { writeInt(pixels) }
    companion object {
        fun from(payload: ByteArray): Scroll {
            require(payload.size == 20)
            return Scroll(Tap.from(payload.copyOfRange(0, 16)), DataInputStream(payload.inputStream()).run { skipBytes(16); readInt() })
        }
    }
}

enum class Navigation(val keycode: Int) { BACK(4), HOME(3) }

data class Navigate(val action: Navigation, val geometry: Geometry) {
    fun bytes(): ByteArray = geometry.bytes() + bytes { writeInt(action.keycode) }
    companion object {
        fun from(payload: ByteArray): Navigate {
            require(payload.size == 12)
            val input = DataInputStream(payload.inputStream())
            val geometry = Geometry(input.readInt(), input.readInt())
            val key = input.readInt()
            return Navigate(Navigation.entries.single { it.keycode == key }, geometry)
        }
    }
}

data class Fit(val left: Int, val top: Int, val width: Int, val height: Int) {
    fun touch(x: Float, y: Float, geometry: Geometry): Tap? {
        if (!x.isFinite() || !y.isFinite() || x < left || y < top || x >= left + width || y >= top + height) return null
        return Tap(((x - left) * geometry.width / width).toInt(), ((y - top) * geometry.height / height).toInt(), geometry)
    }
    companion object {
        fun inside(width: Int, height: Int, geometry: Geometry): Fit {
            require(width > 0 && height > 0)
            val scale = min(width.toDouble() / geometry.width, height.toDouble() / geometry.height)
            val w = (geometry.width * scale).toInt().coerceAtLeast(1)
            val h = (geometry.height * scale).toInt().coerceAtLeast(1)
            return Fit((width - w) / 2, (height - h) / 2, w, h)
        }
    }
}

fun bytes(block: DataOutputStream.() -> Unit): ByteArray {
    val buffer = ByteArrayOutputStream()
    DataOutputStream(buffer).use(block)
    return buffer.toByteArray()
}

data class Frame(val channel: Int, val stamp: Long = 0, val payload: ByteArray = byteArrayOf())

object Wire {
    const val MAGIC = 0x50415631
    const val VERSION = 5
    const val SCRCPY_VERSION = "5.0.1"
    const val H264 = 0x68323634
    const val MAX_PAYLOAD = 1024 * 1024
    const val GEOMETRY = 0
    const val VIDEO = 1
    // Channel 2 (forwarded audio) is retired.
    const val TAP = 3
    const val PING = 4
    const val PONG = 5
    const val END = 6
    // Channel 7 (forwarded audio codec) is retired.
    const val SCROLL = 8
    const val NAVIGATE = 9
    const val SCALE_REQUEST = 10
    const val SCALE_STATE = 11
    const val TOUCH = 14
    const val HEAD_POINTER = 15 // Receiver -> Phone: source position/context, no HID.
    const val RECEIVER_INPUT = 16 // Phone -> Receiver: completed local UI operation.
    const val STREAM = 1
    const val STOP_HOST = 2

    fun writeRequest(output: DataOutputStream, operation: Int) {
        require(operation == STREAM || operation == STOP_HOST)
        output.writeInt(MAGIC); output.writeInt(VERSION); output.writeByte(operation); output.flush()
    }
    fun readRequest(input: DataInputStream): Int {
        require(input.readInt() == MAGIC && input.readInt() == VERSION)
        return input.readUnsignedByte().also { require(it == STREAM || it == STOP_HOST) }
    }
    fun writeWelcome(output: DataOutputStream) {
        output.writeInt(MAGIC); output.writeInt(VERSION); output.writeUTF(SCRCPY_VERSION)
        output.writeInt(H264); output.flush()
    }
    fun readWelcome(input: DataInputStream) {
        require(input.readInt() == MAGIC && input.readInt() == VERSION)
        require(input.readUTF() == SCRCPY_VERSION && input.readInt() == H264)
    }
    private fun validate(channel: Int, length: Int) {
        require(channel in GEOMETRY..RECEIVER_INPUT && length in 0..MAX_PAYLOAD)
        when (channel) {
            GEOMETRY -> require(length == 8)
            TAP -> require(length == 16)
            TOUCH -> require(length == Touch.PAYLOAD_SIZE)
            HEAD_POINTER -> require(length == HeadPointerState.MIN_PAYLOAD_SIZE || length == HeadPointerState.MAX_PAYLOAD_SIZE)
            RECEIVER_INPUT -> require(length == ReceiverInput.PAYLOAD_SIZE)
            SCROLL -> require(length == 20)
            NAVIGATE -> require(length == 12)
            SCALE_REQUEST -> require(length == 8)
            SCALE_STATE -> require(length == 40)
            VIDEO -> require(length > 0)
            PING, PONG, END -> require(length == 0)
            else -> error("Unsupported channel")
        }
    }
    fun readFrame(input: DataInputStream): Frame {
        val channel = input.readUnsignedByte(); val stamp = input.readLong(); val size = input.readInt()
        validate(channel, size)
        return Frame(channel, stamp, ByteArray(size).also(input::readFully))
    }
    fun writeFrame(output: DataOutputStream, frame: Frame) {
        validate(frame.channel, frame.payload.size)
        output.writeByte(frame.channel); output.writeLong(frame.stamp); output.writeInt(frame.payload.size)
        output.write(frame.payload); output.flush()
    }
}

sealed interface ScrcpyRecord {
    data class Session(val geometry: Geometry, val clientResize: Boolean) : ScrcpyRecord
    data class Packet(val stamp: Long, val data: ByteArray) : ScrcpyRecord {
        val config get() = stamp and Scrcpy.CONFIG != 0L
        val keyFrame get() = stamp and Scrcpy.KEY_FRAME != 0L
        val ptsUs get() = stamp and (Scrcpy.KEY_FRAME - 1)
    }
}

/** Exact upstream 5.0.1 Streamer/ControlMessageReader format, not the older 12-byte stream header. */
object Scrcpy {
    const val CONFIG = 1L shl 62
    const val KEY_FRAME = 1L shl 61
    /** Upstream 5.0.1 TYPE_SET_DISPLAY_POWER: physical displays for new-display capture. */
    fun displayPower(on: Boolean): ByteArray = byteArrayOf(10, if (on) 1 else 0)
    /** Upstream 5.0.1 TYPE_RESIZE_DISPLAY: virtual-display bounds, with DPI retained. */
    fun resize(geometry: Geometry): ByteArray = bytes {
        writeByte(21); writeShort(geometry.width); writeShort(geometry.height)
    }
    fun readRecord(input: DataInputStream): ScrcpyRecord {
        val stamp = input.readLong(); val last = input.readInt()
        if (stamp < 0) {
            val flags = (stamp ushr 32).toInt()
            require(flags == Int.MIN_VALUE || flags == (Int.MIN_VALUE or 1))
            return ScrcpyRecord.Session(Geometry(stamp.toInt(), last), flags and 1 != 0)
        }
        require(last in 1..Wire.MAX_PAYLOAD)
        return ScrcpyRecord.Packet(stamp, ByteArray(last).also(input::readFully))
    }
    fun tap(tap: Tap): ByteArray = bytes {
        for (action in listOf(0, 1)) {
            writeByte(2); writeByte(action); writeLong(-2)
            writeInt(tap.x); writeInt(tap.y); writeShort(tap.geometry.width); writeShort(tap.geometry.height)
            writeShort(if (action == 0) 0xffff else 0); writeInt(0); writeInt(0)
        }
    }
    fun scroll(scroll: Scroll): ByteArray = bytes {
        val target = scroll.target
        writeByte(3)
        writeInt(target.x); writeInt(target.y)
        writeShort(target.geometry.width); writeShort(target.geometry.height)
        writeShort(0)
        // Android apps apply their own scroll factor. 64 nominal pixels = one wheel detent.
        writeShort((-scroll.pixels.toDouble() / 64 / 16 * 32768).toInt().coerceIn(-32768, 32767))
        writeInt(0)
    }
    fun navigate(navigate: Navigate): ByteArray = bytes {
        for (action in listOf(0, 1)) {
            writeByte(0); writeByte(action); writeInt(navigate.action.keycode); writeInt(0); writeInt(0)
        }
    }
}
