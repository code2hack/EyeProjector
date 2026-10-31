package com.code2hack.eyeprojector.directhost.uiaccess

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Rect
import android.util.SparseArray
import android.view.accessibility.AccessibilityNodeInfo as Node
import android.view.accessibility.AccessibilityWindowInfo as Window
import com.code2hack.eyeprojector.directhost.ProjectionContext
import com.code2hack.eyeprojector.protocol.Geometry
import com.code2hack.eyeprojector.protocol.Fit
import kotlin.math.abs
import com.code2hack.eyeprojector.directhost.hid.PhoneHidKeyService
import com.code2hack.eyeprojector.directhost.hid.PhoneHidRuntime

private fun equal(actual: Double, expected: Double) { check(abs(actual - expected) < 0.00001) { "$actual != $expected" } }
private fun point(actual: UiPoint, x: Double, y: Double) { equal(actual.x, x); equal(actual.y, y) }
private fun rect(actual: UiRectangle, left: Double, top: Double, right: Double, bottom: Double) {
    equal(actual.left, left); equal(actual.top, top); equal(actual.right, right); equal(actual.bottom, bottom)
}
private val context = ProjectionContext("owner", "projection")
private fun geometry(logical: Geometry = Geometry(1248, 1664), physical: Geometry = logical,
    density: Int = 416, rotation: Int = 0, disabled: Boolean = false, video: Geometry = physical) =
    UiSourceGeometry(logical, physical, density, rotation, disabled, video)
private fun source(g: UiSourceGeometry = geometry()) = ManagedUiSource(context, "generation", 34, "managed:owned", "selected.app", null, g)
private fun read(windows: Map<Int, List<Window>>, request: UiTargetRequest = UiTargetRequest(source(), "selected.app")): UiTargetSnapshot =
    UiTargetReader(AndroidUiAccess(AccessibilityService(windowsOnAllDisplays = SparseArray(windows))), { 100L }).read(request)

private fun readerScenarios() {
    val duplicate = "selected.app:id/repeated"
    val useful = listOf(Node(viewIdResourceName = duplicate), Node(viewIdResourceName = duplicate),
        Node(viewIdResourceName = null), Node(isClickable = false,
            actionList = listOf(Node.AccessibilityAction.ACTION_PRESS_AND_HOLD)))
    val excludedChild = Node(packageName = "other.app", children = listOf(Node()))
    val children = useful + listOf(Node(isVisibleToUser = false), Node(isEnabled = false),
        Node(screen = Rect(2, 2, 2, 7)), Node(screen = Rect(9, 9, 2, 2)),
        Node(isClickable = false), excludedChild, null)
    val root = Node(isClickable = false, children = children)
    val defaultNode = Node(packageName = "physical.app")
    val defaultWindow = Window(displayId = 0, node = defaultNode)
    val targetWindow = Window(node = root)
    val otherWindow = Window(id = 8, node = Node(packageName = "other.app", windowId = 8))
    val snapshot = read(mapOf(0 to listOf(defaultWindow), 34 to listOf(targetWindow, otherWindow)))
    check(snapshot.status == UiTargetReadStatus.READ && snapshot.request?.source?.context == context)
    check(snapshot.windows[0].targets.size == 4)
    check(snapshot.windows[0].targets.map { it.resourceId } == listOf(duplicate, duplicate, null, null))
    check(snapshot.windows[0].targets.map { it.ordinal }.distinct().size == 4)
    check(snapshot.windows[1].status == UiWindowReadStatus.OTHER_PACKAGE)
    check(defaultWindow.rootsRead == 0 && defaultNode.closed == 0 && excludedChild.childrenRead == 0)
    check(listOf(defaultWindow, targetWindow, otherWindow).all { it.closed == 1 })
    check(root.closed == 1 && children.filterNotNull().all { it.closed == 1 })
    check(snapshot.windows[0].targets.all { it.visible && it.enabled && it.bounds.hasArea })

    check(read(mapOf(0 to listOf(Window(displayId = 0)))).status == UiTargetReadStatus.DISPLAY_ABSENT)
    check(read(mapOf(34 to emptyList()), UiTargetRequest(source(), "selected.app", 99)).status == UiTargetReadStatus.WINDOW_ABSENT)
    val ignored = Window(id = 8, node = Node(windowId = 8))
    val exact = read(mapOf(34 to listOf(Window(), ignored)), UiTargetRequest(source(), "selected.app", 7))
    check(exact.windows.single().status == UiWindowReadStatus.NO_ROOT && ignored.rootsRead == 0 && ignored.closed == 1)
    val mismatched = Node(windowId = 9)
    check(read(mapOf(34 to listOf(Window(node = mismatched)))).windows.single().status == UiWindowReadStatus.WINDOW_MISMATCH)
    check(mismatched.closed == 1)
    val wrongDisplay = Window(displayId = 0, node = Node())
    check(read(mapOf(34 to listOf(wrongDisplay))).windows.single().status == UiWindowReadStatus.WINDOW_MISMATCH)
    check(wrongDisplay.rootsRead == 0)

    val throwing = Node(failBounds = true)
    val parent = Node(isClickable = false, children = listOf(throwing))
    val failure = read(mapOf(34 to listOf(Window(node = parent)))).windows.single()
    check(failure.status == UiWindowReadStatus.FAILED && failure.targets.isEmpty())
    check(failure.failureClass == "java.lang.IllegalStateException" && parent.closed == 1 && throwing.closed == 1)
    val unavailable = AccessibilityService(AccessibilityServiceInfo(flags = 0))
    check(UiTargetReader(AndroidUiAccess(unavailable), { 1 }).read(UiTargetRequest(source(), "selected.app")).status == UiTargetReadStatus.ACCESS_REQUIRED)
    unavailable.serviceInfo = AccessibilityServiceInfo(capabilities = 0)
    check(UiTargetReader(AndroidUiAccess(unavailable), { 1 }).read(UiTargetRequest(source(), "selected.app")).status == UiTargetReadStatus.ACCESS_REQUIRED)

    var deep = Node()
    val nodes = ArrayList<Node>(); nodes.add(deep)
    repeat(12000) { deep = Node(isClickable = false, children = listOf(deep)); nodes.add(deep) }
    check(read(mapOf(34 to listOf(Window(node = deep)))).windows.single().targets.size == 1)
    check(nodes.all { it.closed == 1 })
    println("PASS actual reader + Android adapter: exact display/window, static properties, usable bounds, null/repeated IDs, deep tree, stale failure and owned-handle cleanup")
}

