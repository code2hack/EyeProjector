package com.code2hack.eyeprojector.directrg

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import android.os.SystemClock
import com.code2hack.eyeprojector.protocol.Geometry
import com.code2hack.eyeprojector.protocol.LayoutScale
import com.code2hack.eyeprojector.protocol.ScaleState
import com.code2hack.eyeprojector.protocol.ScaleStatus
import com.code2hack.eyeprojector.protocol.Tap
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * Test-only debugger coordination. Phases use 1-based probe-RUN-scale-N.json/.input names.
 * A host supplies complete x,y,width,height input files measured from the owned Phone fixture.
 */
internal fun runLiveScaleProbe(
    instrumentation: Instrumentation,
    activity: Activity,
    args: Bundle,
    report: JSONObject,
    runId: String,
) {
    val detail = JSONObject().put("scope", "Same-session requests and backend observations; no optical or target-hit claim")
    val phases = JSONArray()
    detail.put("phases", phases)
    report.put("liveScale", detail)
    val directory = instrumentation.targetContext.filesDir
    val sessionField = ReceiverActivity::class.java.getDeclaredField("session").apply { isAccessible = true }
    var bound: ProjectionSession? = null
    instrumentation.runOnMainSync { bound = sessionField.get(activity) as? ProjectionSession }
    val source = checkNotNull(bound) { "No connected projection session" }
    val initial = JSONObject(File(directory, "status.json").readText())
    val started = initial.getLong("startedMonoNs")

    fun status(): JSONObject {
        instrumentation.runOnMainSync { check(sessionField.get(activity) === source) { "Projection session replaced" } }
        return JSONObject(File(directory, "status.json").readText()).also {
            check(started > 0 && it.getLong("startedMonoNs") == started) { "Different session telemetry" }
            val age = System.nanoTime() - started - it.getLong("elapsedMs") * 1_000_000
            check(age in 0..3_000_000_000L) { "Stale session telemetry" }
            check(it.getString("state") in listOf("STREAMING", "WAITING_FOR_VIDEO")) { "Projection stopped" }
        }
    }
    fun geometry(value: Geometry?): Any = value?.let {
        JSONObject().put("width", it.width).put("height", it.height)
    } ?: JSONObject.NULL
    fun scale(value: ScaleState?): Any = value?.let {
        JSONObject().put("requestId", it.requestId)
            .put("requested", if (it.requested.isFinite()) it.requested else JSONObject.NULL)
            .put("target", geometry(it.target)).put("observed", geometry(it.observed))
            .put("status", it.status.name).put("pending", it.status == ScaleStatus.PENDING)
            .put("clientResize", it.clientResize)
            .put("applied", it.observed?.let(LayoutScale::applied) ?: JSONObject.NULL)
            .put("appliedX", it.observed?.let(LayoutScale::appliedX) ?: JSONObject.NULL)
            .put("appliedY", it.observed?.let(LayoutScale::appliedY) ?: JSONObject.NULL)
    } ?: JSONObject.NULL

    try {
        detail.put("before", status())
        val values = (args.getString("scales") ?: "1.25,1.5,0.75,1.0").split(',').map { it.trim().toDouble() }
        val waitForInput = args.getString("waitForScaleInput") == "true"
        for ((offset, requested) in values.withIndex()) {
            val index = offset + 1
            val file = File(directory, "probe-$runId-scale-$index.json")
            val phase = JSONObject().put("index", index)
                .put("requested", if (requested.isFinite()) requested else JSONObject.NULL)
                .put("requestText", requested.toString()).put("file", file.name)
                .put("observationConfirmed", false)
            phases.put(phase)
            check(file.createNewFile()) { "Existing scale phase record" }
            try {
                phase.put("before", status()).put("beforeScale", scale(source.scaleState))
                    .put("target", geometry(LayoutScale.target(requested, source.geometry?.let { it.width > it.height } == true)))
                val prior = source.scaleState
                val requestId = System.nanoTime()
                var accepted = false
                instrumentation.runOnMainSync {
                    check(sessionField.get(activity) === source) { "Projection session replaced" }
                    accepted = source.requestScale(requested, requestId)
                }
                phase.put("requestId", requestId).put("locallyAccepted", accepted)
                file.writeText(phase.toString())
                val deadline = SystemClock.elapsedRealtime() + 6000
                var observed = false
                while (accepted && SystemClock.elapsedRealtime() < deadline) {
                    status()
                    val next = source.scaleState
                    if (next !== prior && next?.requestId == requestId && next.observed != null &&
                        next.status in listOf(ScaleStatus.OBSERVED, ScaleStatus.INTERRUPTED)) {
                        observed = true
                        break
                    }
                    if (next?.requestId == requestId && next.status in listOf(ScaleStatus.REJECTED, ScaleStatus.UNAVAILABLE)) break
                    Thread.sleep(100)
                }
                phase.put("observationConfirmed", observed).put("afterScale", scale(source.scaleState))
                    .put("currentGeometry", geometry(source.geometry)).put("after", status())
                    .put("observationScope", "A fresh backend sample is not an acknowledgment of an exact requested target")
                if (!observed) phase.put("unconfirmed", "No new backend observation; a codec-rounded no-op may emit no event")
                file.writeText(phase.toString())

                if (waitForInput) {
                    val input = File(directory, "probe-$runId-scale-$index.input")
                    phase.put("inputFile", input.name).put("waitingForInput", true)
                    file.writeText(phase.toString())
                    val inputDeadline = SystemClock.elapsedRealtime() + 45_000
                    while (!input.isFile && SystemClock.elapsedRealtime() < inputDeadline) {
                        status()
                        Thread.sleep(100)
                    }
                    phase.put("waitingForInput", false).put("inputFileArrived", input.isFile)
                    if (input.isFile) {
                        val parts = input.readText().trim().split(',').map { it.trim().toInt() }
                        require(parts.size == 4) { "Expected x,y,width,height" }
                        val tap = Tap(parts[0], parts[1], Geometry(parts[2], parts[3]))
                        val beforeTap = status().getLong("touchesSent")
                        var submitted = false
                        instrumentation.runOnMainSync {
                            check(sessionField.get(activity) === source) { "Projection session replaced" }
                            submitted = source.tap(tap)
                        }
                        phase.put("input", JSONObject().put("x", tap.x).put("y", tap.y)
                            .put("geometry", geometry(tap.geometry))).put("tapLocallyAccepted", submitted)
                        val touchDeadline = SystemClock.elapsedRealtime() + 6000
                        var afterTap = status()
                        while (submitted && afterTap.getLong("touchesSent") == beforeTap && SystemClock.elapsedRealtime() < touchDeadline) {
                            Thread.sleep(100)
                            afterTap = status()
                        }
                        phase.put("touchesSentBefore", beforeTap).put("touchesSentAfter", afterTap.getLong("touchesSent"))
                            .put("touchesSentDelta", afterTap.getLong("touchesSent") - beforeTap)
                            .put("tapScope", "Receiver telemetry counts transport submission; Phone fixture target-hit evidence is external")
                    } else phase.put("inputUnconfirmed", "Coordinate file did not arrive within the test bound")
                }
                phase.put("finalScale", scale(source.scaleState)).put("final", status())
            } catch (error: Exception) {
                phase.put("errorType", error.javaClass.simpleName)
                runCatching { phase.put("failureStatus", status()) }
                throw error
            } finally {
                file.writeText(phase.toString())
            }
        }
        detail.put("after", status()).put("sameSession", true)
            .put("allRequestsLocallyAccepted", (0 until phases.length()).all { phases.getJSONObject(it).optBoolean("locallyAccepted") })
            .put("allObservationsConfirmed", (0 until phases.length()).all { phases.getJSONObject(it).optBoolean("observationConfirmed") })
    } catch (error: Exception) {
        detail.put("errorType", error.javaClass.simpleName)
        throw error
    }
}
