package com.code2hack.eyeprojector.directhost.anchor.registration

import com.code2hack.eyeprojector.directhost.ProjectionContext
import com.code2hack.eyeprojector.directhost.anchor.preferences.*
import com.code2hack.eyeprojector.directhost.anchor.target.*
import com.code2hack.eyeprojector.directhost.uiaccess.*
import com.code2hack.eyeprojector.protocol.*
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

/** Captured tx6 structure with explicitly synthetic identity values; never live-app label evidence. */
class IdlessIdentityTest {
    private val app = "sh.paseo"
    private val size = Geometry(1248, 1663)
    private val source = ManagedUiSource(ProjectionContext("owner", "session"), "display", 68, "display",
        app, null, UiSourceGeometry(size, size, 420, 0, false, size))
    private val window = UiWindowMetadata(68, 4948, 1, 0, true, true, UiBounds(0, 0, 1248, 1663))
    private val rootPath = listOf(1, 2, 3, 4, 5, 6, 133).mapIndexed { index, ordinal ->
        UiDrawingOrderStep(ordinal, if (ordinal == 133) 2 else if (index == 0) 0 else 1, if (index == 0) -1 else 0)
    }
    private val composer = UiTargetObservation(133, 4948, app, "composer-viewport-content", "android.view.ViewGroup",
        UiBounds(0, 1390, 1248, 1663), emptyList(), false, false, true, true,
        listOf(1, 2, 3, 4, 5, 6), 2, rootPath)
    private val input = composer.copy(ordinal = 134, resourceId = "message-input-root",
        bounds = UiBounds(42, 1390, 1206, 1621), ancestorOrdinals = composer.ancestorOrdinals + 133,
        drawingOrder = 1, drawingOrderPath = rootPath + UiDrawingOrderStep(134, 1, 0), editable = true, inputType = 1)
    private val button = composer.copy(ordinal = 142, resourceId = null, className = "android.widget.Button",
        bounds = UiBounds(1019, 1524, 1092, 1597), ancestorOrdinals = composer.ancestorOrdinals + 133,
        drawingOrder = 7, drawingOrderPath = rootPath + UiDrawingOrderStep(142, 7, 6))
    private val fact = ReceiverUsability("owner", ReceiverIncarnation("receiver", 1), 3, 4, ReceiverUsabilityMode.PROJECTED)
    private val intent = AnchorIntent(AnchorIntentKind.REGISTER, AnchorSource.GLASSES_NATIVE, 1, 1,
        AnchorIntentContext(fact, HeadPointerState(4, 1, true, true, 1055f, 1553f, Fit(0, 0, 1248, 1663), size)))
    private val syntheticLabel = UiControlLabel(UiControlLabelSource.CONTENT_DESCRIPTION, uiIdentityFingerprint("fixture static control"))
    private fun snapshot(nodes: List<UiTargetObservation>, current: ManagedUiSource = source) = UiTargetSnapshot(
        UiTargetRequest(current, app), UiTargetReadStatus.READ, 1, 2,
        listOf(UiWindowSnapshot(window.copy(displayId = current.displayId), UiWindowReadStatus.READ, nodes)))
    private fun capture(control: UiTargetObservation) = AnchorRegistrationTargets.inspect(intent, snapshot(listOf(composer, input, control)))
    private fun saved(result: AnchorRegistrationInspection) = (result.capture as AnchorRegistrationCapture.Selected).target

    @Test fun observedPaseoFrontmostButtonIsNeverSubstitutedWithComposerEvenWithoutIdentity() {
        val result = capture(button)
        assertEquals(button, result.selected)
        assertEquals(AnchorRegistrationCapture.Rejected(AnchorRegistrationFailure.TARGET_UNAVAILABLE), result.capture)
        assertEquals(AnchorTargetUnavailableReason.IDENTITY_UNAVAILABLE, (result.resolution as AnchorTargetResolution.Unavailable).reason)
        assertNotEquals(composer.bounds, result.selected!!.bounds)
    }

