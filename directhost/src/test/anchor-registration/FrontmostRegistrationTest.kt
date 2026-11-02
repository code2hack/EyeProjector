package com.code2hack.eyeprojector.directhost.anchor.registration

import com.code2hack.eyeprojector.directhost.ProjectionContext
import com.code2hack.eyeprojector.directhost.anchor.target.AnchorTargetAmbiguity
import com.code2hack.eyeprojector.directhost.anchor.target.AnchorTargetResolution
import com.code2hack.eyeprojector.directhost.uiaccess.*
import com.code2hack.eyeprojector.protocol.*
import org.junit.Assert.*
import org.junit.Test

class FrontmostRegistrationTest {
    private val app = "com.larus.nova"
    private val size = Geometry(1248, 1663)
    private val source = ManagedUiSource(ProjectionContext("owner", "session"), "display-generation", 57, "display",
        app, null, UiSourceGeometry(size, size, 420, 0, false, size))
    private val window = UiWindowMetadata(57, 4160, 1, 0, true, true, UiBounds(0, 0, 1248, 1663))
    private fun step(ordinal: Int, order: Int?, index: Int = 0) = UiDrawingOrderStep(ordinal, order, index)
    private val root = step(1, 0, -1)
    private fun target(ordinal: Int, id: String?, path: List<UiDrawingOrderStep>) = UiTargetObservation(
        ordinal, 4160, app, id?.let { "$app:id/$it" }, "Button", UiBounds(0, 0, 1248, 1663),
        emptyList(), false, false, true, true, path.dropLast(1).map { it.ordinal }, path.last().drawingOrder, path)
    private fun inspect(targets: List<UiTargetObservation>, x: Float = 111f, y: Float = 1560f): AnchorRegistrationInspection {
        val fact = ReceiverUsability("owner", ReceiverIncarnation("receiver", 1), 3, 4, ReceiverUsabilityMode.PROJECTED)
        val intent = AnchorIntent(AnchorIntentKind.REGISTER, AnchorSource.GLASSES_NATIVE, 1, 1,
            AnchorIntentContext(fact, HeadPointerState(4, 1, true, true, x, y, Fit(0, 0, 1248, 1663), size)))
        return AnchorRegistrationTargets.inspect(intent, UiTargetSnapshot(UiTargetRequest(source, app),
            UiTargetReadStatus.READ, 1, 2, listOf(UiWindowSnapshot(window, UiWindowReadStatus.READ, targets))))
    }
    private fun selected(result: AnchorRegistrationInspection) = (result.capture as AnchorRegistrationCapture.Selected).target

    @Test fun siblingDrawingOrderWinsRegardlessOfTraversalOrdinalAreaOrAdvertisedActions() {
        val behind = target(900, "behind", listOf(root, step(900, 2, 9))).copy(
            bounds = UiBounds(100, 1500, 120, 1600), actionIds = listOf(16, 32), clickable = true, longClickable = true)
        val front = target(3, "front", listOf(root, step(3, 11, 0)))
        for (targets in listOf(listOf(behind, front), listOf(front, behind))) {
            assertEquals(front, selected(inspect(targets)).current.target)
        }
        assertEquals(behind.resourceId, selected(inspect(listOf(behind.copy(drawingOrderPath = listOf(root, step(900, 12))), front))).descriptor.resourceId)
    }

    @Test fun firstDivergentAncestorWinsRatherThanUnrelatedLeafOrderEvenWhenAncestorIsNotExported() {
        val behind = target(900, "behind", listOf(root, step(20, 2, 8), step(900, 100, 0)))
        val front = target(3, "front", listOf(root, step(30, 11, 0), step(3, 1, 9)))
        for (targets in listOf(listOf(behind, front), listOf(front, behind))) {
            assertEquals(front, selected(inspect(targets)).current.target)
        }
    }

    @Test fun equalOrMissingDivergentOrderDoesNotInventChildIndexOrdinalOrDeeperTieBreaker() {
        for (order in listOf<Int?>(7, null)) {
            val a = target(900, "a", listOf(root, step(20, order, 0), step(900, 100, 9)))
            val b = target(3, "b", listOf(root, step(30, 7, 9), step(3, 1, 0)))
            for (targets in listOf(listOf(a, b), listOf(b, a))) {
                assertEquals(AnchorRegistrationCapture.Rejected(AnchorRegistrationFailure.HOVER_AMBIGUOUS), inspect(targets).capture)
            }
        }
    }

    @Test fun descendantOfSameBranchStillWinsAndRawZeroOrderIsPreserved() {
        val parent = target(900, "parent", listOf(root, step(900, 0)))
        val child = target(3, "child", parent.drawingOrderPath + step(3, 1))
        assertEquals(child, selected(inspect(listOf(child, parent))).current.target)
        val sibling = target(4, "sibling", listOf(root, step(4, 2)))
        assertEquals(sibling, selected(inspect(listOf(parent, sibling))).current.target)
    }

    @Test fun selectedFrontTargetStillRequiresUniquePersistentIdentityWithoutTryingBehindTarget() {
        val behind = target(900, "behind", listOf(root, step(900, 2)))
        val front = target(3, "repeated", listOf(root, step(3, 11)))
        val duplicate = front.copy(ordinal = 4, bounds = UiBounds(0, 0, 20, 20),
            drawingOrderPath = listOf(root, step(4, 5)))
        val result = inspect(listOf(behind, front, duplicate))
        assertEquals(front, result.selected)
        assertEquals(AnchorRegistrationCapture.Rejected(AnchorRegistrationFailure.TARGET_UNAVAILABLE), result.capture)
        assertEquals(2, result.identityMatchCount)
        assertEquals(AnchorTargetAmbiguity.TARGET_IDENTITY, (result.resolution as AnchorTargetResolution.Ambiguous).kind)
        val noIdentity = inspect(listOf(behind, front.copy(resourceId = null)))
        assertNull(noIdentity.selected!!.resourceId)
        assertEquals(AnchorRegistrationCapture.Rejected(AnchorRegistrationFailure.TARGET_UNAVAILABLE), noIdentity.capture)
    }

    @Test fun historicalRaw306BottomSiblingsSelectFrontIconsWithExplicitlyModeledDrawingOrder() {
        // Raw306 contains actual old bounds/hierarchy but NO drawing-order observation.
        // The runner supplies modeled sibling drawing order for this reproduction only.
        // This is not current53 metadata or proof of current device dispatch/selection.
        val targets = historicalDoubaoNodes()
        for ((point, id) in listOf((111f to 1560f) to "action_entrance",
            (1030.5f to 1569f) to "action_input", (1153.5f to 1587f) to "iv_action_menu_entrance",
            (672f to 1579f) to "speak_normal")) {
            val result = inspect(targets, point.first, point.second)
            assertEquals("$app:id/$id", selected(result).descriptor.resourceId)
            assertTrue(result.hoverTargets.count { it.leaf } >= 1)
        }
    }
}
