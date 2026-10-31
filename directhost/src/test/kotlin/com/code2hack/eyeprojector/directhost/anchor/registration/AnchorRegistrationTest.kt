package com.code2hack.eyeprojector.directhost.anchor.registration

import com.code2hack.eyeprojector.directhost.ProjectionContext
import com.code2hack.eyeprojector.directhost.anchor.preferences.*
import com.code2hack.eyeprojector.directhost.anchor.target.AnchorTargetDescriptor
import com.code2hack.eyeprojector.directhost.uiaccess.*
import com.code2hack.eyeprojector.protocol.*
import org.junit.Assert.*
import org.junit.Test

class AnchorRegistrationTest {
    private val app = "example.app"
    private val size = Geometry(500, 400)
    private val source = ManagedUiSource(ProjectionContext("owner", "session"), "display-generation", 34, "display",
        app, "example.app/.Screen", UiSourceGeometry(Geometry(400, 400), Geometry(1000, 800), 320, 0, false, size))
    private val bounds = UiBounds(10, 20, 70, 60)
    private val target = UiTargetObservation(1, 7, app, "$app:id/control", "Button", bounds,
        listOf(16), true, false, true, true)
    private val window = UiWindowMetadata(34, 7, 1, 2, true, true, UiBounds(0, 0, 400, 400))
    private val fact = ReceiverUsability("owner", ReceiverIncarnation("receiver", 2), 3, 4, ReceiverUsabilityMode.PROJECTED)
    private fun intent(current: ManagedUiSource = source, selection: UiBounds = bounds): FunctionIntent {
        val video = current.geometry.sourceToVideo(UiPoint((selection.left + selection.right) / 2.0,
            (selection.top + selection.bottom) / 2.0))
        val fit = Fit(73, 31, 1000, 800)
        return FunctionIntent(FunctionIntentKind.REGISTER, FunctionSource.GLASSES_NATIVE, 1, 1,
            FunctionIntentContext(fact, HeadPointerState(4, 9, true, true,
                (fit.left + video.x * fit.width / current.geometry.video.width).toFloat(),
                (fit.top + video.y * fit.height / current.geometry.video.height).toFloat(), fit, current.geometry.video)))
    }
    private fun snapshot(current: ManagedUiSource = source, targets: List<UiTargetObservation> = listOf(target),
        metadata: UiWindowMetadata = window, status: UiTargetReadStatus = UiTargetReadStatus.READ) =
        UiTargetSnapshot(UiTargetRequest(current, app), status, 1, 2,
            listOf(UiWindowSnapshot(metadata, UiWindowReadStatus.READ, targets)))
    private fun selected(i: FunctionIntent = intent(), s: UiTargetSnapshot = snapshot()) =
        (AnchorRegistrationTargets.capture(i, s) as AnchorRegistrationCapture.Selected).target
    private val old = AppAnchor(AnchorTargetDescriptor(app, null, 1, "$app:id/previous", null), AnchorMode.CLICK)
    private class Storage(var value: AppAnchor?) : AppAnchorStorage {
        var writes = 0
        var fail = false
        override fun read(packageName: String) = value
        override fun write(packageName: String, anchor: AppAnchor?) {
            if (fail) throw java.io.IOException("injected atomic write failure")
            writes++; value = anchor
        }
    }

    @Test fun originalReceiverFitMapsThroughVideoPhysicalLetterboxAndLogicalSource() {
        val captured = selected()
        assertEquals(target, captured.current.target)
        assertEquals(UiRectangle(60.0, 20.0, 120.0, 60.0), captured.highlight)
        assertEquals("$app:id/control", captured.descriptor.resourceId)
        assertEquals("example.app/.Screen", captured.descriptor.activityName)
    }

    @Test fun originalHoverMappingHandlesEveryRotationAndDensityWithoutAddingWindowInsets() {
        for (rotation in 0..3) for (density in listOf(160, 320, 416)) {
            val current = source.copy(geometry = source.geometry.copy(rotation = rotation, densityDpi = density))
            assertEquals(target, selected(intent(current), snapshot(current)).current.target)
        }
    }

    @Test fun originalHoverIsUsedRatherThanAnotherControlOrItsLaterPosition() {
        val elsewhere = target.copy(ordinal = 2, resourceId = "$app:id/other", bounds = UiBounds(100, 100, 200, 200))
        assertEquals(target, selected(s = snapshot(targets = listOf(elsewhere, target))).current.target)
        assertEquals(AnchorRegistrationCapture.Rejected(AnchorRegistrationFailure.HOVER_ABSENT),
            AnchorRegistrationTargets.capture(intent(), snapshot(targets = listOf(elsewhere))))
    }

