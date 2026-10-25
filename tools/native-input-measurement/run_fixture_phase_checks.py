#!/usr/bin/env python3
"""Bounded cached source-extracted fixture-phase checks; no Android, SDK or devices."""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess
import time


FIXTURE = r'''package com.code2hack.eyeprojector.directrg

private object Clock {
    const val ELAPSED_EPOCH = 900_000L
    const val NANO_EPOCH = 7_000_000_000_000L
    var now = 0L
    var interruptAt: Long? = null
    val interrupted = InterruptedException("modeled wait cancellation")
    fun reset() { now = 0; interruptAt = null }
    fun advance(ms: Long) { now += ms }
}
private object SystemClock { fun elapsedRealtime() = Clock.ELAPSED_EPOCH + Clock.now }
private object System { fun nanoTime() = Clock.NANO_EPOCH + Clock.now * 1_000_000L }
private object Thread {
    fun sleep(ms: Long) {
        if (Clock.interruptAt?.let { Clock.now + ms >= it } == true) throw Clock.interrupted
        Clock.advance(ms)
    }
}

/** Token round trips model only the getters/owned writes used by the extracted source, not JSON parsing. */
private object JsonTokens {
    val objects = mutableMapOf<String, JSONObject>()
    var next = 0L
    fun cloneValue(value: Any?): Any? = when (value) {
        is JSONObject -> JSONObject(value.fields.mapValues { cloneValue(it.value) })
        is JSONArray -> JSONArray().also { target -> value.values.forEach { target.put(cloneValue(it)) } }
        else -> value
    }
    fun save(value: JSONObject): String {
        val token = "owned-json-" + ++next
        objects[token] = cloneValue(value) as JSONObject
        return token
    }
}
private class JSONObject {
    companion object { val NULL = Any() }
    val fields = linkedMapOf<String, Any?>()
    constructor()
    constructor(values: Map<*, *>) { values.forEach { (k, v) -> fields[k.toString()] = v ?: NULL } }
    constructor(token: String) {
        val saved = JsonTokens.objects[token] ?: error("Missing or partial fake JSON")
        saved.fields.forEach { (k, v) -> fields[k] = JsonTokens.cloneValue(v) }
    }
    fun put(key: String, value: Any?): JSONObject { fields[key] = value ?: NULL; return this }
    fun has(key: String) = fields.containsKey(key)
    fun get(key: String): Any = fields[key] ?: error("Missing field " + key)
    fun opt(key: String): Any? = fields[key]
    fun getLong(key: String) = (get(key) as Number).toLong()
    fun getInt(key: String) = (get(key) as Number).toInt()
    fun getString(key: String) = get(key) as String
    fun getBoolean(key: String) = get(key) as Boolean
    fun optString(key: String, fallback: String = "") = fields[key]?.takeUnless { it === NULL }?.toString() ?: fallback
    fun optInt(key: String, fallback: Int = 0) = (fields[key] as? Number)?.toInt() ?: fallback
    fun optLong(key: String, fallback: Long = 0) = (fields[key] as? Number)?.toLong() ?: fallback
    override fun toString() = JsonTokens.save(this)
}
private class JSONArray {
    val values = mutableListOf<Any?>()
    fun put(value: Any?): JSONArray { values.add(value); return this }
    fun length() = values.size
}
/** Host serialization of the token-double values, not Android/org.json serialization coverage. */
private fun jsonQuote(value: String): String = "\"" + buildString {
    value.forEach { char -> append(when (char) {
        '"' -> "\\\""
        '\\' -> "\\\\"
        '\n' -> "\\n"
        '\r' -> "\\r"
        '\t' -> "\\t"
        else -> if (char.code < 32) "\\u" + char.code.toString(16).padStart(4, '0') else char.toString()
    }) }
} + "\""
private fun hostJson(value: Any?): String = when {
    value == null || value === JSONObject.NULL -> "null"
    value is String -> jsonQuote(value)
    value is Boolean || value is Number -> value.toString()
    value is JSONObject -> value.fields.entries.joinToString(",", "{", "}") { jsonQuote(it.key) + ":" + hostJson(it.value) }
    value is JSONArray -> value.values.joinToString(",", "[", "]") { hostJson(it) }
    else -> error("Unsupported host sample value")
}
private object World {
    val files = mutableMapOf<String, String>()
    val cleanup = mutableListOf<String>()
    var statusAt: (Long) -> JSONObject? = { null }
    var readCost: (Long) -> Long = { 0L }
    var stageCost: (String) -> Long = { 0L }
    var reads = 0
    var goAt: Long? = null
    var goStatCost = 0L
    var writeFailure: Throwable? = null
    var renameFalse = false
    var activityFinished = false
    var launches = 0
    var idleWaits = 0
    var connectClicks = 0
    fun reset() {
        Clock.reset(); files.clear(); cleanup.clear(); JsonTokens.objects.clear(); JsonTokens.next = 0
        statusAt = { null }; readCost = { 0L }; stageCost = { 0L }; reads = 0
        goAt = null; goStatCost = 0; writeFailure = null; renameFalse = false
        activityFinished = false; launches = 0; idleWaits = 0; connectClicks = 0
    }
}
private class File(val path: String) {
    constructor(parent: File?, name: String) : this(checkNotNull(parent).path + "/" + name)
    val name get() = path.substringAfterLast('/')
    val parentFile: File? get() = if ('/' in path) File(path.substringBeforeLast('/')) else null
    val isFile: Boolean get() {
        if (name.endsWith(".go")) {
            Clock.advance(World.goStatCost)
            return World.goAt?.let { Clock.now >= it } == true
        }
        return World.files.containsKey(path)
    }
    fun createNewFile(): Boolean {
        if (World.files.containsKey(path)) return false
        World.files[path] = ""; return true
    }
    fun readText(): String {
        if (name == "status.json") {
            val at = Clock.now
            World.reads++
            val value = World.statusAt(at)
            Clock.advance(World.readCost(at))
            return value?.toString() ?: error("No current status file")
        }
        return World.files[path] ?: error("Missing owned file")
    }
    fun writeText(value: String) {
        if (name.endsWith(".tmp")) World.writeFailure?.let {
            World.files[path] = "partial"; throw it
        }
        World.files[path] = value
        if (name == "probe-report.json") World.cleanup.add("report-write")
    }
    fun renameTo(destination: File): Boolean {
        if (World.renameFalse) return false
        val content = World.files.remove(path) ?: return false
        World.files[destination.path] = content; return true
    }
    fun length() = (World.files[path]?.length ?: 0).toLong()
}
private class Context { val filesDir = File("/owned") }
private class Activity {
    companion object { const val RESULT_OK = 0; const val RESULT_CANCELED = 1 }
    val window = Window()
    fun finish() { World.activityFinished = true; World.cleanup.add("activity-finish") }
}
private class Window { val decorView = Any() }
private class Button {
    val text = "Connect"
    fun performClick(): Boolean { World.connectClicks++; return true }
}
private class Surface { val isValid = true }
private class Holder { val surface = Surface() }
private class SurfaceView { val holder = Holder() }
private class AudioManager {
    companion object { const val STREAM_MUSIC = 3 }
    fun abandonAudioFocusRequest(value: Any) { World.cleanup.add("audio-focus") }
    fun setStreamVolume(stream: Int, volume: Int, flags: Int) { World.cleanup.add("volume-restore") }
    fun getStreamVolume(stream: Int) = 1
}
private class Bundle { fun putString(key: String, value: String) {} }

@TOP_LEVEL@

private open class DriverBase {
    protected val targetContext = Context()
    protected var freshAfterNs = Clock.NANO_EPOCH
    protected var runStartedNs: Long? = Clock.NANO_EPOCH
    protected var setupReport: JSONObject? = JSONObject().put("passed", false)
    protected val report get() = checkNotNull(setupReport)
    protected var lastStatusObservation: JSONObject? = null
    protected var baselineActivity: Activity? = null
    val stages = mutableListOf<String>()
    protected fun setupStage(stage: String, activity: Activity? = baselineActivity) {
        if (activity != null) baselineActivity = activity
        stages.add(stage); Clock.advance(World.stageCost(stage))
    }
    protected fun runOnMainSync(operation: () -> Unit) { operation() }
    protected fun finish(code: Int, result: Bundle) { World.cleanup.add("instrumentation-finish") }
    protected fun startActivitySync(intent: Any): Activity {
        World.launches++; World.activityFinished = false; return Activity()
    }
    protected fun waitForIdleSync() { World.idleWaits++ }
    protected fun buttons(view: Any) = listOf(Button())
    protected fun surfaces(view: Any) = listOf(SurfaceView())
}
private class CurrentDriver : DriverBase() {
    private var fixtureSetupPhase: FixtureSetupPhase? = null
    private var fixtureWitnessFile: File? = null
    private var fixtureWitnessSignature: String? = null
    private val fixtureWitnessHistory = JSONArray()
    private val fixtureWitnessFailures = JSONArray()
@CURRENT_METHODS@
    fun begin(id: String = "trial") = startFixtureSetup(id)
    fun geometry(phase: FixtureSetupPhase) = waitFixtureGeometry(phase)
    fun go(phase: FixtureSetupPhase, id: String = "trial") = waitFixtureGo(id, phase)
    fun stream(phase: FixtureSetupPhase? = null) = waitStreaming(phase)
    fun publish(stage: String, value: JSONObject? = null, quiet: Boolean = false) =
        publishFixtureSetup(checkNotNull(fixtureSetupPhase), stage, value, quiet)
    fun validatedStatus() = status()
    fun witness() = JSONObject(checkNotNull(fixtureWitnessFile).readText())
    fun failures() = fixtureWitnessFailures.length()
    fun history() = fixtureWitnessHistory.values.map { it as JSONObject }
    fun metadata(key: String) = report.opt(key)
    fun quietFailure() { fixtureSetupPhase?.let @FAILED_CALLBACK@ }
    fun ownedReconnect(phase: FixtureSetupPhase) {
        var activity: Activity? = Activity()
        val fixtureSetup: FixtureSetupPhase? = phase
        val waitForFixture = true
        val intent = Any()
        run @RECONNECT_CALLER@
    }
    fun closeUsingActualFinally(primary: Throwable?) {
        val report = report
        var measurementStarted: Long? = SystemClock.elapsedRealtime()
        var measurementEnded: Long? = null
        var activity: Activity? = Activity()
        val testFocus: Any? = Any()
        val audioManager = AudioManager()
        val priorVolume: Int? = 1
        val ownsRecord = true
        val file: File? = File(targetContext.filesDir, "probe-report.json")
        try { if (primary != null) throw primary }
        finally @ACTUAL_FINALLY@
    }
}
private class ParentDriver : DriverBase() {
@PARENT_METHODS@
    fun geometry() = waitFixtureGeometry()
    fun go(id: String = "trial") @PARENT_GO@
}

private fun media(start: Long, ready: Boolean, state: String = if (ready) "STREAMING" else "CONNECTING",
                  elapsed: Long? = null): JSONObject = JSONObject().put("state", state)
    .put("startedMonoNs", Clock.NANO_EPOCH + start * 1_000_000L)
    .put("elapsedMs", elapsed ?: (Clock.now - start))
    .put("tls", "TLSv1.3").put("peerAddress", "owned-peer").put("localAddress", "owned-local")
    .put("width", if (ready) 480 else 0).put("height", if (ready) 640 else 0).put("touchesSent", 0L)
private fun failure(operation: () -> Unit): Throwable {
    try { operation() } catch (error: Throwable) { return error }
    error("Expected source operation to fail")
}
private var cases = 0
private fun case(name: String, operation: () -> Unit) {
    World.reset(); operation(); cases++; println("PASS fixture-phase " + name)
}
private fun delayedFixture() {
    World.statusAt = { at -> if (at < 13_324) null else media(13_324, at >= 17_000) }
}
private fun checkFixedDeadline(phase: FixtureSetupPhase, start: Long = 0) {
    check(phase.setupStartElapsedMs == Clock.ELAPSED_EPOCH + start)
    check(phase.setupDeadlineElapsedMs == Clock.ELAPSED_EPOCH + start + 180_000)
}
private fun checkPublicationFreshness(driver: CurrentDriver) {
    for (row in driver.history()) {
        if (!row.getBoolean("statusValidated")) {
            check(row.get("status") === JSONObject.NULL) { "UNVALIDATED_WITNESS_MUST_NOT_COPY_STATUS" }
            check(!row.getBoolean("geometryReady")) { "UNVALIDATED_WITNESS_MUST_NOT_AUTHORIZE_GEOMETRY" }
            continue
        }
        val value = row.get("status") as JSONObject
        val rejection = receiverStatusRejection(value.getString("state"),
            diagnosticClockLong(value.opt("startedMonoNs")), diagnosticClockLong(value.opt("elapsedMs")),
            row.getLong("producerRunStartedMonoNs"), row.getLong("observedMonoNs"))
        check(rejection == null) {
            "PUBLICATION_TIME_STATUS_FRESHNESS phase=" + row.getString("phase") + " rejection=" + rejection
        }
        if (row.getBoolean("geometryReady")) {
            check(row.getLong("sessionStartedMonoNs") == value.getLong("startedMonoNs"))
            check(row.getLong("observedElapsedMs") < row.getLong("setupDeadlineElapsedMs"))
        }
    }
}
private fun emitSample(name: String, driver: CurrentDriver) {
    val witness = driver.witness()
    val rawReadStart = System.nanoTime()
    var raw: JSONObject? = null
    var rawError: String? = null
    try { raw = JSONObject(File(File("/owned"), "status.json").readText()) }
    catch (error: Throwable) { rawError = error.javaClass.simpleName }
    val payload = JSONObject().put("name", name).put("witness", witness).put("rawStatus", raw ?: JSONObject.NULL)
        .put("rawStatusReadStartMonoNs", rawReadStart).put("rawStatusReadEndMonoNs", System.nanoTime())
        .put("rawStatusReadErrorType", rawError ?: JSONObject.NULL)
        .put("scope", "Actual extracted publisher values; host serialization of fake JSON/files only; no Android or org.json execution")
    println("SAMPLE_JSON " + hostJson(payload))
}

fun main() {
    case("saved delayed13.324s media gets constructor-relative geometry15 inside shared180") {
        delayedFixture(); val driver = CurrentDriver(); val phase = driver.begin(); driver.geometry(phase)
        check(phase.sessionStartedMonoNs == Clock.NANO_EPOCH + 13_324_000_000L)
        check(Clock.now in 17_000L..17_100L && "FIXTURE_GEOMETRY_READY" in driver.stages)
        checkFixedDeadline(phase)
        val witness = driver.witness()
        check(witness.getBoolean("geometryReady") && witness.getBoolean("statusValidated"))
        check(witness.getString("phase") == "GEOMETRY_READY" && witness.getString("runId") == "trial")
        check(witness.getLong("producerRunStartedMonoNs") == Clock.NANO_EPOCH)
        val status = witness.get("status") as JSONObject
        val read = status.get("driverStatusRead") as JSONObject
        check(witness.getLong("observedMonoNs") >= read.getLong("readNowMonoNs"))
        emitSample("positive", driver)
    }
    case("no fresh session uses one180 with structured failure and no Go") {
        val driver = CurrentDriver(); val phase = driver.begin()
        val error = failure { driver.geometry(phase) }
        check(error.message!!.contains("before fresh current-run media session"))
        check(Clock.now == 180_000L && phase.sessionStartedMonoNs == null)
        driver.quietFailure(); val witness = driver.witness()
        check(witness.getString("phase") == "SETUP_FAILED" && !witness.getBoolean("geometryReady"))
        check(!witness.getBoolean("statusValidated") && witness.get("status") === JSONObject.NULL)
        check("FIXTURE_GO_RECEIVED" !in driver.stages)
    }
    for (kind in listOf("historical", "future", "stale")) case(kind + " status never anchors or becomes ready") {
        val driver = CurrentDriver(); val phase = driver.begin()
        World.statusAt = { _ -> media(0, true).apply {
            when (kind) {
                "historical" -> put("startedMonoNs", Clock.NANO_EPOCH - 1)
                "future" -> put("startedMonoNs", Clock.NANO_EPOCH + 1_000_000_000_000L)
                else -> { put("state", "STREAMING"); put("elapsedMs", 0L) }
            }
        } }
        if (kind == "stale") Clock.advance(5000)
        failure { driver.geometry(phase) }
        check(phase.sessionStartedMonoNs == null && "FIXTURE_GEOMETRY_READY" !in driver.stages)
        check(!driver.witness().getBoolean("geometryReady"))
    }
    for (precursor in listOf("STOPPED", "UNKNOWN")) case(precursor + " precursor does not consume later media15") {
        World.statusAt = { at -> if (at < 13_324) media(0, false, precursor) else media(13_324, at >= 17_000) }
        val driver = CurrentDriver(); val phase = driver.begin(); driver.geometry(phase)
        check(phase.sessionStartedMonoNs == Clock.NANO_EPOCH + 13_324_000_000L)
        check(Clock.now in 17_000L..17_100L && driver.witness().getBoolean("geometryReady"))
    }
    case("elapsedMs2 never grants a fresh15 from first observation") {
        val driver = CurrentDriver(); val phase = driver.begin(); Clock.advance(15_083)
        World.statusAt = { _ -> media(13_324, false, elapsed = if (World.reads == 1) 2L else null) }
        val error = failure { driver.geometry(phase) }
        check(error.message!!.contains("media geometry deadline"))
        check(phase.sessionStartedMonoNs == Clock.NANO_EPOCH + 13_324_000_000L)
        check(Clock.now in 28_324L..28_424L)
    }
    case("late first observation nonready expires immediately using original start") {
        val driver = CurrentDriver(); val phase = driver.begin(); Clock.advance(20_000)
        World.statusAt = { media(0, false) }
        check(failure { driver.geometry(phase) }.message!!.contains("media geometry deadline"))
        check(Clock.now == 20_000L)
    }
    case("first already-valid fresh geometry may shortcut media15") {
        val driver = CurrentDriver(); val phase = driver.begin(); Clock.advance(20_000)
        World.statusAt = { media(0, true) }; driver.geometry(phase)
        check(Clock.now == 20_000L && phase.mediaExpired(System.nanoTime()))
        check(driver.witness().getBoolean("geometryReady"))
    }
    case("replacement neither resets media15 nor authorizes original-session readiness") {
        World.statusAt = { at -> if (at < 10_000) media(0, false) else media(10_000, true) }
        val driver = CurrentDriver(); val phase = driver.begin()
        check(failure { driver.geometry(phase) }.message!!.contains("media geometry deadline"))
        check(Clock.now == 15_000L && phase.sessionStartedMonoNs == Clock.NANO_EPOCH)
        check(driver.history().none { it.getBoolean("geometryReady") })
    }
    case("geometry placement and Go retain one180 deadline") {
        World.statusAt = { media(0, true) }
        val driver = CurrentDriver(); val phase = driver.begin(); driver.geometry(phase)
        Clock.advance(179_500); World.goAt = 180_500
        check(failure { driver.go(phase) }.message!!.contains("shared deadline"))
        check(Clock.now == 180_000L && "FIXTURE_GO_RECEIVED" !in driver.stages)
        checkFixedDeadline(phase)
    }
    case("Go within remaining allowance preserves witness phase and original epoch") {
        World.statusAt = { media(0, true) }
        val driver = CurrentDriver(); val phase = driver.begin(); driver.geometry(phase)
        Clock.advance(170_000); World.goAt = 175_000; driver.go(phase)
        check(Clock.now == 175_000L && driver.witness().getString("phase") == "GO_RECEIVED")
        check(!driver.witness().getBoolean("geometryReady")); checkFixedDeadline(phase)
    }
    case("replacement session during Go never authorizes original-epoch readiness") {
        World.statusAt = { media(0, true) }
        val driver = CurrentDriver(); val phase = driver.begin(); driver.geometry(phase)
        Clock.advance(5000); World.statusAt = { media(5000, true) }; World.goAt = 5000
        failure { driver.go(phase) }
        check(Clock.now == 180_000L && "FIXTURE_GO_RECEIVED" !in driver.stages)
        check(driver.history().filter { it.getString("phase") == "WAIT_GO" }.none { it.getBoolean("geometryReady") })
        check(phase.sessionStartedMonoNs == Clock.NANO_EPOCH)
    }
    for (point in listOf("status-read", "go-stat", "STREAM_READY", "FIXTURE_GEOMETRY_READY", "FIXTURE_GO_RECEIVED"))
        case(point + " crossing180 cannot return ready") {
            World.statusAt = { media(0, true) }
            val driver = CurrentDriver(); val phase = driver.begin()
            if (point != "FIXTURE_GEOMETRY_READY") driver.geometry(phase)
            Clock.advance(179_500)
            World.goAt = 0
            when (point) {
                "status-read" -> World.readCost = { 1000L }
                "go-stat" -> World.goStatCost = 1000
                else -> World.stageCost = { if (it == point) 1000L else 0L }
            }
            failure {
                when (point) {
                    "STREAM_READY" -> driver.stream(phase)
                    "FIXTURE_GEOMETRY_READY" -> driver.geometry(phase)
                    else -> driver.go(phase)
                }
            }
            check(Clock.now >= 180_000)
        }
    case("postGo stream is capped180 while nongated original15 remains") {
        val driver = CurrentDriver(); val phase = driver.begin(); Clock.advance(179_000)
        val error = failure { driver.stream(phase) }
        check(error.message!!.contains("Shared fixture setup deadline") && Clock.now == 180_000L)
        World.reset(); val ordinary = CurrentDriver()
        check(failure { ordinary.stream() }.message == "No rendered stream")
        check(Clock.now == 15_000L)
    }
    case("real source wait cancellation propagates unchanged and never reaches Go") {
        val driver = CurrentDriver(); val phase = driver.begin(); Clock.interruptAt = 3500
        check(failure { driver.geometry(phase) } === Clock.interrupted)
        check(phase.sessionStartedMonoNs == null && "FIXTURE_GO_RECEIVED" !in driver.stages)
    }
    case("witness nullable status and read clocks are honest") {
        val driver = CurrentDriver(); val phase = driver.begin()
        val preparing = driver.witness()
        check(preparing.getString("phase") == "PREPARING" && !preparing.getBoolean("statusValidated"))
        check(preparing.get("status") === JSONObject.NULL && preparing.get("sessionStartedMonoNs") === JSONObject.NULL)
        World.statusAt = { media(0, true) }; World.readCost = { 175L }; driver.geometry(phase)
        val ready = driver.witness(); val value = ready.get("status") as JSONObject
        val observation = value.get("driverStatusRead") as JSONObject
        check(ready.getLong("observedMonoNs") >= observation.getLong("readNowMonoNs"))
        check(ready.getLong("observedElapsedMs") >= Clock.ELAPSED_EPOCH + 175)
        val allowed = setOf("state", "startedMonoNs", "elapsedMs", "tls", "width", "height", "peerAddress",
            "localAddress", "touchesSent", "driverStatusRead")
        check(value.fields.keys.all { it in allowed })
    }
    case("actual bootstrap predicate retains TLS dimensions addresses and no-touch requirements") {
        check(fixtureBootstrapReady("WAITING_FOR_VIDEO", "TLSv1.3", "owned-peer", "owned-local", 480, 640, 0))
        check(!fixtureBootstrapReady("CONNECTING", "TLSv1.3", "owned-peer", "owned-local", 480, 640, 0))
        check(!fixtureBootstrapReady("STREAMING", "unknown", "owned-peer", "owned-local", 480, 640, 0))
        check(!fixtureBootstrapReady("STREAMING", "TLSv1.3", "", "owned-local", 480, 640, 0))
        check(!fixtureBootstrapReady("STREAMING", "TLSv1.3", "owned-peer", "owned-local", 0, 640, 0))
        check(!fixtureBootstrapReady("STREAMING", "TLSv1.3", "owned-peer", "owned-local", 480, 640, 1))
    }
    case("ordinary atomic-write failure keeps canonical witness unchanged and same Throwable") {
        val driver = CurrentDriver(); driver.begin(); val before = World.files["/owned/probe-trial-fixture-setup.json"]
        val original = AssertionError("owned witness write fault"); World.writeFailure = original
        check(failure { driver.publish("WAIT_MEDIA_SESSION") } === original)
        check(World.files["/owned/probe-trial-fixture-setup.json"] == before)
        check(!driver.witness().getBoolean("geometryReady") && driver.failures() == 1)
        check(failure { JSONObject(World.files.getValue("/owned/probe-trial-fixture-setup.json.tmp")) }.message!!.contains("partial"))
    }
    case("rename failure leaves previous canonical witness and no ready phase") {
        val driver = CurrentDriver(); driver.begin(); val before = World.files["/owned/probe-trial-fixture-setup.json"]
        World.renameFalse = true
        check(failure { driver.publish("WAIT_MEDIA_SESSION") }.message == "Fixture setup witness publication failed")
        check(World.files["/owned/probe-trial-fixture-setup.json"] == before && !driver.witness().getBoolean("geometryReady"))
    }
    case("actual quiet failure and finally cannot replace primary or skip later cleanup") {
        val driver = CurrentDriver(); driver.begin(); World.writeFailure = AssertionError("witness export fault")
        driver.quietFailure()
        val original = IllegalStateException("original trial failure")
        check(failure { driver.closeUsingActualFinally(original) } === original)
        check(driver.failures() >= 2)
        check(World.cleanup == listOf("activity-finish", "audio-focus", "volume-restore", "report-write", "instrumentation-finish"))
        check(!driver.witness().getBoolean("geometryReady"))
    }
    case("blocking first geometry snapshot cannot publish old-read status as freshly validated") {
        World.statusAt = { media(0, true) }
        World.stageCost = { if (it == "WAIT_FIXTURE_GEOMETRY") 4000L else 0L }
        val driver = CurrentDriver(); val phase = driver.begin(); driver.geometry(phase)
        check(Clock.now >= 4000 && phase.sessionStartedMonoNs == Clock.NANO_EPOCH)
        check(driver.witness().getBoolean("geometryReady"))
        checkPublicationFreshness(driver)
    }
    case("direct publication rejects status that became stale after its original read") {
        World.statusAt = { media(0, true) }
        val driver = CurrentDriver(); val phase = driver.begin()
        val value = driver.validatedStatus()
        phase.observeSession(value.getLong("startedMonoNs"))
        Clock.advance(3500)
        driver.publish("GEOMETRY_READY", value)
        val witness = driver.witness()
        check(!witness.getBoolean("statusValidated")) { "STALE_READ_CANNOT_REMAIN_STATUS_VALIDATED" }
        check(witness.get("status") === JSONObject.NULL && !witness.getBoolean("geometryReady"))
        check(witness.fields.values.any { it == "STALE_ACTIVE_SESSION_STATUS" }) {
            "PUBLICATION_REJECTION_REASON_MUST_BE_RECORDED"
        }
        checkPublicationFreshness(driver)
        emitSample("late", driver)
    }
    case("blocking Go stat and readiness snapshot cannot publish stale copied status") {
        World.statusAt = { media(0, true) }
        val driver = CurrentDriver(); val phase = driver.begin(); driver.geometry(phase)
        World.goAt = 0; World.goStatCost = 4000
        World.stageCost = { if (it == "FIXTURE_GO_RECEIVED") 4000L else 0L }
        driver.go(phase)
        check(Clock.now >= 8000 && "FIXTURE_GO_RECEIVED" in driver.stages)
        check(driver.witness().getString("phase") == "GO_RECEIVED")
        checkPublicationFreshness(driver)
    }
    case("true unchanged WAIT_GO refreshes clocks on existing poll until Go") {
        World.statusAt = { media(0, true) }
        val driver = CurrentDriver(); val phase = driver.begin(); driver.geometry(phase)
        World.goAt = 500
        driver.go(phase)
        val waiting = driver.history().filter {
            it.getString("phase") == "WAIT_GO" && it.getBoolean("geometryReady")
        }
        check(waiting.size >= 2) { "TRUE_WAIT_GO_MUST_REFRESH_WITHOUT_CORE_STATUS_CHANGE" }
        check(waiting.zipWithNext().all { (a, b) ->
            a.getLong("observedMonoNs") < b.getLong("observedMonoNs") &&
                a.getLong("observedElapsedMs") < b.getLong("observedElapsedMs")
        })
        check(waiting.all { it.getBoolean("statusValidated") })
        checkPublicationFreshness(driver)
        val final = driver.witness()
        check(Clock.now == 500L && final.getString("phase") == "GO_RECEIVED")
        check(!final.getBoolean("geometryReady"))
        checkFixedDeadline(phase)
    }
    case("failure and final publisher samples cannot authorize geometry") {
        World.statusAt = { media(0, true) }
        val driver = CurrentDriver(); val phase = driver.begin(); driver.geometry(phase)
        driver.quietFailure()
        check(driver.witness().getString("phase") == "SETUP_FAILED")
        check(!driver.witness().getBoolean("geometryReady") && !driver.witness().getBoolean("statusValidated"))
        emitSample("failure", driver)
        driver.closeUsingActualFinally(null)
        check(driver.witness().getString("phase") == "SETUP_FINISHED")
        check(!driver.witness().getBoolean("geometryReady") && !driver.witness().getBoolean("statusValidated"))
        emitSample("final", driver)
    }
    case("actual owned reconnect caller gives second media leg its existing15 without resetting180") {
        World.statusAt = { at -> when {
            World.activityFinished -> media(0, false, "STOPPED")
            World.launches == 0 || at < 22_000 -> media(0, true)
            else -> media(22_000, at >= 24_000)
        } }
        val driver = CurrentDriver(); val phase = driver.begin(); driver.geometry(phase)
        check(phase.sessionStartedMonoNs == Clock.NANO_EPOCH)
        Clock.advance(20_000)
        var reconnectFailure: Throwable? = null
        try { driver.ownedReconnect(phase) } catch (error: Throwable) { reconnectFailure = error }
        println("OWNED_RECONNECT_CALLER desiredOutcome=" + (reconnectFailure == null) +
            " atMs=" + Clock.now + " error=" + reconnectFailure?.message)
        check(reconnectFailure == null) { "OWNED_RECONNECT_CALLER_MUST_ACCEPT_EXPLICIT_SECOND_MEDIA_LEG" }
        check(phase.sessionStartedMonoNs == Clock.NANO_EPOCH + 22_000_000_000L)
        check(Clock.now in 24_000L..24_100L && !phase.mediaExpired(System.nanoTime()))
        checkFixedDeadline(phase)
        val prior = driver.metadata("fixturePriorMediaLegSessionStarts") as JSONArray
        check(prior.values == listOf(Clock.NANO_EPOCH) && driver.metadata("fixtureMediaLeg") == 2)
        check(driver.metadata("fixtureOwnedReconnectFreshAfterMonoNs") == Clock.NANO_EPOCH + 21_500_000_000L)
        val readyLegs = driver.history().filter { it.getString("phase") == "GEOMETRY_READY" }
        check(readyLegs.map { it.getLong("sessionStartedMonoNs") } ==
            listOf(Clock.NANO_EPOCH, Clock.NANO_EPOCH + 22_000_000_000L))
        check(driver.history().any { it.getString("phase") == "WAIT_MEDIA_SESSION" &&
            it.get("sessionStartedMonoNs") === JSONObject.NULL && !it.getBoolean("geometryReady") })
        check(World.launches == 1 && World.idleWaits == 1 && World.connectClicks == 1)
        check(World.cleanup == listOf("activity-finish"))
        World.goAt = Clock.now + 500; driver.go(phase)
        check(driver.witness().getString("phase") == "GO_RECEIVED" && Clock.now < 180_000)
        checkFixedDeadline(phase); checkPublicationFreshness(driver)
    }
    case("owned reconnect second leg and late Go retain the first shared180") {
        World.statusAt = { at -> when {
            World.activityFinished -> media(0, false, "STOPPED")
            World.launches == 0 || at < 179_600 -> media(0, true)
            else -> media(179_600, at >= 179_800)
        } }
        val driver = CurrentDriver(); val phase = driver.begin(); driver.geometry(phase)
        Clock.advance(178_000); driver.ownedReconnect(phase)
        check(phase.sessionStartedMonoNs == Clock.NANO_EPOCH + 179_600_000_000L)
        check(Clock.now in 179_800L..179_900L); checkFixedDeadline(phase)
        World.goAt = 180_100
        failure { driver.go(phase) }
        check(Clock.now in 180_000L..180_099L && "FIXTURE_GO_RECEIVED" !in driver.stages)
        checkFixedDeadline(phase)
    }
    case("media-only validated session witness survives before geometry or Go") {
        World.statusAt = { media(0, false) }
        val driver = CurrentDriver(); val phase = driver.begin(); Clock.interruptAt = 100
        check(failure { driver.geometry(phase) } === Clock.interrupted)
        check(Clock.now == 0L && phase.sessionStartedMonoNs == Clock.NANO_EPOCH)
        val witness = driver.witness()
        check(witness.getString("phase") == "WAIT_GEOMETRY" && witness.getBoolean("statusValidated"))
        check(!witness.getBoolean("geometryReady") && witness.getLong("sessionStartedMonoNs") == Clock.NANO_EPOCH)
        checkPublicationFreshness(driver); emitSample("media-only", driver)
    }
    println("PASS current source-extracted fixture phases cases=" + cases +
        "; distinct fake clocks/files/JSON and actual finally only; no Android/Looper/consumer/device proof")
}

object ParentFixtureCounterexample {
    @JvmStatic fun main(args: Array<String>) {
        World.reset(); val driver = ParentDriver()
        when (args.single()) {
            "delayed-media" -> {
                delayedFixture()
                var failed: Throwable? = null
                try { driver.geometry() } catch (error: Throwable) { failed = error }
                val desired = failed == null && Clock.now < 180_000
                println("PARENT_DELAYED_MEDIA desiredOutcome=" + desired + " atMs=" + Clock.now + " error=" + failed?.message)
                check(desired) { "PARENT_DELAYED_MEDIA_COUNTEREXAMPLE" }
            }
            "shared-go" -> {
                World.statusAt = { media(0, true) }; driver.geometry()
                Clock.advance(179_500); World.goAt = 180_500
                var failed: Throwable? = null
                try { driver.go() } catch (error: Throwable) { failed = error }
                val desired = failed != null && Clock.now <= 180_000
                println("PARENT_SHARED_GO desiredOutcome=" + desired + " atMs=" + Clock.now + " error=" + failed?.message)
                check(desired) { "PARENT_SHARED_GO_COUNTEREXAMPLE" }
            }
            else -> error("Unknown parent case")
        }
    }
}
'''


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output-dir', type=Path, required=True)
    parser.add_argument('--parent-driver', type=Path, required=True)
    parser.add_argument('--driver-source', type=Path,
                        help='Explicit preserved fixture-phase source to exercise as the current subject')
    parser.add_argument('--contract', type=Path)
    parser.add_argument('--witness', type=Path)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    output = args.output_dir.resolve(); output.mkdir(parents=True, exist_ok=False)
    adapter_path = root/'directrg/src/test/adapter/run.py'
    spec = importlib.util.spec_from_file_location('fixture_source_parser', adapter_path)
    adapter = importlib.util.module_from_spec(spec); spec.loader.exec_module(adapter)
    directory = root/'directrg/src/androidTest/kotlin/com/code2hack/eyeprojector/directrg'
    driver = args.driver_source.resolve() if args.driver_source else directory/'ProjectionInstrumentation.kt'
    text, parent = driver.read_text(), args.parent_driver.read_text()
    def method(source, name):
        pattern = r'^    private fun ' + name + r'\b'
        match = re.search(pattern, source, re.M)
        if match is None: raise ValueError('Missing actual method '+name)
        # Preserve a complete block- OR expression-bodied declaration, including its inferred
        # or explicit Unit/Boolean return type. The next class-level member marks its end.
        following = re.search(r'^    (?:private |internal |override |fun |class |companion )',
                              source[match.end():], re.M)
        if following is None: raise ValueError('Missing following member after '+name)
        end = match.end()+following.start()
        return source[match.start():end].rstrip()
    top_start = text.index('internal fun fixtureBootstrapReady(')
    top_end = text.index('/** Explicit bounded', top_start)
    top = text[top_start:top_end]
    parent_top = parent[parent.index('internal fun fixtureBootstrapReady('):parent.index('/** Explicit bounded')]
    if not top.startswith(parent_top.rstrip()):
        parser.error('Parent/current bootstrap predicate differs; do not substitute current logic for the parent')
    method_names = ['startFixtureSetup', 'publishFixtureSetup', 'status', 'clickConnect',
                    'waitFixtureGeometry', 'waitFixtureGo', 'waitStreaming']
    method_names += [name for name in ['fixtureStatusRejection', 'fixtureGeometryReady', 'beginOwnedReconnectLeg']
                     if re.search(r'^    private fun '+name+r'\b', text, re.M)]
    methods = '\n'.join(method(text, name) for name in method_names)
    parent_methods = '\n'.join(method(parent, name) for name in ['status', 'waitFixtureGeometry', 'waitStreaming'])
    parent_go = adapter.activity_lambda(parent,
        r'if \(waitForFixture\)\s*(?=\{\s*val go = File\(targetContext.filesDir, "probe-\$id.go"\))', 'parent original Go block')
    actual_finally = adapter.activity_lambda(text,
        r'finally\s*(?=\{\s*fixtureSetupPhase\?\.let)', 'actual trial finally')
    failed = adapter.activity_lambda(text,
        r'fixtureSetupPhase\?\.let\s*(?=\{\s*publishFixtureSetup\(it, "SETUP_FAILED")', 'actual quiet failure publication')
    reconnect_caller = adapter.activity_lambda(text, r'if \(operation == "reconnect"\)\s*',
                                               'actual explicitly owned reconnect caller')
    def measurement(source):
        start = source.index('                    val start = SystemClock.elapsedRealtime()')
        end = source.index('                    if (operation == "audio-volume")', start)
        return source[start:end]
    current_measurement, parent_measurement = measurement(text), measurement(parent)
    if current_measurement != parent_measurement:
        parser.error('Ordinary measurement body changed from parent; source equality assertion must be revisited')
    if text.count('startFixtureSetup(id)') != 1:
        parser.error('Shared setup allowance must have one actual call site')
    start_setup = text.index('startFixtureSetup(id)')
    launch = text.index('activity = startActivitySync(intent)', start_setup)
    if launch <= start_setup: parser.error('Fixture deadline is not established before owned Activity launch')
    generated = output/'FixturePhaseChecks.kt'
    content = FIXTURE
    for token, value in {'@TOP_LEVEL@':top, '@CURRENT_METHODS@':methods, '@PARENT_METHODS@':parent_methods,
                         '@PARENT_GO@':parent_go, '@FAILED_CALLBACK@':failed, '@ACTUAL_FINALLY@':actual_finally,
                         '@RECONNECT_CALLER@':reconnect_caller}.items():
        content = content.replace(token, value)
    generated.write_text(content)
    cache = Path.home()/'.gradle/caches/modules-2/files-2.1'
    deps = [('org.jetbrains.kotlin','kotlin-compiler-embeddable','2.2.21'),
            ('org.jetbrains.kotlin','kotlin-stdlib','2.2.21'),('org.jetbrains.kotlin','kotlin-script-runtime','2.2.21'),
            ('org.jetbrains.kotlin','kotlin-reflect','1.6.10'),('org.jetbrains.intellij.deps','trove4j','1.0.20200330'),
            ('org.jetbrains.kotlinx','kotlinx-coroutines-core-jvm','1.8.0'),('org.jetbrains','annotations','13.0')]
    jars = []
    for group, artifact, version in deps:
        matches = list((cache/group/artifact/version).glob('*/'+artifact+'-'+version+'.jar'))
        if len(matches) != 1: parser.error('Missing/ambiguous cached dependency '+artifact+'; no downloads')
        jars.append(matches[0])
    java = str(Path(os.environ['JAVA_HOME'])/'bin/java') if os.environ.get('JAVA_HOME') else 'java'
    cp = os.pathsep.join(map(str, jars)); jar = output/'fixture-phase-checks.jar'
    sources = [directory/'ReceiverStartupDiagnostics.kt', generated]
    compile_command = [java,'-Xmx512m','-cp',cp,'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
        '-no-stdlib','-no-reflect','-jvm-target','17','-classpath',cp,'-d',str(jar)]+list(map(str,sources))
    prefix = [java,'-Xmx256m','-cp',str(jar)+os.pathsep+cp]
    current_command = prefix+['com.code2hack.eyeprojector.directrg.FixturePhaseChecksKt']
    inputs = [driver, args.parent_driver, adapter_path, Path(__file__)]+sources
    inputs += [p for p in [args.contract, args.witness] if p is not None]
    sha = lambda p: hashlib.sha256(p.read_bytes()).hexdigest()
    result = {'scope':'Current/parent exact extracted wait/status/bootstrap/publisher/finally methods with fake '
              'distinct clocks and owned token JSON/files; actual diagnostic predicate compiled. No Android, Looper, '
              'vendor, device or Worker62 consumer/release/future proof; ordinary measurement body source-identical.',
        'inputs':[{'path':str(p),'sha256':sha(p)} for p in inputs],
        'dependencies':[{'path':str(p),'sha256':sha(p)} for p in jars],
        'measurementBodySha256':hashlib.sha256(current_measurement.encode()).hexdigest(),
        'ownedReconnectCallerSha256':hashlib.sha256(reconnect_caller.encode()).hexdigest(),
        'subjectDriver':{'path':str(driver),'sha256':sha(driver),'extractedMethods':method_names},
        'commands':{'compile':compile_command,'current':current_command},'parentComparisons':{}}
    result_file = output/'result.json'
    def save(): result_file.write_text(json.dumps(result,indent=2)+'\n')
    deadline = time.monotonic()+115
    def run(command, filename, limit):
        with (output/filename).open('w') as log:
            remaining = max(.1, deadline-time.monotonic())
            try:
                return subprocess.run(command,stdout=log,stderr=subprocess.STDOUT,timeout=min(limit,remaining)).returncode, False
            except subprocess.TimeoutExpired:
                log.write('\nHOST_FIXTURE_CHECK_TIMEOUT\n'); return 124, True
    save()
    result['compileExit'], result['compileTimedOut'] = run(compile_command,'compile.log',45); save()
    if result['compileExit'] == 0:
        for case, marker in [('delayed-media','PARENT_DELAYED_MEDIA'),('shared-go','PARENT_SHARED_GO')]:
            command = prefix+['com.code2hack.eyeprojector.directrg.ParentFixtureCounterexample',case]
            result['commands'][case] = command
            filename='parent-'+case+'-red.log'; code,timed_out=run(command,filename,15)
            log=(output/filename).read_text()
            result['parentComparisons'][case]={'exitCode':code,'timedOut':timed_out,
                'expectedRed':code != 0 and not timed_out and marker+' desiredOutcome=false' in log and marker+'_COUNTEREXAMPLE' in log}
            save()
        result['currentExit'], result['currentTimedOut'] = run(current_command,'current-green.log',30); save()
        sample_dir = output/'samples'; sample_dir.mkdir()
        sample_rows = []
        sample_names = set()
        sample_errors = []
        for line in (output/'current-green.log').read_text().splitlines():
            if not line.startswith('SAMPLE_JSON '): continue
            try:
                payload = json.loads(line[len('SAMPLE_JSON '):])
                name = payload['name']
                if name not in {'positive','late','failure','final','media-only'} or name in sample_names:
                    raise ValueError('Unexpected or duplicate sample name')
                if not isinstance(payload['witness'], dict):
                    raise ValueError('Sample witness must be an object')
                sample_names.add(name)
                witness_file = sample_dir/(name+'-witness.json')
                witness_file.write_text(json.dumps(payload['witness'],indent=2)+'\n')
                raw_file = None
                if isinstance(payload.get('rawStatus'), dict):
                    raw_file = sample_dir/(name+'-raw-status.json')
                    raw_file.write_text(json.dumps(payload['rawStatus'],indent=2)+'\n')
                def sample_ref(path):
                    return {'path':str(path),'sha256':sha(path),'bytes':path.stat().st_size}
                sample_rows.append({'name':name,'witness':sample_ref(witness_file),
                    'rawStatus':sample_ref(raw_file) if raw_file else None,
                    'rawStatusReadStartMonoNs':payload.get('rawStatusReadStartMonoNs'),
                    'rawStatusReadEndMonoNs':payload.get('rawStatusReadEndMonoNs'),
                    'rawStatusReadErrorType':payload.get('rawStatusReadErrorType'),
                    'scope':payload.get('scope')})
            except (KeyError, TypeError, ValueError) as error:
                sample_errors.append({'errorClass':type(error).__name__,'errorMessage':str(error)})
        sample_complete = sample_names == {'positive','late','failure','final','media-only'} and not sample_errors
        manifest = {'schema':1,
            'scope':'Samples from actual extracted publisher execution using host token JSON/file doubles; '
                    'real JSON emitted by the host serializer, not Android/org.json serialization or device evidence',
            'subjectDriver':result['subjectDriver'],
            'generatedFixture':{'path':str(generated),'sha256':sha(generated)},
            'authoritativeStatusPredicate':{'path':str(sources[0]),'sha256':sha(sources[0])},
            'sampleSetComplete':sample_complete,'subjectChecksExit':result['currentExit'],
            'samples':sample_rows,'errors':sample_errors}
        manifest_file = sample_dir/'manifest.json'
        manifest_file.write_text(json.dumps(manifest,indent=2)+'\n')
        result['sampleManifest'] = {'path':str(manifest_file),'sha256':sha(manifest_file),
                                    'sampleSetComplete':sample_complete,'samples':len(sample_rows)}
        save()
    for filename in ['compile.log','parent-delayed-media-red.log','parent-shared-go-red.log','current-green.log']:
        if (output/filename).exists(): print((output/filename).read_text(),end='')
    expected = len(result['parentComparisons']) == 2 and all(x['expectedRed'] for x in result['parentComparisons'].values())
    samples_ok = result.get('sampleManifest',{}).get('sampleSetComplete') is True
    raise SystemExit(result['compileExit'] or result.get('currentExit',1) or (0 if expected and samples_ok else 1))


if __name__ == '__main__': main()
