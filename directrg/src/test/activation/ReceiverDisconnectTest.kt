package com.code2hack.eyeprojector.directrg

import android.media.MediaCodec
import com.code2hack.eyeprojector.protocol.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Exact Activity callers composed with actual retained owner/client/session, no Android device. */
class ReceiverDisconnectTest {
    private val retained = RetainedReceiverSessionTest()
    @get:Rule val temporary = retained.temporary

    @Test fun explicitUiAndCapturedRecorderRetireActualDirectSessionWithCurrentOrCanceledTrigger() {
        for (recorder in listOf(false, true)) for (canceled in listOf(false, true)) {
            retained.Fixture(directFeature = true).use { f ->
                f.streaming()
                val scope = ReceiverLaunchScope("ui-scope", "ui-T", 7)
                f.feature!!.launch(scope, "ui-admission")
                if (canceled) {
                    f.feature.cancel(scope, null)
                    f.feature.accept(ActivationReply(trigger = TriggerReceipt(scope.trigger, null, TriggerDisposition.CANCELED)))
                }
                f.transport = { request -> SessionState("retired", SessionPhase.INACTIVE,
                    activation = request.cancelTrigger?.let {
                        ActivationReply(trigger = TriggerReceipt(it.triggerId, null, TriggerDisposition.CANCELED))
                    }) }
                val source = ReceiverDisconnectSource(f.owner, f.feature, scope)
                val original = f.owner.session!!
                val video = f.output
                if (recorder) {
                    val target = source.captureFixtureTeardownTarget(original.observationStartedMonoNs)!!
                    assertTrue(target.observe().ownerSessionMatchesOriginal)
                    var complete = 0
                    target.disconnect { complete++ }
                    f.pumpUntil("recorder completion") { complete == 1 }
                    assertFalse(target.observe().ownerSessionPresent)
                } else source.disconnect()
                assertNull(f.owner.session); assertEquals(1, f.codec.releases.get())
                assertTrue(video.surface.texture!!.released); assertFalse(f.owner.hasRetainedResources())
                f.pumpUntil("original peer Stop submitted") {
                    f.commands.any { it.operation == SessionControl.Operation.STOP && it.epoch == "retained-phone-owner" }
                }
            }
        }
    }

    @Test fun originalFixtureAndLateFinishingDestructorCannotSelectReplacementSession() {
        retained.Fixture(directFeature = true).use { f ->
            f.streaming()
            val scope = ReceiverLaunchScope("old-scope", "old-T", 7)
            f.feature!!.launch(scope, "old-admission")
            val source = ReceiverDisconnectSource(f.owner, f.feature, scope)
            val original = f.owner.session!!
            val target = source.captureFixtureTeardownTarget(original.observationStartedMonoNs)!!
            val nextCodec = MediaCodec(); MediaCodec.created = nextCodec
            f.observation(SessionState("S2-owner", SessionPhase.AVAILABLE))
            val next = f.owner.session!!
            target.disconnect {}
            source.onDestroy(); f.pump()
            assertSame(next, f.owner.session); assertEquals(0, nextCodec.releases.get())
            assertFalse(target.observe().ownerSessionMatchesOriginal)
            assertTrue(f.commands.none { it.operation == SessionControl.Operation.STOP })
        }
    }

    @Test fun replacementDuringExistingUiCancellationWaitCannotBeReleasedByOriginalDisconnectOrFinish() {
        for (finish in listOf(false, true)) retained.Fixture(directFeature = true).use { f ->
            f.streaming()
            val scope = ReceiverLaunchScope("old-scope", "old-T", 7)
            f.feature!!.launch(scope, "old-admission")
            val source = ReceiverDisconnectSource(f.owner, f.feature, scope)
            val nextCodec = MediaCodec()
            source.beforeCancel = {
                MediaCodec.created = nextCodec
                f.observation(SessionState("S2-owner", SessionPhase.AVAILABLE))
            }
            if (finish) source.finishForTest() else source.disconnect()
            val next = f.owner.session!!; f.pump()
            assertSame(next, f.owner.session); assertEquals("S2-owner", f.owner.snapshot().epoch)
            assertEquals(0, nextCodec.releases.get())
            assertTrue(f.commands.none { it.operation == SessionControl.Operation.STOP })
        }
    }

    @Test fun finishingCurrentDirectViewRetiresItsOriginalButTemporaryDestructionRetainsIt() {
        for (finishing in listOf(false, true)) retained.Fixture(directFeature = true).use { f ->
            f.streaming()
            val source = ReceiverDisconnectSource(f.owner, f.feature!!, ReceiverLaunchScope("scope", "T", 7))
            val original = f.owner.session
            source.isFinishing = finishing; source.onDestroy(); f.pump()
            if (finishing) {
                assertNull(f.owner.session); assertEquals(1, f.codec.releases.get())
            } else { assertSame(original, f.owner.session); assertEquals(0, f.codec.releases.get()) }
        }
    }
}
