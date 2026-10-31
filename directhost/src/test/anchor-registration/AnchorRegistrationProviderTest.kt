package com.code2hack.eyeprojector.directhost.anchor.registration

import com.code2hack.eyeprojector.directhost.ProjectionContext
import com.code2hack.eyeprojector.directhost.anchor.execution.*
import com.code2hack.eyeprojector.directhost.anchor.preferences.*
import com.code2hack.eyeprojector.directhost.anchor.target.AnchorTargetDescriptor
import com.code2hack.eyeprojector.directhost.uiaccess.*
import com.code2hack.eyeprojector.protocol.*
import org.junit.Assert.*
import org.junit.Test

/** Actual provider/reader/resolver/store; only the recorded #122 transport/FIFO ABI is modeled. */
class AnchorRegistrationProviderTest {
    private class Fixture {
        val app = "example.app"
        val size = Geometry(400, 400)
        val source = ManagedUiSource(ProjectionContext("owner", "session"), "display-generation", 34, "display",
            app, null, UiSourceGeometry(size, size, 320, 0, false, size))
        val fact = ReceiverUsability("owner", ReceiverIncarnation("receiver", 1), 3, 4, ReceiverUsabilityMode.PROJECTED)
        val intent = FunctionIntent(FunctionIntentKind.REGISTER, FunctionSource.GLASSES_NATIVE, 1, 1,
            FunctionIntentContext(fact, HeadPointerState(4, 2, true, true, 40f, 40f, Fit(0, 0, 400, 400), size)))
        val old = AppAnchor(AnchorTargetDescriptor(app, null, 1, "$app:id/old", null), AnchorMode.CLICK)
        var value: AppAnchor? = old
        var writes = 0
        var fail = false
        var windowId = 7
        var duringRead: () -> Unit = {}
        val store = AppAnchorStore(object : AppAnchorStorage {
            override fun read(packageName: String) = value
            override fun write(packageName: String, anchor: AppAnchor?) {
                if (fail) throw java.io.IOException("injected")
                writes++; value = anchor
            }
        })
        inner class Route(override val transactionId: Long = 1, override val intent: FunctionIntent = this@Fixture.intent,
            var current: ManagedUiSource? = source) : AnchorOriginalRoute {
            val messages = mutableListOf<AnchorMessage>()
            override fun source() = current
            override fun send(message: AnchorMessage) {
                if (message is AnchorMessage.Result && message.result in
                    listOf(AnchorRegistrationResult.SAVED_CLICK, AnchorRegistrationResult.SAVED_HOLD)) {
                    assertEquals("Saved feedback must follow the complete store commit", "$app:id/control", value!!.descriptor.resourceId)
                }
                messages.add(message)
            }
        }
        val route = Route()
        val reader = UiTargetReader(object : UiAccess {
            override val canRead = true
            override fun windowsOnAllDisplays(): Map<Int, List<UiAccessWindow>> = mapOf(34 to listOf(object : UiAccessWindow {
                override val metadata get() = UiWindowMetadata(34, windowId, 1, 2, true, true, UiBounds(0, 0, 400, 400))
                override fun root() = object : UiAccessNode {
                    override val packageName = app
                    override val windowId get() = this@Fixture.windowId
                    override val childCount = 0
                    override fun child(index: Int): UiAccessNode? = null
                    override fun observation(ordinal: Int): UiTargetObservation {
                        duringRead()
                        return UiTargetObservation(ordinal, windowId, app, "$app:id/control", "Button",
                            UiBounds(10, 20, 70, 60), emptyList(), true, false, true, true)
                    }
                    override fun close() = Unit
                }
                override fun close() = Unit
            }))
        }) { 1L }
        val runtime = AnchorAppRuntime(store) { reader }
        init { AnchorRegistrationProvider().install(runtime) }
        fun begin(original: Route = route) = runtime.begin(CapturedAnchorRequest(original, original.current!!,
            runtime.read(original, app)))
        fun open(original: Route = route, chooser: ReceiverUsability = fact.copy(revision = 5, inputRevision = 6,
            mode = ReceiverUsabilityMode.LOCAL_PANEL)) =
            runtime.message(AnchorMessage.Open(original.transactionId, original.intent, chooser), original)
        fun choose(choice: AnchorChoice, original: Route = route) =
            runtime.message(AnchorMessage.Choice(original.transactionId, original.intent, choice), original)
        fun last() = route.messages.last() as AnchorMessage.Result
    }

