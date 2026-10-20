package com.code2hack.eyeprojector.directrg

import widgethost.Host
import org.json.JSONObject
import java.lang.reflect.InvocationTargetException

private fun diagnostic(report: JSONObject) = report.getJSONObject("diagnostics")
private fun assertIncomplete(report: JSONObject, name: String) {
    val detail = diagnostic(report)
    check(detail.getString("lastEnteredCheck") == name) { "Expected $name, got $detail" }
    check(!detail.getBoolean("currentCheckCompleted"))
}

fun main() {
    var failures = 0
    fun scenario(name: String, run: () -> Unit) {
        try { run(); println("PASS $name") }
        catch (error: Throwable) { failures++; println("FAIL $name: ${error.javaClass.simpleName}: ${error.message}") }
    }
    scenario("happy-fixture-control-flow") {
        Host.reset("none")
        val report = ExtractedLocalReplayDriver().run()
        check(report.getBoolean("passed")) { "Fixture did not complete: $report" }
        val native = report.getJSONObject("localWidgets")
        check(native.getInt("nativeButtonClicks") == 3)
        check(native.getInt("nativeDoubleTaps") == 1)
        val held = native.getJSONObject("heldWidgets")
        check(held.getBoolean("stationaryLongClick"))
        check(held.getInt("reentrantCancels") == 1)
        check(Host.escapedMainFailure == null)
    }
    scenario("successful-fixtures-retain-counts-and-deduplicated-checkpoints") {
        Host.reset("none")
        val report = ExtractedLocalReplayDriver().run()
        check(report.getBoolean("passed"))
        val native = report.getJSONObject("localWidgets")
        val detail = diagnostic(native)
        check(detail.getString("lastCompletedCheck") == "LOCAL_INPUT_REPLAY_COMPLETE")
        check(detail.getJSONObject("completedCheckCounts").getInt("NATIVE_TAP_ACCEPTED") == 3)
        val completed = detail.getJSONArray("completedChecks")
        check((0 until completed.length()).count { completed.get(it) == "NATIVE_TAP_ACCEPTED" } == 1)
        check(completed.length() <= 40)
    }
    scenario("main-setup-failure-reaches-final-catch-and-keeps-child") {
        Host.reset("native-setup")
        val report = ExtractedLocalReplayDriver().run()
        check(!report.getBoolean("passed"))
        check(Host.escapedMainFailure == null) { "UI callback escaped rather than transferring to caller" }
        check(report.optString("errorType") == "IllegalStateException")
        val native = report.optJSONObject("localWidgets") ?: error("No pre-attached native partial report")
        assertIncomplete(native, "NATIVE_SETUP")
        check(!report.toString().contains("sentinel sensitive setup text")) { "Report leaked raw exception message" }
    }
    scenario("actual-layout-wait-failure-keeps-native-and-held-partials") {
        Host.reset("native-layout")
        val begin = System.nanoTime()
        val report = ExtractedLocalReplayDriver().run()
        val millis = (System.nanoTime() - begin) / 1_000_000
        check(!report.getBoolean("passed"))
        check(millis >= 2900 && millis < 6000) { "Unexpected unchanged3s latch wait: $millis" }
        val native = report.optJSONObject("localWidgets") ?: error("No native partial report")
        check(native.optJSONObject("heldWidgets") != null) { "No pre-attached held partial report" }
        assertIncomplete(native, "NATIVE_LAYOUT_WAIT")
        check(!diagnostic(native).getJSONObject("observations").getBoolean("layoutLatchSignaled"))
        check(Host.nativeTouchCount == 0) { "Input ran after actual layout timeout" }
    }
    scenario("held-down-failure-retains-completed-native-metrics") {
        Host.reset("held-down")
        val report = ExtractedLocalReplayDriver().run()
        check(!report.getBoolean("passed"))
        check(Host.escapedMainFailure == null)
        val native = report.optJSONObject("localWidgets") ?: error("Completed native report discarded")
        check(native.getInt("nativeButtonClicks") == 3)
        check(native.getInt("nativeDoubleTaps") == 1)
        check(native.getInt("mainAfterDown") > native.getInt("mainAfterUp"))
        check(native.optJSONObject("heldWidgets") != null) { "Held partial report discarded" }
        check(diagnostic(native).getString("lastCompletedCheck") == "NATIVE_CANCEL")
        assertIncomplete(native.getJSONObject("heldWidgets"), "HELD_DOWN")
        check(report.optString("errorType") == "IllegalStateException")
        check(!report.toString().contains("Stationary continuous DOWN"))
        check(Host.nativeTouchCount == 6) { "Native events retried or skipped: ${Host.nativeTouchCount}" }
        check(Host.heldTouchCount == 1) { "Held input retried after failure: ${Host.heldTouchCount}" }
    }
    scenario("geometry-is-retained-before-main-check-fails") {
        Host.reset("native-geometry")
        val report = ExtractedLocalReplayDriver().run()
        check(!report.getBoolean("passed"))
        check(Host.escapedMainFailure == null)
        val native = report.getJSONObject("localWidgets")
        assertIncomplete(native, "NATIVE_LAYOUT_SIZE")
        val observations = diagnostic(native).getJSONObject("observations")
        check(observations.getJSONObject("root").getInt("width") == 0)
        check(observations.getJSONObject("root").getInt("height") == 640)
        check(observations.getJSONObject("nested").getInt("height") == 180)
        check(Host.nativeTouchCount == 0)
    }
    scenario("unexpected-extra-callbacks-retain-bounded-counts-before-assertion") {
        Host.reset("native-extra-downs")
        val report = ExtractedLocalReplayDriver().run()
        check(!report.getBoolean("passed"))
        val native = report.getJSONObject("localWidgets")
        assertIncomplete(native, "NATIVE_DOWN_INTERVALS")
        val observations = diagnostic(native).getJSONObject("observations")
        check(observations.getInt("clicks") == 3)
        check(observations.getInt("doubleTaps") == 1)
        check(observations.getJSONArray("downTimesMs").length() == 8)
        check(observations.getInt("downTimeCount") == 12)
        check(observations.getBoolean("downTimesTruncated"))
        check(!native.has("downTimesMs")) { "Failed interval assertion published an uncapped success field" }
    }
    scenario("activity-start-failure-retains-initial-report-and-six-class-limit") {
        Host.reset("activity-start")
        val report = ExtractedLocalReplayDriver().run()
        check(!report.getBoolean("passed"))
        val native = report.getJSONObject("localWidgets")
        assertIncomplete(native, "ACTIVITY_START")
        check(native.getJSONObject("heldWidgets").getBoolean("physicalPadPass") == false)
        check(report.getJSONArray("causeClasses").length() == 6)
        check(report.optString("errorType") == "IllegalStateException")
        check(!report.toString().contains("sensitive"))
        check(Host.fixture == 0)
    }
    scenario("main-sync-transfers-the-same-exception-without-ui-escape") {
        Host.reset("none")
        val sentinel = IllegalStateException("sensitive identity sentinel")
        val clazz = Class.forName("com.code2hack.eyeprojector.directrg.WidgetCheckDiagnostics")
        val companion = clazz.getDeclaredField("Companion").get(null)
        val method = companion.javaClass.getMethod("mainSync", kotlin.jvm.functions.Function1::class.java, Runnable::class.java)
        var received: Throwable? = null
        try { method.invoke(companion, { task: Runnable -> Host.main(task) }, Runnable { throw sentinel }) }
        catch (wrapped: InvocationTargetException) { received = wrapped.targetException }
        check(received === sentinel)
        check(Host.escapedMainFailure == null)
    }
    if (failures != 0) error("$failures diagnostic regression(s) failed")
}