    @Test fun publicUniqueIdKeepsExactSelectedButtonAndFindsItAfterOrdinalPositionAndSessionChange() {
        val selected = button.copy(uniqueId = UiNodeUniqueId("fixture-provider-control-key"))
        val captured = saved(capture(selected))
        assertEquals(selected, captured.current.target)
        assertEquals(selected.bounds, captured.current.target.bounds)
        assertNull(captured.descriptor.resourceId)
        assertEquals("fixture-provider-control-key", captured.descriptor.uniqueId)
        val descriptor = AnchorTargetDescriptorCodec.decode(AnchorTargetDescriptorCodec.encode(captured.descriptor))
        val moved = selected.copy(ordinal = 900, bounds = UiBounds(100, 200, 160, 260), drawingOrderPath = emptyList())
        val current = source.copy(context = ProjectionContext("new-owner", "new-session"), displayId = 91, displayGeneration = "new-display")
        val resolved = AnchorTargetResolver.resolve(descriptor, snapshot(listOf(composer, input, moved), current)) as AnchorTargetResolution.Unique
        assertEquals(moved, resolved.current.target)
        assertEquals(current, resolved.current.source)
    }

    @Test fun semanticControlLabelUsesAncestorAsContextWithoutReplacingControlOrBounds() {
        val selected = button.copy(controlLabel = syntheticLabel)
        val captured = saved(capture(selected))
        val descriptor = AnchorTargetDescriptorCodec.decode(AnchorTargetDescriptorCodec.encode(captured.descriptor))
        assertNull(descriptor.resourceId)
        assertNull(descriptor.uniqueId)
        assertEquals(composer.resourceId, descriptor.ancestorResourceId)
        assertEquals(selected, captured.current.target)
        val moved = selected.copy(ordinal = 900, bounds = UiBounds(100, 200, 160, 260), ancestorOrdinals = listOf(500))
        val newComposer = composer.copy(ordinal = 500)
        val resolved = AnchorTargetResolver.resolve(descriptor, snapshot(listOf(newComposer, moved))) as AnchorTargetResolution.Unique
        assertEquals(moved, resolved.current.target)
        assertNotEquals(newComposer.bounds, resolved.current.target.bounds)
        val wrapper = composer.copy(ordinal = 501, resourceId = "new-wrapper", ancestorOrdinals = listOf(500))
        val nested = moved.copy(ancestorOrdinals = listOf(500, 501))
        assertEquals(nested, (AnchorTargetResolver.resolve(descriptor, snapshot(listOf(newComposer, wrapper, nested)))
            as AnchorTargetResolution.Unique).current.target)
        assertTrue(AnchorTargetResolver.resolve(descriptor, snapshot(listOf(input))) is AnchorTargetResolution.Missing)
        assertTrue(AnchorTargetResolver.resolve(descriptor, snapshot(listOf(newComposer.copy(resourceId = "other-composer"), moved))) is AnchorTargetResolution.Missing)
    }

    @Test fun idlessLabelWithPackageWindowClassContextDoesNotRequireAnAncestorViewId() {
        for (className in listOf("android.widget.Button", "android.view.View", "example.CustomControl")) {
            val selected = button.copy(className = className, controlLabel = syntheticLabel, ancestorOrdinals = emptyList())
            val result = AnchorTargetResolver.capture(snapshot(listOf(selected)), selected) as AnchorTargetCapture.Captured
            assertNull(result.descriptor.ancestorResourceId)
            assertEquals(selected, (AnchorTargetResolver.resolve(result.descriptor, snapshot(listOf(selected))) as AnchorTargetResolution.Unique).current.target)
            assertTrue(AnchorTargetResolver.resolve(result.descriptor, snapshot(listOf(selected.copy(className = "other.Class")))) is AnchorTargetResolution.Missing)
        }
    }

    @Test fun inputContentCannotBecomeSemanticControlIdentityButInputNodeKeysRemainUsable() {
        for (selected in listOf(button.copy(controlLabel = syntheticLabel, editable = true),
            button.copy(controlLabel = syntheticLabel, password = true), button.copy(controlLabel = syntheticLabel, inputType = 1))) {
            assertEquals(AnchorRegistrationCapture.Rejected(AnchorRegistrationFailure.TARGET_UNAVAILABLE), capture(selected).capture)
            val withKey = selected.copy(uniqueId = UiNodeUniqueId("fixture-input-node-key"))
            assertEquals(withKey, saved(capture(withKey)).current.target)
        }
        val descriptor = saved(capture(button.copy(controlLabel = syntheticLabel))).descriptor
        val editor = button.copy(controlLabel = syntheticLabel, editable = true)
        assertTrue(AnchorTargetResolver.resolve(descriptor, snapshot(listOf(composer, editor))) is AnchorTargetResolution.Missing)
    }