    @Test fun hoverOutsideOriginalFitDoesNotClampToAnAppControl() {
        val original = intent()
        val pointer = original.context.pointer.copy(x = 0f)
        assertEquals(AnchorRegistrationCapture.Rejected(AnchorRegistrationFailure.HOVER_ABSENT),
            AnchorRegistrationTargets.capture(original.copy(context = original.context.copy(pointer = pointer)), snapshot()))
    }

    @Test fun overlappingTargetsAndRepeatedStaticIdentityCannotSelectAnUnintendedControl() {
        val overlap = target.copy(ordinal = 2, resourceId = "$app:id/overlap")
        assertEquals(AnchorRegistrationCapture.Rejected(AnchorRegistrationFailure.HOVER_AMBIGUOUS),
            AnchorRegistrationTargets.capture(intent(), snapshot(targets = listOf(target, overlap))))
        val repeated = target.copy(ordinal = 2, bounds = UiBounds(100, 100, 200, 200))
        assertEquals(AnchorRegistrationCapture.Rejected(AnchorRegistrationFailure.TARGET_UNAVAILABLE),
            AnchorRegistrationTargets.capture(intent(), snapshot(targets = listOf(target, repeated))))
    }

    @Test fun noDefaultDisplayMissingIdentityOrUnavailableReaderFallback() {
        for (s in listOf(snapshot(metadata = window.copy(displayId = 0)),
            snapshot(current = source.copy(foregroundPackage = "other.app")),
            snapshot(targets = listOf(target.copy(resourceId = null))), snapshot(status = UiTargetReadStatus.ACCESS_REQUIRED))) {
            assertTrue(AnchorRegistrationTargets.capture(intent(), s) is AnchorRegistrationCapture.Rejected)
        }
    }

    @Test fun wrongOriginalGeometryOwnerInputRevisionModeOrIntentIsRejected() {
        val i = intent()
        for (changed in listOf(i.copy(kind = FunctionIntentKind.ACTIVATE),
            i.copy(context = i.context.copy(usability = fact.copy(ownerEpoch = "replacement"))),
            i.copy(context = i.context.copy(usability = fact.copy(inputRevision = 5))),
            i.copy(context = i.context.copy(usability = fact.copy(mode = ReceiverUsabilityMode.LOCAL_PANEL))),
            i.copy(context = i.context.copy(pointer = i.context.pointer.copy(geometry = Geometry(700, 700)))))) {
            assertEquals(AnchorRegistrationCapture.Rejected(AnchorRegistrationFailure.CONTEXT_CHANGED),
                AnchorRegistrationTargets.capture(changed, snapshot()))
        }
    }

    @Test fun beginAndCancelNeverWriteAndDoNotRestoreOverAnExternalPreferenceChange() {
        val storage = Storage(old)
        val store = AppAnchorStore(storage)
        val coordinator = AnchorRegistrationCoordinator<String>(store)
        assertTrue(coordinator.begin("original", intent(), snapshot()) is AnchorRegistrationCapture.Selected)
        assertEquals(0, storage.writes)
        val external = old.copy(mode = AnchorMode.HOLD)
        store.replace(external)
        assertTrue(coordinator.cancel("original"))
        assertEquals(external, store.read(app))
        assertEquals(1, storage.writes)
        assertEquals(AnchorRegistrationCompletion.Canceled, coordinator.choose("original", AnchorMode.CLICK, snapshot()))
        assertEquals(1, storage.writes)
    }

    @Test fun bothExplicitModesAreSavedRegardlessOfAdvertisedActionsAndNeverInferredOnBegin() {
        for (mode in AnchorMode.entries) for (actions in listOf(emptyList(), listOf(16), listOf(32), listOf(16, 32))) {
            val storage = Storage(old)
            val store = AppAnchorStore(storage)
            var events = 0
            store.observe(app) { events++ }
            val coordinator = AnchorRegistrationCoordinator<Long>(store)
            val s = snapshot(targets = listOf(target.copy(actionIds = actions)))
            coordinator.begin(1L, intent(), s)
            assertEquals(old, storage.value)
            assertEquals(0, events)
            val outcome = coordinator.choose(1L, mode, s) as AnchorRegistrationCompletion.Saved
            assertEquals(mode, outcome.anchor.mode)
            assertEquals(selected().descriptor, outcome.anchor.descriptor)
            assertEquals(outcome.anchor, store.read(app))
            assertEquals(1, events)
            assertEquals(1, storage.writes)
            assertEquals(AnchorRegistrationCompletion.Canceled, coordinator.choose(1L, mode, s))
        }
    }

