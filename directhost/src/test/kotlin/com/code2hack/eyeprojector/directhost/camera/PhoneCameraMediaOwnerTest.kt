package com.code2hack.eyeprojector.directhost.camera

import com.code2hack.eyeprojector.protocol.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class PhoneCameraMediaOwnerTest {
    private class UnusedPlatform : Qpr2CameraPlatform {
        val calls = AtomicInteger()
        override fun caller(): PhoneCameraCaller { calls.incrementAndGet(); error("No native invocation expected") }
        override fun lookup(): PhoneCameraServiceLookup { calls.incrementAndGet(); error("No native invocation expected") }
        override fun newToken(): android.os.IBinder { calls.incrementAndGet(); error("No token expected before start") }
    }
    private fun owner(session: Any, platform: UnusedPlatform, observer: (PhoneCameraMediaSnapshot) -> Unit = {}) =
        PhoneCameraMediaOwner(session, PhoneCameraMediaRole("test", {}), Qpr2VirtualCameraOwner(platform),
            PhoneCameraProfile(listOf(RequestedPhoneStream(640, 480, 1, 30)), 2, 0, 0),
            CameraStreamRequest(640, 480, 1, "video/avc"), observer)

    @Test fun constructionDoesNotCreateTokenOrPublishDemand() {
        val session = Any(); val native = UnusedPlatform(); val original = owner(session, native)
        val before = original.snapshot()
        assertSame(session, before.originalSession)
        assertFalse(before.startSubmitted)
        assertNull(before.registration)
        assertTrue(before.groups.isEmpty())
        assertFalse(before.worker.startRequested)
        assertFalse(before.statusWorker.startRequested)
        assertEquals(0, native.calls.get())
        original.requestClose(CameraEndReason.PROJECTION_CLOSED)
    }

    @Test fun closeBeforeStartCannotRegisterAndCloseObservationRetainsOriginal() {
        val session = Any(); val native = UnusedPlatform(); val original = owner(session, native)
        assertEquals(CameraFact.PENDING, original.requestClose(CameraEndReason.PROJECTION_CLOSED).fact)
        assertEquals(CameraFact.RETIRED, original.startRegistration().fact)
        assertEquals(CameraFact.STALE_OR_DUPLICATE, original.requestClose(CameraEndReason.PROJECTION_CLOSED).fact)
        val returned = CountDownLatch(1)
        original.closeAsync(Executor { it.run() }) {
            assertSame(session, it.snapshot.originalSession)
            assertTrue(it.snapshot.closeRequested)
            assertNull(it.snapshot.registration)
            assertEquals("UNOBSERVED", it.snapshot.callbackQuiescence)
            assertEquals("UNKNOWN", it.snapshot.fullH)
            returned.countDown()
        }
        assertTrue(returned.await(2, TimeUnit.SECONDS))
        assertEquals(0, native.calls.get())
    }

    @Test fun failingStatusObserverCannotReopenClosedNativeAdmission() {
        val native = UnusedPlatform(); val observed = CountDownLatch(1)
        val original = owner(Any(), native) { observed.countDown(); error("status sink failure") }
        original.requestClose(CameraEndReason.PROJECTION_CLOSED)
        assertTrue(observed.await(2, TimeUnit.SECONDS))
        assertEquals(CameraFact.RETIRED, original.startRegistration().fact)
        assertEquals(0, native.calls.get())
        // Observer failure is diagnostic; no test substitutes it for a Surface fence or actual join.
    }
}
