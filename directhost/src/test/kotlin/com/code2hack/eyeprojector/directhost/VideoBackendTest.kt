package com.code2hack.eyeprojector.directhost

import com.code2hack.eyeprojector.protocol.*
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import org.junit.Assert.*
import org.junit.Test

class VideoBackendTest {
    @Test fun stockStartupRequestsOnlyVideoAndControlWithStreamMetadata() {
        val command = ShellMain.videoControlCommand(0x1234)
        assertEquals(listOf("/system/bin/app_process", "/", "com.genymobile.scrcpy.Server", "5.0.1"), command.take(4))
        val options = command.drop(4).associate { it.substringBefore('=') to it.substringAfter('=') }
        assertEquals("00001234", options["scid"])
        assertEquals("true", options["video"]); assertEquals("true", options["control"])
        assertEquals("false", options["audio"])
        assertEquals("h264", options["video_codec"])
        assertEquals("false", options["send_device_meta"]); assertEquals("false", options["send_dummy_byte"])
        assertEquals("false", options["tunnel_forward"])
        assertEquals("true", options["send_stream_meta"]); assertEquals("true", options["send_frame_meta"])
        assertEquals("480x640/160", options["new_display"]); assertEquals("true", options["flex_display"])
        assertEquals("local", options["display_ime_policy"])
        assertEquals("false", options["vd_system_decorations"])
        assertEquals("true", options["keep_active"])
        assertEquals("false", options["power_on"])
        assertEquals("true", options["cleanup"])
        assertFalse(options.containsKey("stay_awake"))

        assertFalse(options.keys.any { it.startsWith("audio_") || it == "send_codec_meta" })
    }

    @Test fun pinnedStockVideoHeaderFlowsIntoReceiverWelcomeWithoutAnAudioHeader() {
        val stock = ByteArrayOutputStream()
        DataOutputStream(stock).use {
            it.writeInt(Wire.H264)
            it.writeInt(Int.MIN_VALUE); it.writeInt(480); it.writeInt(640)
            it.writeLong(Scrcpy.CONFIG); it.writeInt(3); it.write(byteArrayOf(0, 0, 1))
            it.writeLong(Scrcpy.KEY_FRAME or 9_000_000_000); it.writeInt(2); it.write(byteArrayOf(1, 2))
        }
        val input = DataInputStream(stock.toByteArray().inputStream())
        assertEquals(Wire.H264, input.readInt())
        val transport = ByteArrayOutputStream()
        DataOutputStream(transport).use { out ->
            Wire.writeWelcome(out)
            while (input.available() > 0) when (val record = Scrcpy.readRecord(input)) {
                is ScrcpyRecord.Session -> Wire.writeFrame(out, Frame(Wire.GEOMETRY, payload = record.geometry.bytes()))
                is ScrcpyRecord.Packet -> Wire.writeFrame(out, Frame(Wire.VIDEO, record.stamp, record.data))
            }
        }
        val receiver = DataInputStream(transport.toByteArray().inputStream())
        Wire.readWelcome(receiver)
        assertEquals(Wire.GEOMETRY, Wire.readFrame(receiver).channel)
        assertTrue(ScrcpyRecord.Packet(Wire.readFrame(receiver).stamp, byteArrayOf()).config)
        val video = Wire.readFrame(receiver)
        assertEquals(Wire.VIDEO, video.channel)
        assertEquals(9_000_000_000, ScrcpyRecord.Packet(video.stamp, video.payload).ptsUs)
        assertEquals(-1, receiver.read())
    }
}