private fun geometryScenarios() {
    // Accepted111 independent View landmarks. The recorded View/node agreement was logical only.
    val fixture = geometry()
    rect(fixture.sourceToVideo(UiBounds(24, 95, 1224, 221)), 24.0, 95.0, 1224.0, 221.0)
    point(fixture.sourceToLayout(UiPoint(24.0, 95.0)), 24.0 / 2.6, 95.0 / 2.6)
    point(fixture.layoutToSource(UiPoint(120.0, 160.0)), 312.0, 416.0)

    // Independent centered frame: physical1000x800, logical400x400 ->800x800 at x100.
    val padded = geometry(Geometry(400, 400), Geometry(1000, 800), density = 320, video = Geometry(500, 400))
    point(padded.sourceToVideo(UiPoint(100.0, 150.0)), 150.0, 150.0)
    point(padded.videoToSource(UiPoint(150.0, 150.0)), 100.0, 150.0)
    check(padded.videoToInput(UiPoint(0.0, 0.0)) == (0f to 0f))
    check(padded.videoToInput(UiPoint(500.0, 400.0)) == (399f to 399f))
    // Source screen bounds include status/inset offset40 and an IME-induced move from y200 to120.
    rect(padded.sourceToVideo(UiBounds(20, 40, 100, 200)), 70.0, 40.0, 150.0, 200.0)
    rect(padded.sourceToVideo(UiBounds(20, 40, 100, 120)), 70.0, 40.0, 150.0, 120.0)
    // Receiver Fit500x400 inside1200x800 ->1000x800 at x100, matching actual Fit.touch.
    rect(padded.sourceToReceiver(UiBounds(20, 40, 100, 120), 1200, 800), 240.0, 80.0, 400.0, 240.0)
    val tap = Fit.inside(1200, 800, padded.video).touch(400f, 300f, padded.video)!!
    point(padded.videoToSource(UiPoint(tap.x.toDouble(), tap.y.toDouble())), 100.0, 150.0)
    point(padded.receiverToSource(UiPoint(400.0, 300.0), 1200, 800), 100.0, 150.0)

    // Physical1200x800; source quarter-width/quarter-height point under each display rotation.
    val expected = listOf(300.0 to 200.0, 900.0 to 200.0, 900.0 to 600.0, 300.0 to 600.0)
    for (rotation in 0..3) {
        val logical = if (rotation % 2 == 0) Geometry(600, 400) else Geometry(400, 600)
        val g = geometry(logical, Geometry(1200, 800), density = 240, rotation = rotation)
        val p = UiPoint(logical.width / 4.0, logical.height / 4.0)
        val (x, y) = expected[rotation]
        point(g.sourceToVideo(p), x, y)
        point(g.videoToSource(UiPoint(x, y)), p.x, p.y)
    }
    val rotated = geometry(Geometry(400, 600), Geometry(1200, 800), rotation = 1)
    rect(rotated.sourceToVideo(UiBounds(50, 100, 150, 200)), 800.0, 100.0, 1000.0, 300.0)
    // Scaling disabled:400x200 at physical frame origin300,300; video is half physical.
    val disabled = geometry(Geometry(400, 200), Geometry(1000, 800), disabled = true, video = Geometry(500, 400))
    point(disabled.sourceToVideo(UiPoint(100.0, 50.0)), 200.0, 175.0)
    point(disabled.videoToSource(UiPoint(200.0, 175.0)), 100.0, 50.0)
    // Layout scales change actual logical pixels, density changes layout normalization only.
    val scaled = geometry(Geometry(800, 800), Geometry(1000, 800), density = 320, video = Geometry(500, 400))
    point(scaled.sourceToVideo(UiPoint(200.0, 300.0)), 150.0, 150.0)
    point(padded.copy(densityDpi = 480).sourceToVideo(UiPoint(100.0, 150.0)), 150.0, 150.0)
    // Integer frame rounding is the injector's truncation, not a floating approximation.
    val rounded = geometry(Geometry(333, 500), Geometry(1000, 800))
    point(rounded.sourceToVideo(UiPoint(0.0, 0.0)), 234.0, 0.0)
    point(rounded.sourceToVideo(UiPoint(333.0, 500.0)), 766.0, 800.0)
    println("PASS independent geometry landmarks: source/layout density, video scale, aspect padding, four rotations, disabled scaling, insets/IME and Receiver Fit/input")
}

