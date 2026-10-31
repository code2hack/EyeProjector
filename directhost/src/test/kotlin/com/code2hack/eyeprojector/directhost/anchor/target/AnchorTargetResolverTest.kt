package com.code2hack.eyeprojector.directhost.anchor.target

import com.code2hack.eyeprojector.directhost.ProjectionContext
import com.code2hack.eyeprojector.directhost.uiaccess.*
import com.code2hack.eyeprojector.protocol.Geometry
import org.junit.Assert.*
import org.junit.Test

/** Actual production resolver/codec and snapshot/geometry models; no device or app effects. */
class AnchorTargetResolverTest {
    private val app = "example.app"
    private val activity = "example.app/.Current"
    private val originalBounds = UiBounds(10, 20, 70, 60)
    private val geometry = UiSourceGeometry(Geometry(400, 400), Geometry(1000, 800), 320, 0, false, Geometry(500, 400))
    private val source = ManagedUiSource(ProjectionContext("owner-one", "session-one"), "generation-one", 34,
        "display-one", app, activity, geometry)

    private fun target(id: String? = "example.app:id/action", ordinal: Int = 2, window: Int = 7,
        bounds: UiBounds = originalBounds, className: String? = "android.widget.Button") =
        UiTargetObservation(ordinal, window, app, id, className, bounds, listOf(16), true, false, true, true)

    private fun window(targets: List<UiTargetObservation> = listOf(target()), id: Int = 7,
        active: Boolean = false, focused: Boolean = true, display: Int = source.displayId,
        status: UiWindowReadStatus = UiWindowReadStatus.READ, type: Int = 1, layer: Int = 3) =
        UiWindowSnapshot(UiWindowMetadata(display, id, type, layer, active, focused, UiBounds(0, 0, 400, 400)), status, targets)

    private fun snapshot(windows: List<UiWindowSnapshot> = listOf(window()), current: ManagedUiSource = source,
        selectedPackage: String = app, selectedWindow: Int? = null, status: UiTargetReadStatus = UiTargetReadStatus.READ) =
        UiTargetSnapshot(UiTargetRequest(current, selectedPackage, selectedWindow), status, 1, 2, windows)

    private fun saved(screen: UiTargetSnapshot = snapshot(), selected: UiTargetObservation = target()): AnchorTargetDescriptor =
        (AnchorTargetResolver.capture(screen, selected) as AnchorTargetCapture.Captured).descriptor

    private fun resolved(descriptor: AnchorTargetDescriptor = saved(), screen: UiTargetSnapshot = snapshot()) =
        (AnchorTargetResolver.resolve(descriptor, screen) as AnchorTargetResolution.Unique).current

    private fun missing(reason: AnchorTargetMissingReason, screen: UiTargetSnapshot, descriptor: AnchorTargetDescriptor = saved()) {
        assertEquals(AnchorTargetResolution.Missing(reason), AnchorTargetResolver.resolve(descriptor, screen))
    }

    private fun unavailable(reason: AnchorTargetUnavailableReason, screen: UiTargetSnapshot,
        descriptor: AnchorTargetDescriptor = saved()) {
        assertEquals(reason, (AnchorTargetResolver.resolve(descriptor, screen) as AnchorTargetResolution.Unavailable).reason)
    }

    @Test fun capturesStaticValuesFromFocusedButInactiveWindow() {
        val result = AnchorTargetResolver.capture(snapshot(), target()) as AnchorTargetCapture.Captured
        assertEquals(AnchorTargetDescriptor(app, activity, 1, "example.app:id/action", "android.widget.Button"), result.descriptor)
        assertEquals(source, result.current.source)
        assertFalse(result.current.window.active)
        assertTrue(result.current.window.focused)
        assertEquals(target(), result.current.target)
    }

