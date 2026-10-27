package com.code2hack.eyeprojector.directrg

import android.view.Surface
import com.code2hack.eyeprojector.protocol.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.*

class CameraProjectionDispatchTest {
    private val ref = CameraUseRef("captured-phone", "original-session", 0)
    private val request = CameraStreamRequest(640, 480, 35, "video/avc")
    @Test fun actualProjectionReaderUsesCapturedEpochAndDispatchesCallbacksOffReader() {
        val directory = Files.createTempDirectory("camera-projection-").toFile()
        val release = CountDownLatch(1); val entered = CountDownLatch(1)
        val session = ProjectionSession(directory, Surface(), { _, _ -> }, { _, _ -> }, expectedPhoneOwnerEpoch = "captured-phone")
        try {
            session.cameraUses.setUseObserver { entered.countDown(); release.await() }
            val reader = Executors.newSingleThreadExecutor()
            try {
                reader.submit { session.receive(Frame(Wire.CAMERA_USE, payload = CameraFeed.useBytes(CameraUseMessage.Prepare(ref, request)))) }.get(1, TimeUnit.SECONDS)
                assertTrue(entered.await(2, TimeUnit.SECONDS))
                val original = session.cameraUses.receivedUses().single()
                assertTrue(original.live)
                session.receive(Frame(Wire.CAMERA_USE, payload = CameraFeed.useBytes(CameraUseMessage.Prepare(ref.copy(ownerEpoch = "current-successor", useGeneration = 1), request))))
                assertEquals(1, session.cameraUses.receivedUses().size)
                session.receive(Frame(Wire.CAMERA_PROGRESS, payload = CameraFeed.progressBytes(CameraProgress(ref, null, CameraStage.PERMISSION, CameraFact.PENDING))))
                assertEquals(CameraFact.PENDING, session.cameraUses.remoteProgress(original)!!.fact)
                session.receive(Frame(Wire.CAMERA_USE, payload = CameraFeed.useBytes(CameraUseMessage.Close(ref, CameraEndReason.ORIGINAL_INPUT_CLOSED))))
                assertFalse(original.live)
                session.receive(Frame(Wire.CAMERA_USE, payload = CameraFeed.useBytes(CameraUseMessage.Prepare(ref, request))))
                assertEquals(1, session.cameraUses.receivedUses().size)
            } finally { reader.shutdownNow() }
        } finally { release.countDown(); session.close(); directory.deleteRecursively() }
    }
    @Test fun projectionRejectsCameraSocketDirectionsAndEndRetiresExactHandles() {
        val directory = Files.createTempDirectory("camera-direction-").toFile()
        val session = ProjectionSession(directory, Surface(), { _, _ -> }, { _, _ -> }, expectedPhoneOwnerEpoch = "captured-phone")
        try {
            val prepare = CameraFeed.useBytes(CameraUseMessage.Prepare(ref, request))
            assertThrows(Exception::class.java) { session.receive(Frame(Wire.CAMERA_USE, 1, prepare)) }
            assertThrows(Exception::class.java) { session.receive(Frame(Wire.CAMERA_USE, payload = prepare + byteArrayOf(0))) }
            for (channel in listOf(Wire.CAMERA_STREAM, Wire.CAMERA_MEDIA, Wire.CAMERA_END))
                assertThrows(Exception::class.java) { session.receive(Frame(channel)) }
            session.receive(Frame(Wire.CAMERA_USE, payload = prepare))
            val original = session.cameraUses.receivedUses().single(); assertTrue(original.live)
            session.receive(Frame(Wire.END)); assertFalse(original.live)
        } finally { session.close(); directory.deleteRecursively() }
    }
}