    @Test fun supersededTransactionCannotCancelOrSaveTheSuccessor() {
        val storage = Storage(old)
        val coordinator = AnchorRegistrationCoordinator<String>(AppAnchorStore(storage))
        coordinator.begin("old", intent(), snapshot())
        coordinator.begin("new", intent(), snapshot())
        assertFalse(coordinator.cancel("old"))
        assertEquals(AnchorRegistrationCompletion.Canceled, coordinator.choose("old", AnchorMode.HOLD, snapshot()))
        assertEquals(0, storage.writes)
        assertTrue(coordinator.choose("new", AnchorMode.CLICK, snapshot()) is AnchorRegistrationCompletion.Saved)
    }

    @Test fun genuineSourceActivityWindowAndGeometryReplacementKeepPreviousPreference() {
        for (s in listOf(snapshot(current = source.copy(context = ProjectionContext("owner", "successor"))),
            snapshot(current = source.copy(displayGeneration = "successor")),
            snapshot(current = source.copy(foregroundActivity = "example.app/.Other")),
            snapshot(current = source.copy(geometry = source.geometry.copy(rotation = 1))),
            snapshot(metadata = window.copy(windowId = 8), targets = listOf(target.copy(windowId = 8))),
            snapshot(status = UiTargetReadStatus.SOURCE_CHANGED))) {
            val storage = Storage(old)
            val coordinator = AnchorRegistrationCoordinator<Long>(AppAnchorStore(storage))
            coordinator.begin(1L, intent(), snapshot())
            assertEquals(AnchorRegistrationCompletion.ContextChanged, coordinator.choose(1L, AnchorMode.HOLD, s))
            assertEquals(old, storage.value)
            assertEquals(0, storage.writes)
        }
    }

    @Test fun atomicWriteFailureRetainsPreviousValueAndPublishesNoCompletionEvent() {
        val storage = Storage(old)
        val store = AppAnchorStore(storage)
        var events = 0
        store.observe(app) { events++ }
        val coordinator = AnchorRegistrationCoordinator<Long>(store)
        coordinator.begin(1L, intent(), snapshot())
        storage.fail = true
        assertEquals(AnchorRegistrationCompletion.SaveFailed, coordinator.choose(1L, AnchorMode.HOLD, snapshot()))
        assertEquals(old, storage.value)
        assertEquals(0, events)
    }

    @Test fun failedRegistrationReplacesNoPreference() {
        val storage = Storage(old)
        val coordinator = AnchorRegistrationCoordinator<Long>(AppAnchorStore(storage))
        assertTrue(coordinator.begin(1L, intent(), snapshot(targets = emptyList())) is AnchorRegistrationCapture.Rejected)
        assertEquals(AnchorRegistrationCompletion.Canceled, coordinator.choose(1L, AnchorMode.HOLD, snapshot()))
        assertEquals(old, storage.value)
        assertEquals(0, storage.writes)
    }

    @Test fun actualReaderProviderResolverAndStoreComposeWithoutRetainingLiveNodes() {
        var allocated = 0
        var closed = 0
        var duringRead: () -> Unit = {}
        var actualSource = source
        val reader = UiTargetReader(object : UiAccess {
            override val canRead = true
            override fun windowsOnAllDisplays(): Map<Int, List<UiAccessWindow>> {
                allocated += 2
                val node = object : UiAccessNode {
                    override val packageName = app
                    override val windowId = 7
                    override val childCount = 0
                    override fun child(index: Int): UiAccessNode? = null
                    override fun observation(ordinal: Int): UiTargetObservation {
                        duringRead()
                        return target.copy(ordinal = ordinal)
                    }
                    override fun close() { closed++ }
                }
                return mapOf(34 to listOf(object : UiAccessWindow {
                    override val metadata = window
                    override fun root() = node
                    override fun close() { closed++ }
                }))
            }
        }) { 1L }
        val provider = UiTargetSnapshotProvider({ expected -> actualSource.takeIf { it.context == expected } }, { reader }) { 1L }
        val storage = Storage(old)
        val coordinator = AnchorRegistrationCoordinator<Long>(AppAnchorStore(storage))
        assertTrue(coordinator.begin(1L, intent(), provider.read(source.context, app)) is AnchorRegistrationCapture.Selected)
        assertEquals(allocated, closed)
        assertEquals(old, storage.value)
        assertEquals(0, storage.writes)
        assertTrue(coordinator.choose(1L, AnchorMode.HOLD, provider.read(source.context, app)) is AnchorRegistrationCompletion.Saved)
        assertEquals(allocated, closed)
        assertEquals(AnchorMode.HOLD, storage.value!!.mode)

        val saved = storage.value
        coordinator.begin(2L, intent(), provider.read(source.context, app))
        duringRead = { actualSource = source.copy(displayGeneration = "replacement") }
        assertEquals(AnchorRegistrationCompletion.ContextChanged,
            coordinator.choose(2L, AnchorMode.CLICK, provider.read(source.context, app)))
        assertEquals(allocated, closed)
        assertEquals(saved, storage.value)
        assertEquals(1, storage.writes)
    }
}