    @Test fun followsRecreatedWindowNodeSessionAndDisplayWithMovedScaledCurrentBounds() {
        val descriptor = AnchorTargetDescriptorCodec.decode(AnchorTargetDescriptorCodec.encode(saved()))
        val moved = target(ordinal = 87, window = 42, bounds = UiBounds(200, 300, 360, 380))
        val newSource = source.copy(context = ProjectionContext("owner-two", "session-two"), displayGeneration = "generation-two",
            displayId = 91, displayUniqueId = "display-two", geometry = geometry.copy(logical = Geometry(800, 800), densityDpi = 416))
        val screen = snapshot(listOf(window(listOf(target(id = "example.app:id/other", window = 42), moved), id = 42,
            display = 91)), current = newSource)
        val current = resolved(descriptor, screen)
        assertEquals(newSource, current.source)
        assertEquals(42, current.window.windowId)
        assertEquals(moved, current.target)
        assertEquals(UiRectangle(150.0, 150.0, 230.0, 190.0), current.source.geometry.sourceToVideo(current.target.bounds))
        assertEquals(descriptor, saved(screen, moved))
    }

    @Test fun actualReaderProviderRecreatesAndClosesHandlesBeforePersistentResolution() {
        var current = source
        var currentWindow = 7
        var currentBounds = originalBounds
        var prependSibling = false
        var allocated = 0
        var closed = 0
        class Node(private val value: UiTargetObservation?, private val children: List<UiAccessNode> = emptyList()) : UiAccessNode {
            init { allocated++ }
            override val packageName get() = app
            override val windowId get() = currentWindow
            override val childCount get() = children.size
            override fun child(index: Int) = children[index]
            override fun observation(ordinal: Int) = value?.copy(ordinal = ordinal)
            override fun close() { closed++ }
        }
        val access = object : UiAccess {
            override val canRead = true
            override fun windowsOnAllDisplays(): Map<Int, List<UiAccessWindow>> {
                val control = Node(target(window = currentWindow, bounds = currentBounds))
                val root = Node(null, if (prependSibling) listOf(Node(null), control) else listOf(control))
                allocated++
                val window = object : UiAccessWindow {
                    override val metadata = window(id = currentWindow, display = current.displayId).window
                    override fun root() = root
                    override fun close() { closed++ }
                }
                return mapOf(current.displayId to listOf(window))
            }
        }
        var uptime = 0L
        val reader = UiTargetReader(access) { ++uptime }
        val provider = UiTargetSnapshotProvider({ expected -> current.takeIf { it.context == expected } }, { reader }) { ++uptime }
        val first = provider.read(current.context, app)
        val firstTarget = first.windows.single().targets.single()
        val descriptor = saved(first, firstTarget)
        assertEquals(allocated, closed)

        currentWindow = 42
        currentBounds = UiBounds(200, 300, 360, 380)
        prependSibling = true
        current = current.copy(context = ProjectionContext("new-owner", "new-session"), displayId = 91,
            displayGeneration = "new-generation", geometry = geometry.copy(rotation = 1, logical = Geometry(800, 800)))
        val second = provider.read(current.context, app)
        val currentTarget = resolved(descriptor, second)
        assertNotEquals(firstTarget.ordinal, currentTarget.target.ordinal)
        assertEquals(42, currentTarget.window.windowId)
        assertEquals(current, currentTarget.source)
        assertEquals(currentBounds, currentTarget.target.bounds)
        assertEquals(allocated, closed)
        assertEquals(descriptor, saved(second, currentTarget.target))
    }

    @Test fun noFallbackToOldCoordinateOrAnotherResourceId() {
        missing(AnchorTargetMissingReason.TARGET_ABSENT,
            snapshot(listOf(window(listOf(target(id = "example.app:id/different"))))))
        missing(AnchorTargetMissingReason.TARGET_ABSENT,
            snapshot(listOf(window(listOf(target(className = "other.Control"))))))
    }

    @Test fun repeatedIdentityIsAmbiguousRegardlessOfOrdinalBoundsActionsOrLayer() {
        val repeated = target(ordinal = 99, bounds = UiBounds(200, 200, 250, 250)).copy(longClickable = true, actionIds = listOf(32))
        val screen = snapshot(listOf(window(listOf(target(), repeated), layer = 99)))
        val expected = AnchorTargetResolution.Ambiguous(AnchorTargetAmbiguity.TARGET_IDENTITY, 2)
        assertEquals(expected, AnchorTargetResolver.resolve(saved(), screen))
        assertEquals(AnchorTargetCapture.Rejected(expected), AnchorTargetResolver.capture(screen, target()))
    }