private fun providerScenarios() {
    val reader = UiTargetReader(AndroidUiAccess(AccessibilityService()), { 100 })
    var current: ManagedUiSource? = source()
    var calls = 0
    val provider = UiTargetSnapshotProvider({ calls++; current }, { reader }, { 100 })
    check(provider.read(context, "selected.app").status == UiTargetReadStatus.DISPLAY_ABSENT && calls == 2)
    current = source().copy(context = ProjectionContext("other", "session"))
    check(provider.read(context, "selected.app").status == UiTargetReadStatus.SOURCE_UNAVAILABLE)
    current = source()
    check(UiTargetSnapshotProvider({ current }, { null }, { 100 }).read(context, "selected.app").status == UiTargetReadStatus.ACCESS_REQUIRED)
    val failing = UiTargetSnapshotProvider({ throw IllegalStateException("No exception message copied") }, { reader }, { 100 }).read(context, "selected.app")
    check(failing.status == UiTargetReadStatus.FAILED && failing.failureClass == "java.lang.IllegalStateException")
    for (replacement in listOf(source().copy(displayId = 35), source().copy(displayGeneration = "new"),
        source().copy(geometry = geometry(density = 320)), null)) {
        var count = 0
        val changing = UiTargetSnapshotProvider({ if (count++ == 0) source() else replacement }, { reader }, { 100 })
        val result = changing.read(context, "selected.app")
        check(result.status == UiTargetReadStatus.SOURCE_CHANGED && result.windows.isEmpty())
    }
    println("PASS provider correlation: original context, unavailable grant, and changed source/session geometry during read")
}

private fun connectionScenarios() {
    check(UiTargetAccess.reader() == null)
    val original = AccessibilityService()
    UiTargetAccess.connected(original)
    val originalReader = UiTargetAccess.reader()!!
    val replacement = AccessibilityService()
    UiTargetAccess.connected(replacement)
    UiTargetAccess.disconnected(original)
    check(UiTargetAccess.reader() != null && UiTargetAccess.reader() !== originalReader)
    UiTargetAccess.disconnected(replacement)
    check(UiTargetAccess.reader() == null)
    println("PASS exact service connection retirement, including a replacement before original disconnect")
}

private fun serviceScenarios() {
    val runtime = PhoneHidRuntime().also { PhoneHidRuntime.current = it }
    val original = PhoneHidKeyService()
    original.serviceInfo.flags = 0
    original.onServiceConnected()
    check(original.serviceInfo.flags and AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS != 0)
    check(original.serviceInfo.flags and AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS != 0)
    check(original.serviceInfo.flags and AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS != 0)
    check(UiTargetAccess.reader() != null && runtime.connections == listOf(true))
    check(!original.onKeyEvent(android.view.KeyEvent())) // Unassigned/uncaptured keys retain the controller result.
    runtime.controller.acceptResult = true
    check(original.onKeyEvent(android.view.KeyEvent()))
    val beforePin = runtime.controller.events.size
    runtime.pin.ownsRoute = true
    check(!original.onKeyEvent(android.view.KeyEvent()))
    check(runtime.controller.events.size == beforePin && UiTargetAccess.reader() != null)
    original.onInterrupt()
    check(UiTargetAccess.reader() != null)
    val replacement = PhoneHidKeyService()
    replacement.onServiceConnected()
    original.onDestroy()
    check(UiTargetAccess.reader() != null)
    replacement.onUnbind(null)
    check(UiTargetAccess.reader() == null)
    replacement.onDestroy()
    check(UiTargetAccess.reader() == null)
    println("PASS actual HID service: retrieval/view-ID/key flags, modeled key result/pin coexistence, interrupt/unbind/destroy/replacement lifecycle")
}

fun main() { readerScenarios(); geometryScenarios(); providerScenarios(); connectionScenarios(); serviceScenarios() }
