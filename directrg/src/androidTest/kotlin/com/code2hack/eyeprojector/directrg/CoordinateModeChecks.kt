package com.code2hack.eyeprojector.directrg

import android.app.Instrumentation
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import com.code2hack.eyeprojector.protocol.AnchorInteractionMode
import com.code2hack.eyeprojector.protocol.AnchorPosition
import com.code2hack.eyeprojector.protocol.CoordinateModeResult
import com.code2hack.eyeprojector.protocol.Fit
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/** Scripted real transport/input calls; Phone fixture receipts remain independent evidence. */
internal object CoordinateModeChecks {
    fun run(instrumentation: Instrumentation, activity: ReceiverActivity, args: Bundle,
            seconds: Int, report: JSONObject) {
        report.put("scope", "Synthetic canvas input through existing Receiver methods and real Phone transport")
            .put("physicalInputPass", false).put("opticalPass", false)
            .put("originalListRestored", false).put("normalCleanupCompleted", false)
            .put("targetTouchAndBackPass", JSONObject.NULL)
            .put("targetReceiptScope", "Root must compare independent Phone fixture counts before Go and after this case")
        val steps = JSONArray().also { report.put("steps", it) }
        val deadline = SystemClock.elapsedRealtime() + seconds * 1000L
        fun main(action: () -> Unit) = WidgetCheckDiagnostics.mainSync(
            instrumentation::runOnMainSync, Runnable { action() })
        fun field(target: Any, name: String): Any? = target.javaClass.getDeclaredField(name)
            .apply { isAccessible = true }.get(target)
        lateinit var source: ProjectionSession
        lateinit var owner: ReceiverSessionOwner
        lateinit var pointer: PointerOverlay
        lateinit var root: View
        main {
            source = field(activity, "session") as ProjectionSession
            owner = field(activity, "owner") as ReceiverSessionOwner
            pointer = field(activity, "pointer") as PointerOverlay
            root = field(activity, "root") as View
        }
        val geometry = checkNotNull(source.geometry)
        val tap = ReceiverActivity::class.java.getDeclaredMethod("remoteTap",
            Float::class.javaPrimitiveType, Float::class.javaPrimitiveType, Long::class.javaPrimitiveType)
            .apply { isAccessible = true }
        val scroll = ReceiverActivity::class.java.getDeclaredMethod("remoteScroll",
            Float::class.javaPrimitiveType, Float::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            Long::class.javaPrimitiveType, Boolean::class.javaPrimitiveType).apply { isAccessible = true }

        fun json(result: CoordinateModeResult): JSONObject = JSONObject()
            .put("packageName", result.state.packageName ?: JSONObject.NULL)
            .put("mode", result.state.mode.name).put("selectedIndex", result.state.selectedIndex ?: JSONObject.NULL)
            .put("positions", JSONArray().apply {
                result.state.positions.forEach { put(JSONArray(listOf(it.x, it.y))) }
            }).put("consumed", result.consumed).put("failure", result.failure ?: JSONObject.NULL)
        fun snapshot(): CoordinateModeResult {
            lateinit var result: CoordinateModeResult
            main {
                check(field(activity, "session") === source && field(activity, "owner") === owner) {
                    "Original projection replaced"
                }
                check(source.geometry == geometry && activity.projectionContext &&
                    activity.inputSnapshot().getBoolean("streaming")) { "Original projection unavailable" }
                result = owner.coordinateMode
                check(result.failure == null) { "Phone coordinate operation failed" }
                check(field(pointer, "coordinateMode") == result.state) { "Pointer state differs from Phone state" }
                result.state.selectedPosition?.let { point ->
                    val bounds = pointer.visibleBounds
                    val x = bounds.left + point.x.toFloat().coerceIn(0f, (geometry.width - 1).toFloat()) * bounds.width / geometry.width
                    val y = bounds.top + point.y.toFloat().coerceIn(0f, (geometry.height - 1).toFloat()) * bounds.height / geometry.height
                    check(pointer.position.available && abs(pointer.position.x - x) < 0.01f &&
                        abs(pointer.position.y - y) < 0.01f) { "Selected Anchor pointer differs from saved position" }
                }
            }
            return result
        }
        fun await(name: String, matches: (CoordinateModeResult) -> Boolean): CoordinateModeResult {
            report.put("currentStep", name)
            do {
                val result = snapshot()
                report.put("lastObserved", json(result))
                if (matches(result)) {
                    steps.put(json(result).put("step", name).put("observedElapsedMs", SystemClock.elapsedRealtime()))
                    return result
                }
                Thread.sleep(20)
            } while (SystemClock.elapsedRealtime() < deadline)
            error("Coordinate case measurement budget expired at $name")
        }
        val packageName = args.getString("modePackage") ?: "com.code2hack.eyeprojector.hostprobe"
        val initial = await("initial-state") {
            it.state.packageName == packageName
        }
        var expected = initial.state
        val original = expected
        report.put("original", json(initial)).put("initiallyEmpty", original.positions.isEmpty())
            .put("cleanupContract", "On completion remove only the three test additions and leave this fixture app in Normal; failed trials retain their last observation for Root recovery")
        fun step(name: String, action: () -> Unit) {
            report.put("currentStep", name)
            main { action() }
            await(name) { it.state == expected }
        }
        fun cycle(name: String) {
            val next = when (expected.mode) {
                AnchorInteractionMode.NORMAL -> if (expected.positions.isEmpty()) AnchorInteractionMode.ANCHOR_SETTINGS else AnchorInteractionMode.ANCHOR
                AnchorInteractionMode.ANCHOR -> AnchorInteractionMode.ANCHOR_SETTINGS
                AnchorInteractionMode.ANCHOR_SETTINGS -> AnchorInteractionMode.NORMAL
            }
            expected = expected.copy(mode = next, selectedIndex = if (next == AnchorInteractionMode.ANCHOR) 0 else null)
            step(name) { check(source.cycleMode()) { "Mode was not queued" } }
        }
        fun wheel(pixels: Int) {
            val fit = Fit.inside(root.width, root.height, geometry)
            check(scroll.invoke(activity, fit.left + fit.width / 2f, fit.top + fit.height / 2f,
                pixels, SystemClock.uptimeMillis(), false) == true) { "Scroll was not queued" }
        }

        while (expected.mode != AnchorInteractionMode.NORMAL) cycle("prepare-normal-from-${expected.mode.name}")
        cycle("enter-mode")
        if (expected.mode == AnchorInteractionMode.ANCHOR) cycle("enter-settings")
        // These no-op inputs have no unique acknowledgment; the subsequent add is the FIFO boundary.
        main { wheel(64); wheel(-64) }
        for (index in 1..3) {
            var x = 0f; var y = 0f
            lateinit var position: AnchorPosition
            main {
                val fit = Fit.inside(root.width, root.height, geometry)
                x = fit.left + fit.width * index / 4f
                y = fit.top + fit.height * index / 4f
                val mapped = checkNotNull(fit.touch(x, y, geometry))
                position = AnchorPosition(mapped.x.toDouble(), mapped.y.toDouble())
            }
            expected = expected.copy(positions = expected.positions + position)
            step("settings-add-$index") {
                check(tap.invoke(activity, x, y, SystemClock.uptimeMillis()) == true) { "Tap was not queued" }
            }
        }
        cycle("settings-to-normal")
        cycle("anchor-first")
        for ((name, pixels) in listOf("wrap-previous" to -64, "wrap-next" to 64, "next" to 64, "previous" to -64)) {
            expected = expected.copy(selectedIndex = Math.floorMod(checkNotNull(expected.selectedIndex) +
                if (pixels > 0) 1 else -1, expected.positions.size))
            step(name) { wheel(pixels) }
        }
        cycle("anchor-to-settings")
        repeat(3) { index ->
            expected = expected.copy(positions = expected.positions.dropLast(1))
            step("settings-remove-${index + 1}") { activity.contextualBack() }
        }
        if (original.positions.isEmpty()) main { activity.contextualBack() }
        // Normal's changed state is also the FIFO boundary after the optional empty Back.
        cycle("cleanup-normal")
        check(expected == original.copy(mode = AnchorInteractionMode.NORMAL, selectedIndex = null))
        report.put("originalListRestored", true).put("normalCleanupCompleted", true).put("stateAssertionsPassed", true)
            .put("settingsNoOpScope", "Settings Scroll and empty Back require external target effects; later changed state supplies FIFO observation")
    }
}