    @Test fun classRefinesAResourceIdButMissingClassNeverGuesses() {
        val other = target(ordinal = 3, className = "other.Control")
        assertEquals(target(), resolved(screen = snapshot(listOf(window(listOf(target(), other))))).target)
        val unknownClass = target(className = null)
        val screen = snapshot(listOf(window(listOf(unknownClass, other))))
        assertEquals(AnchorTargetCapture.Rejected(AnchorTargetResolution.Ambiguous(AnchorTargetAmbiguity.TARGET_IDENTITY, 2)),
            AnchorTargetResolver.capture(screen, unknownClass))
        missing(AnchorTargetMissingReason.TARGET_ABSENT, snapshot(listOf(window(listOf(unknownClass)))))
    }

    @Test fun nullAndBlankResourceIdsHaveExplicitUnavailableCoverage() {
        for (id in listOf(null, "", "  ")) {
            val selected = target(id = id)
            assertEquals(AnchorTargetCapture.Rejected(AnchorTargetResolution.Unavailable(AnchorTargetUnavailableReason.IDENTITY_UNAVAILABLE)),
                AnchorTargetResolver.capture(snapshot(listOf(window(listOf(selected)))), selected))
        }
    }

    @Test fun unobservedActivityStaysNullAcrossCodecAndMatchesOnlyPackageWindowIdentity() {
        val screen = snapshot(current = source.copy(foregroundActivity = null))
        val descriptor = saved(screen)
        assertNull(descriptor.activityName)
        val decoded = AnchorTargetDescriptorCodec.decode(AnchorTargetDescriptorCodec.encode(descriptor))
        assertEquals(descriptor, decoded)
        assertEquals(target(), resolved(decoded, screen).target)
        // An unobserved saved Activity cannot distinguish ID reuse in another screen.
        assertEquals(target(), resolved(decoded, snapshot(current = source.copy(foregroundActivity = "example.app/.Other"))).target)
    }

    @Test fun knownActivityRequiresCurrentObservationAndDoesNotFollowAnotherActivity() {
        unavailable(AnchorTargetUnavailableReason.ACTIVITY_UNOBSERVED, snapshot(current = source.copy(foregroundActivity = null)))
        unavailable(AnchorTargetUnavailableReason.ACTIVITY_UNOBSERVED, snapshot(current = source.copy(foregroundActivity = " ")))
        missing(AnchorTargetMissingReason.ACTIVITY_CHANGED, snapshot(current = source.copy(foregroundActivity = "example.app/.Other")))
    }

    @Test fun selectedAppAndActualForegroundPackageAreSeparateRequiredContexts() {
        missing(AnchorTargetMissingReason.SELECTED_PACKAGE_CHANGED, snapshot(selectedPackage = "another.app"))
        missing(AnchorTargetMissingReason.FOREGROUND_PACKAGE_CHANGED, snapshot(current = source.copy(foregroundPackage = "another.app")))
        unavailable(AnchorTargetUnavailableReason.FOREGROUND_PACKAGE_UNOBSERVED, snapshot(current = source.copy(foregroundPackage = null)))
        unavailable(AnchorTargetUnavailableReason.FOREGROUND_PACKAGE_UNOBSERVED, snapshot(current = source.copy(foregroundPackage = "")))
    }

    @Test fun onlyCurrentWindowOnCurrentDisplayIsSearched() {
        val inactive = window(listOf(target(ordinal = 9, window = 8)), id = 8, focused = false, layer = 100)
        val wrongDisplay = window(listOf(target(ordinal = 10, window = 9)), id = 9, display = 0)
        assertEquals(7, resolved(screen = snapshot(listOf(inactive, wrongDisplay, window()))).window.windowId)
        missing(AnchorTargetMissingReason.CURRENT_WINDOW_ABSENT, snapshot(listOf(wrongDisplay)))
        missing(AnchorTargetMissingReason.CURRENT_WINDOW_ABSENT, snapshot(listOf(inactive)))
        assertEquals(7, resolved(screen = snapshot(listOf(window(active = true, focused = false)))).window.windowId)
    }

    @Test fun twoCurrentWindowsRemainAmbiguousRatherThanChoosingLayerOrAnAvailableMatch() {
        val other = window(emptyList(), id = 8, active = true, focused = false, layer = 100)
        assertEquals(AnchorTargetResolution.Ambiguous(AnchorTargetAmbiguity.CURRENT_WINDOW, 2),
            AnchorTargetResolver.resolve(saved(), snapshot(listOf(window(), other))))
    }

