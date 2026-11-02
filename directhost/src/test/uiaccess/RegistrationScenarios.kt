package com.code2hack.eyeprojector.directhost.uiaccess

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.util.SparseArray
import android.view.accessibility.AccessibilityNodeInfo as Node
import android.view.accessibility.AccessibilityWindowInfo as Window
import com.code2hack.eyeprojector.directhost.ProjectionContext
import com.code2hack.eyeprojector.directhost.anchor.preferences.*
import com.code2hack.eyeprojector.directhost.anchor.registration.*
import com.code2hack.eyeprojector.directhost.anchor.target.*
import com.code2hack.eyeprojector.protocol.*

internal data class RawVoiceNode(val ordinal: Int, val parent: Int?, val childIndex: Int, val childCount: Int,
    val resourceId: String?, val className: String, val bounds: List<Int>, val actions: List<Int>,
    val clickable: Boolean, val longClickable: Boolean, val visible: Boolean, val enabled: Boolean)

/** Public metadata fixture; Android handles are doubles, reader/adapter/selection/store are actual. */
internal object RegistrationFixtureChecks {
    private const val app = "com.larus.nova"
    private val size = Geometry(840, 1120)
    private val source = ManagedUiSource(ProjectionContext("fixture-owner", "fixture-session"), "fixture-display",
        57, "fixture-display-id", app, null, UiSourceGeometry(Geometry(1248, 1663), size, 420, 0, false, size))
    private val metadata = UiWindowMetadata(57, 4160, 1, 0, true, true, UiBounds(0, 0, 1248, 1663))
    private val intent = AnchorIntent(AnchorIntentKind.REGISTER, AnchorSource.GLASSES_NATIVE, 13, 13,
        AnchorIntentContext(ReceiverUsability("fixture-owner", ReceiverIncarnation("fixture-receiver", 57),
            835, 107, ReceiverUsabilityMode.PROJECTED), HeadPointerState(107, 0, true, true,
            201.89757f, 609.461f, Fit(0, 0, 480, 640), size)))

    private fun read(raw: List<RawVoiceNode>, reverse: Boolean = false): UiTargetSnapshot {
        val handles = mutableListOf<Node>()
        fun build(value: RawVoiceNode): Node {
            val children = raw.filter { it.parent == value.ordinal }.sortedBy { it.childIndex }
                .let { if (reverse) it.reversed() else it }.map(::build)
            return Node(packageName = app, windowId = 4160, viewIdResourceName = value.resourceId,
                className = value.className, screen = Rect(value.bounds[0], value.bounds[1], value.bounds[2], value.bounds[3]),
                isClickable = value.clickable, isLongClickable = value.longClickable,
                isVisibleToUser = value.visible, isEnabled = value.enabled,
                actionList = value.actions.map { Node.AccessibilityAction(it) }, children = children).also(handles::add)
        }
        val root = build(raw.single { it.parent == null })
        val window = Window(57, 4160, 1, 0, true, true, root, Rect(0, 0, 1248, 1663))
        val snapshot = UiTargetReader(AndroidUiAccess(AccessibilityService(
            windowsOnAllDisplays = SparseArray(mapOf(57 to listOf(window))))), { 1L })
            .read(UiTargetRequest(source, app))
        check(snapshot.status == UiTargetReadStatus.READ)
        check(handles.size == raw.size && handles.all { it.closed == 1 } && window.closed == 1)
        return snapshot
    }

    private fun selected(snapshot: UiTargetSnapshot): AnchorRegistrationTarget {
        val result = AnchorRegistrationTargets.capture(intent, snapshot)
        check(result is AnchorRegistrationCapture.Selected) { "Expected selected actual voice target, got $result" }
        return result.target
    }
    private class Storage : AppAnchorStorage {
        var value: AppAnchor? = null
        var writes = 0
        override fun read(packageName: String) = value
        override fun write(packageName: String, anchor: AppAnchor?) { value = anchor; writes++ }
    }