    @Test fun providerUsesSameStoreAndReaderAndSendsConfirmationOnlyAfterExplicitModeCommit() {
        for ((choice, mode, outcome) in listOf(Triple(AnchorChoice.CLICK, AnchorMode.CLICK, AnchorRegistrationResult.SAVED_CLICK),
            Triple(AnchorChoice.HOLD, AnchorMode.HOLD, AnchorRegistrationResult.SAVED_HOLD))) {
            val f = Fixture(); f.begin()
            assertEquals(f.old, f.value)
            assertTrue(f.route.messages.single() is AnchorMessage.Offer)
            f.open(); f.choose(choice)
            assertEquals(mode, f.value!!.mode)
            assertEquals(1, f.writes)
            assertEquals(outcome, f.last().result)
        }
    }

    @Test fun cancelAfterExternalReplacementDoesNotRestoreOldValue() {
        val f = Fixture(); f.begin(); f.open()
        val external = f.old.copy(mode = AnchorMode.HOLD)
        f.store.replace(external)
        f.choose(AnchorChoice.CANCEL)
        assertEquals(external, f.value)
        assertEquals(1, f.writes)
        assertEquals(AnchorRegistrationResult.CANCELED, f.last().result)
    }

    @Test fun sameNumericIdAndIntentFromSuccessorSessionCannotChooseOrCancelOriginal() {
        val f = Fixture(); f.begin(); f.open()
        val successor = f.Route(current = f.source.copy(context = ProjectionContext("owner", "successor")))
        f.choose(AnchorChoice.HOLD, successor)
        f.choose(AnchorChoice.CANCEL, successor)
        assertEquals(f.old, f.value)
        assertEquals(0, f.writes)
        f.choose(AnchorChoice.CLICK)
        assertEquals(1, f.writes)
        assertEquals(AnchorRegistrationResult.SAVED_CLICK, f.last().result)
    }

    @Test fun noChoiceIsSavedBeforeAnExplicitOriginalOpen() {
        val f = Fixture(); f.begin(); f.choose(AnchorChoice.HOLD)
        assertEquals(0, f.writes)
        assertEquals(f.old, f.value)
        assertEquals(AnchorRegistrationResult.CANCELED, f.last().result)
    }

    @Test fun duplicateBeginAndIdenticalOpenKeepTheOriginalPendingTransaction() {
        val f = Fixture(); f.begin(); f.open(); f.begin(); f.open(); f.choose(AnchorChoice.HOLD)
        assertEquals(1, f.route.messages.filterIsInstance<AnchorMessage.Offer>().size)
        assertEquals(1, f.writes)
        assertEquals(AnchorRegistrationResult.SAVED_HOLD, f.last().result)
    }

    @Test fun unrelatedChooserRevisionCannotReplaceTheAuthorizedTransition() {
        val f = Fixture(); f.begin(); f.open()
        f.open(chooser = f.fact.copy(revision = 6, inputRevision = 7, mode = ReceiverUsabilityMode.LOCAL_PANEL))
        f.choose(AnchorChoice.HOLD)
        assertEquals(0, f.writes)
        assertEquals(AnchorRegistrationResult.CANCELED, f.last().result)
    }

    @Test fun sourceWindowAndGeometryChangesDuringChoicePreservePreference() {
        for (change in listOf<(Fixture) -> Unit>(
            { it.route.current = null },
            { it.route.current = it.source.copy(displayGeneration = "new") },
            { it.route.current = it.source.copy(geometry = it.source.geometry.copy(rotation = 1)) },
            { it.windowId = 8 })) {
            val f = Fixture(); f.begin(); f.open(); change(f); f.choose(AnchorChoice.HOLD)
            assertEquals(f.old, f.value)
            assertEquals(0, f.writes)
        }
    }

    @Test fun sourceLossDuringActualReaderTraversalCannotSavePendingSelection() {
        val f = Fixture(); f.begin(); f.open()
        f.duringRead = { f.route.current = null }
        f.choose(AnchorChoice.HOLD)
        assertEquals(0, f.writes)
        assertEquals(f.old, f.value)
        assertEquals(AnchorRegistrationResult.UNAVAILABLE, f.last().result)
    }

    @Test fun failedAtomicSaveSendsUnavailableAndLeavesPreviousAnchor() {
        val f = Fixture(); f.begin(); f.open(); f.fail = true; f.choose(AnchorChoice.HOLD)
        assertEquals(0, f.writes)
        assertEquals(f.old, f.value)
        assertEquals(AnchorRegistrationResult.UNAVAILABLE, f.last().result)
    }
}