    @Test fun explicitCurrentWindowScopesLookupWithoutPersistingItsId() {
        val other = window(listOf(target(ordinal = 4, window = 8)), id = 8, active = true)
        val screen = snapshot(listOf(window(), other), selectedWindow = 8)
        assertEquals(8, resolved(screen = screen).window.windowId)
        assertEquals(saved(), saved(screen, other.targets.single()))
        missing(AnchorTargetMissingReason.CURRENT_WINDOW_ABSENT, snapshot(selectedWindow = 99))
        missing(AnchorTargetMissingReason.CURRENT_WINDOW_ABSENT,
            snapshot(listOf(window(display = 0)), selectedWindow = 7))
        unavailable(AnchorTargetUnavailableReason.WINDOW_NOT_CURRENT,
            snapshot(listOf(window(focused = false)), selectedWindow = 7))
    }

    @Test fun anotherPackagesWindowCannotSupplyAReplacementTarget() {
        val foreign = window(listOf(target(window = 8).copy(packageName = "another.app")), id = 8,
            status = UiWindowReadStatus.OTHER_PACKAGE)
        assertEquals(7, resolved(screen = snapshot(listOf(window(), foreign))).window.windowId)
        missing(AnchorTargetMissingReason.CURRENT_WINDOW_ABSENT, snapshot(listOf(foreign)))
        missing(AnchorTargetMissingReason.TARGET_ABSENT,
            snapshot(listOf(window(listOf(target().copy(packageName = "another.app"))))))
        missing(AnchorTargetMissingReason.TARGET_ABSENT,
            snapshot(listOf(window(listOf(target(window = 99))))))
    }

    @Test fun changedWindowTypeDoesNotBecomeADifferentControl() {
        missing(AnchorTargetMissingReason.WINDOW_TYPE_CHANGED, snapshot(listOf(window(type = 2))))
    }

    @Test fun everyUnavailableSnapshotStatusIsDistinctFromAnAbsentTarget() {
        for (status in UiTargetReadStatus.entries.filter { it != UiTargetReadStatus.READ }) {
            assertEquals(AnchorTargetResolution.Unavailable(AnchorTargetUnavailableReason.READ_UNAVAILABLE, readStatus = status),
                AnchorTargetResolver.resolve(saved(), snapshot(status = status)))
        }
        unavailable(AnchorTargetUnavailableReason.REQUEST_UNAVAILABLE, snapshot().copy(request = null))
    }

    @Test fun incompleteWindowReadCannotResolveFromPartialTargetsOrAnotherWindow() {
        for (status in listOf(UiWindowReadStatus.FAILED, UiWindowReadStatus.NO_ROOT, UiWindowReadStatus.WINDOW_MISMATCH)) {
            assertEquals(AnchorTargetResolution.Unavailable(AnchorTargetUnavailableReason.WINDOW_READ_UNAVAILABLE, windowStatus = status),
                AnchorTargetResolver.resolve(saved(), snapshot(listOf(window(status = status)))))
            val failed = window(status = status, id = 8, active = true)
            assertEquals(AnchorTargetResolution.Ambiguous(AnchorTargetAmbiguity.CURRENT_WINDOW, 2),
                AnchorTargetResolver.resolve(saved(), snapshot(listOf(window(), failed))))
        }
    }

    @Test fun registrationMustUseAnActualObservationOfTheCurrentWindow() {
        for (selected in listOf(target(ordinal = 99), target(window = 8), target(bounds = UiBounds(11, 20, 70, 60)))) {
            assertEquals(AnchorTargetCapture.Rejected(AnchorTargetResolution.Missing(AnchorTargetMissingReason.SELECTION_ABSENT)),
                AnchorTargetResolver.capture(snapshot(), selected))
        }
        val screen = snapshot(status = UiTargetReadStatus.ACCESS_REQUIRED)
        assertEquals(AnchorTargetCapture.Rejected(AnchorTargetResolver.resolve(saved(), screen)),
            AnchorTargetResolver.capture(screen, target()))
    }