    @JvmStatic fun main(args: Array<String>) {
        val raw = capturedVoiceNodes()
        check(raw.size == 50 && raw.all { n -> raw.count { it.parent == n.ordinal } == n.childCount })
        val actual = selected(read(raw))
        check(actual.descriptor.resourceId == "$app:id/speak_normal")
        check(actual.current.target.bounds == UiBounds(295, 1523, 953, 1592))
        val point = source.geometry.videoToSource(UiPoint(353.0, 1066.0))
        check(kotlin.math.abs(point.x - 524.4571428571429) < 0.0000001)
        check(kotlin.math.abs(point.y - 1584.2341376228776) < 0.0000001)
        println("PASS exact raw50 voice tree actual Android adapter/reader and original integer mapping")

        for (variant in listOf("none", "all", "other")) {
            val changed = raw.map { n -> when (variant) {
                "none" -> n.copy(clickable = false, longClickable = false, actions = emptyList())
                "all" -> n.copy(clickable = true, longClickable = true, actions = listOf(16, 32, 16973842))
                else -> n.copy(clickable = n.ordinal % 2 == 0, longClickable = n.ordinal % 3 == 0, actions = listOf(4, 4096))
            } }
            val snapshot = read(changed)
            check(selected(snapshot).descriptor == actual.descriptor)
            for (mode in AnchorMode.entries) {
                val storage = Storage(); val store = AppAnchorStore(storage)
                val coordinator = AnchorRegistrationCoordinator<String>(store)
                check(coordinator.begin("original", intent, snapshot) is AnchorRegistrationCapture.Selected)
                check(storage.writes == 0) { "Registration begin wrote a preference" }
                check(coordinator.choose("original", mode) is AnchorRegistrationCompletion.Saved)
                check(storage.value == AppAnchor(actual.descriptor, mode) && storage.writes == 1)
            }
        }
        println("PASS flags/actions alone change neither capture, resolution nor explicit Click/Hold registration")

        val reordered = selected(read(raw, reverse = true))
        check(reordered.descriptor == actual.descriptor)
        check(reordered.current.target.ordinal != actual.current.target.ordinal)
        val moved = raw.map { n -> if (n.resourceId == actual.descriptor.resourceId)
            n.copy(bounds = listOf(345, 1400, 1003, 1469)) else n }
        val resolved = AnchorTargetResolver.resolve(actual.descriptor, read(moved))
        check(resolved is AnchorTargetResolution.Unique && resolved.current.target.bounds == UiBounds(345, 1400, 1003, 1469))
        println("PASS actual hierarchy not ordinal/ranking; saved identity resolves new bounds")

        val label = raw.single { it.ordinal == 36 }
        for (parent in listOf(3, 35)) {
            val competitor = label.copy(ordinal = 50, parent = parent, childIndex = 99, resourceId = "$app:id/competing")
            check(AnchorRegistrationTargets.capture(intent, read(raw + competitor)) ==
                AnchorRegistrationCapture.Rejected(AnchorRegistrationFailure.HOVER_AMBIGUOUS))
        }
        val duplicate = label.copy(ordinal = 50, parent = 3, childIndex = 99, bounds = listOf(295, 1000, 953, 1069))
        check(AnchorRegistrationTargets.capture(intent, read(raw + duplicate)) is AnchorRegistrationCapture.Rejected)
        println("PASS unrelated/nested siblings and duplicate persistent identity remain ambiguous")

        val noLabelId = raw.map { if (it.ordinal == 36) it.copy(resourceId = null) else it }
        val idless = AnchorRegistrationTargets.inspect(intent, read(noLabelId))
        check(idless.capture is AnchorRegistrationCapture.Rejected && idless.selected?.resourceId == null)
        val root = raw.single { it.parent == null }
        for (disabled in listOf(false, true)) {
            val hidden = label.copy(parent = root.ordinal, childIndex = 0,
                visible = disabled, enabled = !disabled)
            check(AnchorRegistrationTargets.capture(intent, read(listOf(root, hidden))) is AnchorRegistrationCapture.Rejected)
        }
        val storage = Storage(); val coordinator = AnchorRegistrationCoordinator<String>(AppAnchorStore(storage))
        check(coordinator.begin("cancel", intent, read(raw)) is AnchorRegistrationCapture.Selected)
        check(coordinator.cancel("cancel") && storage.writes == 0)
        println("PASS no ancestor target substitution, nonvisible/disabled exclusion and cancel without a write")
    }
}
