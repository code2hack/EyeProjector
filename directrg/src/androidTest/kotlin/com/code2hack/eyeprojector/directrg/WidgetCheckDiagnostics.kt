package com.code2hack.eyeprojector.directrg

import android.view.View
import org.json.JSONArray
import org.json.JSONObject

/** Fixed, bounded evidence from these owned fixtures only. No event text or exception data. */
internal class WidgetCheckDiagnostics(private val report: JSONObject) {
    enum class Check(val assertion: Boolean = false) {
        ACTIVITY_START, ACTIVITY_IDLE, NATIVE_SETUP, NATIVE_LAYOUT_WAIT(true), NATIVE_LAYOUT_SIZE(true),
        NATIVE_REPLAY, NATIVE_TAP_ACCEPTED(true), NATIVE_CLICK_DRAIN(true), NATIVE_CLICKS(true),
        NATIVE_DOUBLE_TAPS(true), NATIVE_DOWN_INTERVALS(true), NATIVE_NESTED_SCROLL,
        NATIVE_NESTED_MOVED(true), NATIVE_PARENT_UNCHANGED(true), NATIVE_MAIN_SCROLL_DOWN,
        NATIVE_MAIN_DOWN(true), NATIVE_MAIN_SCROLL_UP, NATIVE_MAIN_UP(true), NATIVE_CANCEL,
        HELD_SETUP, HELD_LAYOUT_WAIT(true), HELD_DOWN(true), HELD_LONG_CLICK_WAIT(true),
        HELD_UP(true), HELD_CLICK_DRAIN(true), HELD_NO_ORDINARY_CLICK(true), SLIDER_DOWN(true),
        SLIDER_MOVE(true), SLIDER_UP(true), SLIDER_FINAL_PROGRESS(true), EXPLICIT_DOWN,
        EXPLICIT_CANCEL, REENTRANT_DOWN, REENTRANT_CANCEL(true), LOCAL_INPUT_REPLAY_COMPLETE
    }

    private val details = report.optJSONObject("diagnostics") ?: JSONObject()
        .put("schemaVersion", 1).put("lastEnteredCheck", "NOT_STARTED")
        .put("lastCompletedCheck", "NONE").put("currentCheckCompleted", false)
        .put("completedChecks", JSONArray()).put("completedAssertions", JSONArray())
        .put("completedCheckCounts", JSONObject()).put("observations", JSONObject())
        .also { report.put("diagnostics", it) }

    fun enter(check: Check) {
        details.put("lastEnteredCheck", check.name).put("currentCheckCompleted", false)
    }

    fun completed(check: Check) {
        val counts = details.getJSONObject("completedCheckCounts")
        val count = counts.optInt(check.name)
        if (count == 0) {
            details.getJSONArray("completedChecks").put(check.name)
            if (check.assertion) details.getJSONArray("completedAssertions").put(check.name)
        }
        counts.put(check.name, count + 1)
        details.put("lastCompletedCheck", check.name)
        if (details.getString("lastEnteredCheck") == check.name) details.put("currentCheckCompleted", true)
    }

    // Call sites supply a fixed set of own-fixture scalar/geometry fields. Latest
    // values replace earlier ones; the only observation arrays are capped at8.
    fun observe(vararg fields: Pair<String, Any?>) {
        val observations = details.getJSONObject("observations")
        fields.forEach { (key, value) -> observations.put(key, value ?: JSONObject.NULL) }
    }

    fun geometry(name: String, view: View) = observe(name to JSONObject()
        .put("left", view.left).put("top", view.top).put("width", view.width).put("height", view.height))

    companion object {
        fun heldReport() = JSONObject()
            .put("source", "synthetic edges through LocalPointerDispatcher and Android Views")
            .put("physicalPadPass", false).put("passed", false)
            .also { WidgetCheckDiagnostics(it) }

        fun nativeReport() = JSONObject()
            .put("source", "synthetic timestamped replay through local Android Views")
            .put("physicalPadPass", false).put("passed", false).put("heldWidgets", heldReport())
            .also { WidgetCheckDiagnostics(it) }

        /** Let Instrumentation's sync Runnable finish, then propagate the same check failure. */
        fun mainSync(delegate: (Runnable) -> Unit, task: Runnable) {
            var failure: Exception? = null
            delegate(Runnable { try { task.run() } catch (error: Exception) { failure = error } })
            failure?.let { throw it }
        }
    }
}