    @Test fun unusableBoundsHiddenAndDisabledTargetsAreMissing() {
        for (unusable in listOf(target().copy(visible = false), target().copy(enabled = false),
            target(bounds = UiBounds(10, 20, 10, 60)), target(bounds = UiBounds(70, 60, 10, 20)))) {
            val screen = snapshot(listOf(window(listOf(unusable))))
            missing(AnchorTargetMissingReason.TARGET_ABSENT, screen)
            assertTrue(AnchorTargetResolver.capture(screen, unusable) is AnchorTargetCapture.Rejected)
        }
    }

    @Test fun advertisedActionsDoNotChooseClickOrHoldOrBecomeIdentity() {
        val changed = target().copy(clickable = false, longClickable = true, actionIds = listOf(32))
        assertEquals(saved(), saved(snapshot(listOf(window(listOf(changed)))), changed))
        assertEquals(changed, resolved(screen = snapshot(listOf(window(listOf(changed))))).target)
    }

    @Test fun failedResolutionsLeaveSavedDescriptorReusable() {
        val descriptor = saved()
        val before = AnchorTargetDescriptorCodec.encode(descriptor)
        assertTrue(AnchorTargetResolver.resolve(descriptor, snapshot(listOf(window(emptyList())))) is AnchorTargetResolution.Missing)
        assertTrue(AnchorTargetResolver.resolve(descriptor, snapshot(status = UiTargetReadStatus.SOURCE_CHANGED)) is AnchorTargetResolution.Unavailable)
        assertArrayEquals(before, AnchorTargetDescriptorCodec.encode(descriptor))
        assertEquals(target(), resolved(descriptor).target)
    }

    @Test fun codecRoundtripsNullableAndUnicodeValuesWithoutAPreferenceStore() {
        for (value in listOf(saved(), saved().copy(activityName = null, className = null),
            AnchorTargetDescriptor("app.例", "app.例/.画面", 1, "app.例:id/操作😀", "app.例.Control"))) {
            assertEquals(value, AnchorTargetDescriptorCodec.decode(AnchorTargetDescriptorCodec.encode(value)))
        }
        // The encoding introduces no writeUTF 64 KiB string ceiling or target-count policy.
        val large = saved().copy(resourceId = "x".repeat(70_000))
        assertEquals(large, AnchorTargetDescriptorCodec.decode(AnchorTargetDescriptorCodec.encode(large)))
    }

    @Test fun codecRejectsTruncationTrailingDataAndUnknownVersion() {
        val payload = AnchorTargetDescriptorCodec.encode(saved())
        for (size in payload.indices) {
            assertThrows(Exception::class.java) { AnchorTargetDescriptorCodec.decode(payload.copyOf(size)) }
        }
        assertThrows(IllegalArgumentException::class.java) { AnchorTargetDescriptorCodec.decode(payload + byteArrayOf(0)) }
        assertThrows(IllegalArgumentException::class.java) { AnchorTargetDescriptorCodec.decode(payload.copyOf().also { it[3] = 2 }) }
    }

    @Test fun codecRejectsImpossibleLengthsInvalidUtfAndPresenceFlags() {
        val payload = AnchorTargetDescriptorCodec.encode(saved())
        assertThrows(IllegalArgumentException::class.java) {
            AnchorTargetDescriptorCodec.decode(payload.copyOf().also { it[4] = 127 })
        }
        assertThrows(IllegalArgumentException::class.java) {
            AnchorTargetDescriptorCodec.decode(payload.copyOf().also { it[4] = -1 })
        }
        assertThrows(java.nio.charset.CharacterCodingException::class.java) {
            AnchorTargetDescriptorCodec.decode(payload.copyOf().also { it[8] = -1 })
        }
        assertThrows(IllegalArgumentException::class.java) {
            AnchorTargetDescriptorCodec.decode(payload.copyOf().also { it[8 + app.toByteArray().size] = 2 })
        }
    }

    @Test fun descriptorRejectsAbsentStaticIdentityInsteadOfMakingCoordinateOnlyAnchors() {
        assertThrows(IllegalArgumentException::class.java) { saved().copy(resourceId = " ") }
        assertThrows(IllegalArgumentException::class.java) { saved().copy(packageName = "") }
        assertThrows(IllegalArgumentException::class.java) { saved().copy(activityName = "") }
        assertThrows(IllegalArgumentException::class.java) { saved().copy(className = "") }
    }
}