    @Test fun repeatedSemanticOrNodeKeysRemainAmbiguousAndNeverMatchInputOrAncestorInstead() {
        for (selected in listOf(button.copy(controlLabel = syntheticLabel), button.copy(uniqueId = UiNodeUniqueId("fixture-key")))) {
            val captured = saved(capture(selected))
            val duplicate = selected.copy(ordinal = 300, bounds = UiBounds(200, 300, 260, 360))
            val resolved = AnchorTargetResolver.resolve(captured.descriptor, snapshot(listOf(composer, input, duplicate, selected)))
            assertEquals(AnchorTargetResolution.Ambiguous(AnchorTargetAmbiguity.TARGET_IDENTITY, 2), resolved)
        }
    }

    @Test fun semanticLabelIdentityIsIndependentOfAdvertisedClickAndHoldCapabilities() {
        val selected = button.copy(controlLabel = syntheticLabel)
        val descriptor = saved(capture(selected)).descriptor
        for (actions in listOf(emptyList(), listOf(16), listOf(32))) {
            assertEquals(descriptor, saved(capture(selected.copy(actionIds = actions, clickable = actions.isNotEmpty()))).descriptor)
        }
    }

    private fun legacyDescriptor(): ByteArray = ByteArrayOutputStream().also { bytes -> DataOutputStream(bytes).use { out ->
        fun text(value: String) { val data = value.toByteArray(Charsets.UTF_8); out.writeInt(data.size); out.write(data) }
        out.writeInt(1); text(app); out.writeBoolean(false); out.writeInt(1)
        text("legacy:id/control"); out.writeBoolean(true); text("android.widget.Button")
    } }.toByteArray()

    @Test fun legacyDescriptorAndPreferenceMigrateWithoutRewritingExistingSavedHoldOrCancel() {
        val oldBytes = legacyDescriptor()
        val old = AnchorTargetDescriptorCodec.decode(oldBytes)
        assertEquals(AnchorTargetDescriptor(app, null, 1, "legacy:id/control", "android.widget.Button"), old)
        assertEquals(old, AnchorTargetDescriptorCodec.decode(AnchorTargetDescriptorCodec.encode(old)))
        val stored = ByteArrayOutputStream().also { bytes -> DataOutputStream(bytes).use { out ->
            out.writeInt(1); out.writeUTF("HOLD"); out.write(oldBytes)
        } }.toByteArray()
        val directory = Files.createTempDirectory("anchor-identity-migration-").toFile()
        try {
            val name = MessageDigest.getInstance("SHA-256").digest(app.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
            val file = java.io.File(directory, "$name.anchor").apply { writeBytes(stored) }
            val store = AppAnchorStore(FileAppAnchorStorage(directory))
            assertEquals(AppAnchor(old, AnchorMode.HOLD), store.read(app))
            val original = button.copy(resourceId = old.resourceId)
            assertEquals(original, (AnchorTargetResolver.resolve(old, snapshot(listOf(original))) as AnchorTargetResolution.Unique).current.target)
            val coordinator = AnchorRegistrationCoordinator<Long>(store)
            assertTrue(coordinator.begin(1L, intent, snapshot(listOf(composer, button.copy(controlLabel = syntheticLabel)))) is AnchorRegistrationCapture.Selected)
            assertTrue(coordinator.cancel(1L))
            assertEquals(AppAnchor(old, AnchorMode.HOLD), store.read(app))
            assertArrayEquals(stored, file.readBytes())
            coordinator.begin(2L, intent, snapshot(listOf(composer, button.copy(controlLabel = syntheticLabel))))
            val result = coordinator.choose(2L, AnchorMode.CLICK) as AnchorRegistrationCompletion.Saved
            assertEquals(result.anchor, FileAppAnchorStorage(directory).read(app))
            assertNull(result.anchor.descriptor.resourceId)
            assertEquals(AnchorMode.CLICK, result.anchor.mode)
        } finally { directory.listFiles()?.forEach { it.delete() }; directory.delete() }
    }
}
