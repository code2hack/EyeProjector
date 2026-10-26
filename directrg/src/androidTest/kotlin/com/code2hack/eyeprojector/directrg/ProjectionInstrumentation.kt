package com.code2hack.eyeprojector.directrg

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import com.code2hack.eyeprojector.protocol.Tls
import com.code2hack.eyeprojector.protocol.Wire
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import org.json.JSONArray
import org.json.JSONObject

internal fun fixtureBootstrapReady(
    state: String, tls: String?, peerAddress: String?, localAddress: String?, width: Int, height: Int, touchesSent: Long,
): Boolean = state in listOf("WAITING_FOR_VIDEO", "STREAMING") && tls in listOf("TLSv1.2", "TLSv1.3") &&
    !peerAddress.isNullOrBlank() && !localAddress.isNullOrBlank() && width in 1..4096 && height in 1..4096 && touchesSent == 0L

/** Test setup only: one existing elapsed-time allowance, with a per-leg constructor nanoTime age. */
internal class FixtureSetupPhase(val runId: String, val producerRunStartedMonoNs: Long, val setupStartElapsedMs: Long) {
    val setupDeadlineElapsedMs = setupStartElapsedMs + 180_000
    var sessionStartedMonoNs: Long? = null; private set
    val priorMediaLegSessionStarts = mutableListOf<Long?>()
    fun observeSession(startedMonoNs: Long) { if (sessionStartedMonoNs == null) sessionStartedMonoNs = startedMonoNs }
    fun mediaExpired(nowMonoNs: Long): Boolean = sessionStartedMonoNs?.let { nowMonoNs - it >= 15_000_000_000L } ?: false
    fun beginOwnedReconnectLeg() {
        priorMediaLegSessionStarts.add(sessionStartedMonoNs)
        sessionStartedMonoNs = null
    }
}

/** Explicit bounded on-RG test driver. No input/media loop runs on a computer. */
class ProjectionInstrumentation : Instrumentation() {
    private lateinit var args: Bundle
    private var freshAfterNs = 0L
    private var runStartedNs: Long? = null
    private var setupReport: JSONObject? = null
    private val setupStages = JSONArray()
    private var currentSetupStage = "NOT_STARTED"
    private var lastStatusObservation: JSONObject? = null
    private var baselineActivity: Activity? = null
    private var fixtureSetupPhase: FixtureSetupPhase? = null
    private var fixtureWitnessFile: File? = null
    private var fixtureWitnessSignature: String? = null
    private val fixtureWitnessHistory = JSONArray()
    private val fixtureWitnessFailures = JSONArray()

    private fun startFixtureSetup(id: String): FixtureSetupPhase {
        val phase = FixtureSetupPhase(id, checkNotNull(runStartedNs), SystemClock.elapsedRealtime())
        fixtureSetupPhase = phase
        setupReport?.put("fixtureSetupStartElapsedMs", phase.setupStartElapsedMs)
            ?.put("fixtureSetupDeadlineElapsedMs", phase.setupDeadlineElapsedMs)
            ?.put("fixtureSetupWitnessHistory", fixtureWitnessHistory)
            ?.put("fixtureSetupWitnessFailures", fixtureWitnessFailures)
        setupStage("FIXTURE_SETUP_PREPARING")
        val destination = File(targetContext.filesDir, "probe-$id-fixture-setup.json")
        check(destination.createNewFile()) { "Existing fixture setup witness" }
        fixtureWitnessFile = destination
        publishFixtureSetup(phase, "PREPARING")
        return phase
    }

    private fun beginOwnedReconnectLeg(phase: FixtureSetupPhase) {
        phase.beginOwnedReconnectLeg()
        setupReport?.put("fixturePriorMediaLegSessionStarts", JSONArray().apply {
            phase.priorMediaLegSessionStarts.forEach { put(it ?: JSONObject.NULL) }
        })?.put("fixtureMediaLeg", phase.priorMediaLegSessionStarts.size + 1)
            ?.put("fixtureOwnedReconnectFreshAfterMonoNs", freshAfterNs)
        publishFixtureSetup(phase, "WAIT_MEDIA_SESSION")
    }

    private fun fixtureStatusRejection(value: JSONObject?, atMonoNs: Long): String? {
        if (value == null) return "NO_VALIDATED_STATUS"
        val bound = if (freshAfterNs == 0L) runStartedNs else freshAfterNs
        return receiverStatusRejection(value.optString("state"), diagnosticClockLong(value.opt("startedMonoNs")),
            diagnosticClockLong(value.opt("elapsedMs")), bound, atMonoNs)
    }
    private fun fixtureGeometryReady(phase: FixtureSetupPhase, value: JSONObject?): Boolean {
        return value != null && phase.sessionStartedMonoNs == value.getLong("startedMonoNs") && fixtureBootstrapReady(value.getString("state"),
            value.optString("tls"), value.optString("peerAddress"), value.optString("localAddress"),
            value.optInt("width", 0), value.optInt("height", 0), value.optLong("touchesSent", -1))
    }

    /** Returns existing status validity at publication, not operation authority. Quiet errors cannot mask cleanup. */
    private fun publishFixtureSetup(phase: FixtureSetupPhase, stage: String, value: JSONObject? = null, quiet: Boolean = false): Boolean {
        try {
            val observedElapsed = SystemClock.elapsedRealtime()
            val observedMono = System.nanoTime() // After status() validation/read; never subtract from elapsed or host clocks.
            val rejection = fixtureStatusRejection(value, observedMono)
            val validated = value?.takeIf { rejection == null }
            val geometryReady = stage in listOf("GEOMETRY_READY", "WAIT_GO") &&
                observedElapsed < phase.setupDeadlineElapsedMs && fixtureGeometryReady(phase, validated)
            val current = validated?.let { source -> JSONObject().apply {
                for (key in listOf("state", "startedMonoNs", "elapsedMs", "tls", "width", "height", "peerAddress",
                        "localAddress", "touchesSent", "driverStatusRead")) if (source.has(key)) put(key, source.get(key))
            } }
            val signature = listOf(stage, geometryReady, rejection, validated?.opt("startedMonoNs"),
                validated?.opt("state"), validated?.opt("tls"), validated?.opt("width"), validated?.opt("height"),
                validated?.opt("peerAddress"), validated?.opt("localAddress"), validated?.opt("touchesSent")).toString()
            // A ready witness must refresh its publication clock while the existing Go poll continues.
            if (!quiet && !geometryReady && signature == fixtureWitnessSignature) return validated != null
            val witness = JSONObject().put("schemaVersion", 1).put("runId", phase.runId)
                .put("producerRunStartedMonoNs", phase.producerRunStartedMonoNs)
                .put("setupStartElapsedMs", phase.setupStartElapsedMs).put("setupDeadlineElapsedMs", phase.setupDeadlineElapsedMs)
                .put("observedElapsedMs", observedElapsed).put("observedMonoNs", observedMono).put("phase", stage)
                .put("geometryReady", geometryReady).put("statusValidated", validated != null)
                .put("statusValidationRejection", rejection ?: JSONObject.NULL)
                .put("sessionStartedMonoNs", phase.sessionStartedMonoNs ?: JSONObject.NULL)
                .put("status", current ?: JSONObject.NULL)
            val destination = checkNotNull(fixtureWitnessFile)
            val temporary = File(destination.parentFile, destination.name + ".tmp")
            check(temporary.createNewFile()) { "Existing fixture setup witness temporary" }
            temporary.writeText(witness.toString())
            check(temporary.renameTo(destination)) { "Fixture setup witness publication failed" }
            fixtureWitnessSignature = signature
            fixtureWitnessHistory.put(witness)
            return validated != null
        } catch (error: Throwable) {
            runCatching { fixtureWitnessFailures.put(JSONObject(startupErrorFields(error))) }
            if (!quiet) throw error
            return false
        }
    }
    private fun startupSnapshot(activity: Activity?, destination: JSONObject) {
        if (activity !is ReceiverActivity) {
            destination.put("startupSnapshotScope", "No owned Receiver Activity returned")
            return
        }
        var values: Map<String, Any?>? = null
        var start: Long? = null
        var end: Long? = null
        var readFailure: Throwable? = null
        try {
            runOnMainSync {
                try {
                    start = System.nanoTime()
                    values = activity.startupDiagnosticSnapshot()
                } catch (error: Throwable) { readFailure = error }
                finally { end = System.nanoTime() }
            }
            val failure = readFailure
            if (failure != null) destination.put("startupSnapshotError", JSONObject(startupErrorFields(failure)))
            else destination.put("startupSnapshot", JSONObject(values ?: emptyMap<String, Any?>()))
                    .put("startupSnapshotScope", "Passive current-memory scalar reads; not atomic readiness or authorization")
        } catch (error: Throwable) {
            runCatching { destination.put("startupSnapshotError", JSONObject(startupErrorFields(error))) }
        }
        runCatching { destination.put("startupSnapshotStartMonoNs", start ?: JSONObject.NULL)
            .put("startupSnapshotEndMonoNs", end ?: JSONObject.NULL) }
    }
    private fun setupStage(stage: String, activity: Activity? = baselineActivity) {
        if (activity != null) baselineActivity = activity
        currentSetupStage = stage
        setupReport?.put("setupStage", stage)
        val record = JSONObject().put("stage", stage).put("observedMonoNs", System.nanoTime())
            .put("activityPresent", activity != null)
        startupSnapshot(activity, record)
        setupStages.put(record)
    }
    override fun onCreate(arguments: Bundle?) { args = arguments ?: Bundle(); super.onCreate(arguments); start() }
    private fun buttons(view: View): List<Button> = when (view) {
        is Button -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { buttons(view.getChildAt(it)) }
        else -> emptyList()
    }
    private fun clickConnect(activity: Activity) {
        setupStage("WAIT_SURFACE", activity)
        val deadline = SystemClock.elapsedRealtime() + 5000
        var ready = false
        while (!ready && SystemClock.elapsedRealtime() < deadline) {
            runOnMainSync { ready = surfaces(activity.window.decorView).singleOrNull()?.holder?.surface?.isValid == true }
            if (!ready) Thread.sleep(50)
        }
        check(ready) { "No current projection Surface" }
        setupStage("SURFACE_READY", activity)
        var buttonFound = false
        var clickResult: Boolean? = null
        setupStage("CONNECT_DISPATCH", activity)
        runOnMainSync {
            // Phone Start may already have attached this resumed Receiver through the paired control route.
            val button = buttons(activity.window.decorView).singleOrNull { it.text == "Connect" }
            buttonFound = button != null
            clickResult = button?.performClick()
        }
        setupReport?.put("connectButtonFound", buttonFound)?.put("connectClickResult", clickResult ?: JSONObject.NULL)
        setupStage("CONNECT_RETURNED", activity)
    }
    private fun status(): JSONObject {
        val bound = if (freshAfterNs == 0L) runStartedNs else freshAfterNs
        val observation = JSONObject().put("requiredStartMonoNs", bound ?: JSONObject.NULL)
        lastStatusObservation = observation
        try {
            val value = JSONObject(File(targetContext.filesDir, "status.json").readText())
            val now = System.nanoTime()
            val started = diagnosticClockLong(value.opt("startedMonoNs"))
            val elapsed = diagnosticClockLong(value.opt("elapsedMs"))
            val reason = receiverStatusRejection(value.optString("state"), started, elapsed, bound, now)
            observation.put("readNowMonoNs", now).put("state", value.optString("state"))
                .put("startedMonoNs", started ?: JSONObject.NULL).put("elapsedMs", elapsed ?: JSONObject.NULL)
                .put("rejection", reason ?: JSONObject.NULL)
            check(reason == null) { "Rejected Receiver status: $reason" }
            value.put("driverStatusRead", observation)
            setupReport?.put("lastStatusObservation", observation)
            return value
        } catch (error: Exception) {
            startupErrorFields(error).forEach { (key, value) -> observation.put(key, value ?: JSONObject.NULL) }
            throw error
        }
    }
    private fun surfaces(view: View): List<SurfaceView> = when (view) {
        is SurfaceView -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { surfaces(view.getChildAt(it)) }
        else -> emptyList()
    }

    /** One own-Surface read for diagnosis. Neither a callback nor copied pixels prove composition or optical output. */
    private fun readSurface(activity: Activity, id: String, report: JSONObject) {
        val detail = JSONObject().put("diagnosticOnly", true).put("maximumPixelCopyRequests", 1)
        report.put("surfaceRead", detail)
        require(status().getString("state") == "STREAMING" && status().getLong("touchesSent") == 0L)
        val destination = File(targetContext.filesDir, "probe-$id-surface.png")
        require(destination.createNewFile()) { "Existing Surface diagnostic" }
        val bitmap = Bitmap.createBitmap(480, 640, Bitmap.Config.ARGB_8888)
        val done = CountDownLatch(1)
        var result = -1
        val begin = SystemClock.elapsedRealtime()
        try {
            runOnMainSync {
                require(activity is ReceiverActivity && activity.hasWindowFocus() && !activity.isFinishing)
                val surface = surfaces(activity.window.decorView).single()
                require(surface.isShown && surface.width == 480 && surface.height == 640 && surface.holder.surface.isValid)
                detail.put("focusedOwnSurface480x640AtRequest", true)
                PixelCopy.request(surface, bitmap, { code -> result = code; done.countDown() }, Handler(Looper.getMainLooper()))
            }
            val arrived = done.await(5, TimeUnit.SECONDS)
            detail.put("callbackArrived", arrived).put("requestToWaitReturnMs", SystemClock.elapsedRealtime() - begin)
            require(arrived) { "Bounded Surface read did not complete" }
            val category = when (result) {
                PixelCopy.SUCCESS -> "SUCCESS"
                PixelCopy.ERROR_UNKNOWN -> "ERROR_UNKNOWN"
                PixelCopy.ERROR_TIMEOUT -> "ERROR_TIMEOUT"
                PixelCopy.ERROR_SOURCE_NO_DATA -> "ERROR_SOURCE_NO_DATA"
                PixelCopy.ERROR_SOURCE_INVALID -> "ERROR_SOURCE_INVALID"
                PixelCopy.ERROR_DESTINATION_INVALID -> "ERROR_DESTINATION_INVALID"
                else -> "UNRECOGNIZED_RESULT"
            }
            detail.put("result", category)
            if (result == PixelCopy.SUCCESS) {
                FileOutputStream(destination).use { output ->
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                    output.fd.sync()
                }
                detail.put("PNGBytes", destination.length()).put("copiedWidth", bitmap.width).put("copiedHeight", bitmap.height)
            }
            detail.put("touchesSentAfter", status().getLong("touchesSent"))
            require(status().getLong("touchesSent") == 0L)
        } finally {
            // A timed-out copy may still own the bitmap until its callback; do not recycle it underneath the native read.
            if (done.count == 0L) bitmap.recycle()
        }
    }
    private fun waitStreaming(fixture: FixtureSetupPhase? = null) {
        setupStage("WAIT_STREAM")
        val mediaDeadline = SystemClock.elapsedRealtime() + 15_000
        val deadline = if (fixture == null) mediaDeadline else minOf(mediaDeadline, fixture.setupDeadlineElapsedMs)
        while (SystemClock.elapsedRealtime() < deadline) {
            if (runCatching { status().optString("state") == "STREAMING" }.getOrDefault(false) &&
                (fixture == null || SystemClock.elapsedRealtime() < fixture.setupDeadlineElapsedMs)) {
                setupStage("STREAM_READY")
                if (fixture != null && SystemClock.elapsedRealtime() >= fixture.setupDeadlineElapsedMs) break
                return
            }
            Thread.sleep(100)
        }
        if (fixture != null && SystemClock.elapsedRealtime() >= fixture.setupDeadlineElapsedMs) {
            error("Shared fixture setup deadline expired waiting for stream")
        }
        error("No rendered stream")
    }
    private fun waitFixtureGeometry(fixture: FixtureSetupPhase) {
        setupStage("WAIT_FIXTURE_MEDIA_SESSION")
        publishFixtureSetup(fixture, "WAIT_MEDIA_SESSION")
        while (SystemClock.elapsedRealtime() < fixture.setupDeadlineElapsedMs) {
            var value = runCatching { status() }.getOrNull()
            if (value != null && value.optString("state") in listOf("CONNECTING", "WAITING_FOR_VIDEO", "STREAMING")) {
                val first = fixture.sessionStartedMonoNs == null
                fixture.observeSession(value.getLong("startedMonoNs"))
                if (first) {
                    setupStage("WAIT_FIXTURE_GEOMETRY")
                    value = runCatching { status() }.getOrNull()
                }
                publishFixtureSetup(fixture, "WAIT_GEOMETRY", value)
                val ready = fixtureGeometryReady(fixture, value)
                if (SystemClock.elapsedRealtime() >= fixture.setupDeadlineElapsedMs) break
                if (ready && (first || !fixture.mediaExpired(System.nanoTime()))) {
                    setupStage("FIXTURE_GEOMETRY_READY")
                    if (SystemClock.elapsedRealtime() >= fixture.setupDeadlineElapsedMs) break
                    val confirmed = runCatching { status() }.getOrNull()
                    if (fixtureGeometryReady(fixture, confirmed) && (first || !fixture.mediaExpired(System.nanoTime())) &&
                        publishFixtureSetup(fixture, "GEOMETRY_READY", confirmed) &&
                        SystemClock.elapsedRealtime() < fixture.setupDeadlineElapsedMs) return
                    publishFixtureSetup(fixture, "WAIT_GEOMETRY", confirmed)
                }
            } else publishFixtureSetup(fixture, if (fixture.sessionStartedMonoNs == null) "WAIT_MEDIA_SESSION" else "WAIT_GEOMETRY", value)
            if (fixture.mediaExpired(System.nanoTime())) error("Fixture media geometry deadline expired after first fresh session start")
            Thread.sleep(100)
        }
        error(if (fixture.sessionStartedMonoNs == null) "Shared fixture setup deadline expired before fresh current-run media session"
              else "Shared fixture setup deadline expired before authenticated fixture geometry")
    }
    private fun waitFixtureGo(id: String, fixture: FixtureSetupPhase) {
        setupStage("WAIT_FIXTURE_GO")
        val go = File(targetContext.filesDir, "probe-$id.go")
        while (SystemClock.elapsedRealtime() < fixture.setupDeadlineElapsedMs) {
            val value = runCatching { status() }.getOrNull()
            val ready = fixtureGeometryReady(fixture, value)
            val publishedValid = publishFixtureSetup(fixture, "WAIT_GO", value)
            if (ready && publishedValid && go.isFile && SystemClock.elapsedRealtime() < fixture.setupDeadlineElapsedMs) {
                setupStage("FIXTURE_GO_RECEIVED")
                if (SystemClock.elapsedRealtime() >= fixture.setupDeadlineElapsedMs) break
                val confirmed = runCatching { status() }.getOrNull()
                if (fixtureGeometryReady(fixture, confirmed) && publishFixtureSetup(fixture, "GO_RECEIVED", confirmed) &&
                    SystemClock.elapsedRealtime() < fixture.setupDeadlineElapsedMs) return
                publishFixtureSetup(fixture, "WAIT_GO", confirmed)
            }
            Thread.sleep(100)
        }
        error("Bounded fixture setup did not complete within the shared deadline")
    }
    private fun unauthenticated(): Boolean {
        val directory = File(targetContext.filesDir, "session")
        val certificate = File(directory, "peer.crt").inputStream().use { CertificateFactory.getInstance("X.509").generateCertificate(it) }
        val trust = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null); setCertificateEntry("host", certificate) }
        val managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(trust) }
        val context = SSLContext.getInstance("TLS").apply { init(null, managers.trustManagers, null) }
        val endpoint = Tls.settings(directory)
        return try {
            (context.socketFactory.createSocket(endpoint.getProperty("host"), endpoint.getProperty("port").toInt()) as SSLSocket).use { socket ->
                socket.soTimeout = 3000
                socket.sslParameters = socket.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
                socket.startHandshake()
                Wire.writeRequest(DataOutputStream(socket.outputStream), Wire.STREAM)
                Wire.readWelcome(DataInputStream(socket.inputStream))
            }
            false
        } catch (_: javax.net.ssl.SSLException) { true }
    }

    override fun onStart() {
        val report = JSONObject().put("passed", false).put("driver", "on-RG Kotlin instrumentation")
        runStartedNs = System.nanoTime()
        setupReport = report
        report.put("runStartedMonoNs", runStartedNs).put("setupStages", setupStages)
        setupStage("RUN_STARTED")
        var activity: Activity? = null
        var file: File? = null
        var ownsRecord = false
        val audioManager = targetContext.getSystemService(AudioManager::class.java)
        var priorVolume: Int? = null
        var testFocus: AudioFocusRequest? = null
        var measurementStarted: Long? = null
        var measurementEnded: Long? = null
        var windowPresentationRecorder: WindowPresentationRecorder? = null
        try {
            val id = args.getString("runId") ?: error("Run identity required")
            require(id.matches(Regex("[A-Za-z0-9_-]{1,48}")))
            report.put("runId", id)
            file = File(targetContext.filesDir, "probe-$id.json")
            require(file.createNewFile()) { "Existing test record" }
            ownsRecord = true
            val operation = args.getString("operation") ?: "stream"
            report.put("operation", operation)
            if (operation == "native-input-observation") {
                setupStage("NATIVE_OBSERVER_START")
                runNativeInputObservation(this, args, id, report)
                setupStage("NATIVE_OBSERVER_FINISHED")
            } else if (operation == "local-input-replay") {
                val widgets = WidgetCheckDiagnostics.nativeReport()
                report.put("localWidgets", widgets)
                val diagnostics = WidgetCheckDiagnostics(widgets)
                val intent = Intent().setClassName(targetContext.packageName, ReceiverActivity::class.java.name)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                diagnostics.enter(WidgetCheckDiagnostics.Check.ACTIVITY_START)
                setupStage("ACTIVITY_LAUNCH")
                activity = startActivitySync(intent)
                setupStage("ACTIVITY_LAUNCHED", activity)
                diagnostics.completed(WidgetCheckDiagnostics.Check.ACTIVITY_START)
                diagnostics.enter(WidgetCheckDiagnostics.Check.ACTIVITY_IDLE)
                waitForIdleSync()
                diagnostics.completed(WidgetCheckDiagnostics.Check.ACTIVITY_IDLE)
                NativeInputWidgetChecks.run(activity,
                    { task -> WidgetCheckDiagnostics.mainSync(::runOnMainSync, task) }, widgets)
                diagnostics.enter(WidgetCheckDiagnostics.Check.LOCAL_INPUT_REPLAY_COMPLETE)
                diagnostics.completed(WidgetCheckDiagnostics.Check.LOCAL_INPUT_REPLAY_COMPLETE)
            } else if (operation == "tls-diagnostic") {
                report.put("phase", "LOAD_PLATFORM_TLS_CONTEXT")
                Tls.context(File(targetContext.filesDir, "session"))
                report.put("contextLoaded", true).put("phase", "AUTHENTICATED_HANDSHAKE")
                Tls.connect(File(targetContext.filesDir, "session")).use { report.put("tls", it.session.protocol) }
            } else if (operation == "unauthenticated") {
                require(unauthenticated()); report.put("unauthenticatedRefused", true).put("mediaWelcomeReceived", false)
            } else {
                require(operation in listOf("stream", "static-content", "scale", "connected-panel", "pause", "reconnect", "local-stop", "host-stop", "surface-diagnostic", "input-observation", "audio-focus", "audio-volume"))
                val seconds = args.getString("seconds")?.toInt() ?: 10
                require(seconds in 1..660)
                val waitForFixture = args.getString("waitForGo") == "true"
                val fixtureSetup = if (waitForFixture) startFixtureSetup(id) else null
                if (waitForFixture && operation in listOf("input-observation", "static-content")) {
                    try {
                        windowPresentationRecorder = WindowPresentationRecorder(id, checkNotNull(runStartedNs))
                        windowPresentationRecorder?.install(this)
                    } catch (error: Throwable) {
                        runCatching { report.put("windowPresentationObservationUnavailable", error.javaClass.simpleName) }
                    }
                }
                if (operation == "audio-volume") {
                    priorVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                    report.put("priorMusicVolume", priorVolume)
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, minOf(2, audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)), 0)
                }
                val intent = Intent().setClassName(targetContext.packageName, ReceiverActivity::class.java.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                freshAfterNs = System.nanoTime()
                setupStage("ACTIVITY_LAUNCH")
                activity = startActivitySync(intent)
                setupStage("ACTIVITY_LAUNCHED", activity)
                setupStage("WAIT_ACTIVITY_IDLE", activity)
                waitForIdleSync(); Thread.sleep(250)
                setupStage("ACTIVITY_IDLE", activity)
                if (args.getString("autoStart") == "true") {
                    File(targetContext.filesDir, "probe-$id.ready").writeText("Receiver resumed; waiting for Phone Start; no Glasses Connect invocation")
                    report.put("initiator", "Phone Start; no Glasses Connect invocation")
                    setupStage("WAIT_PHONE_START", activity)
                } else clickConnect(activity)
                if (waitForFixture) waitFixtureGeometry(fixtureSetup!!) else waitStreaming()
                if (operation == "connected-panel") report.put("connectedPanel", ConnectedPanelChecks.run(this, activity as ReceiverActivity))
                report.put("connected", status())
                if (operation == "reconnect") {
                    setupStage("RECONNECT_FINISH_OWNED_ACTIVITY", activity)
                    runOnMainSync { activity!!.finish() }; Thread.sleep(1500)
                    require(status().getString("state") == "STOPPED")
                    freshAfterNs = System.nanoTime()
                    fixtureSetup?.let { beginOwnedReconnectLeg(it) }
                    baselineActivity = null
                    setupStage("RECONNECT_ACTIVITY_LAUNCH", null)
                    activity = startActivitySync(intent)
                    setupStage("RECONNECT_ACTIVITY_LAUNCHED", activity)
                    setupStage("RECONNECT_WAIT_ACTIVITY_IDLE", activity)
                    waitForIdleSync(); Thread.sleep(250)
                    setupStage("RECONNECT_ACTIVITY_IDLE", activity)
                    clickConnect(activity)
                    if (waitForFixture) waitFixtureGeometry(fixtureSetup!!) else waitStreaming()
                    require(status().getLong("touchesSent") == 0L)
                    report.put("reconnected", status())
                }
                if (waitForFixture) {
                    waitFixtureGo(id, fixtureSetup!!)
                    report.put("fixtureSetupBeforeMeasurement", true)
                    waitStreaming(fixtureSetup)
                }
                if (operation == "scale") {
                    runLiveScaleProbe(this, activity, args, report, id)
                } else if (operation == "surface-diagnostic") {
                    require(waitForFixture && (args.getString("tapAtSeconds") ?: "-1") == "-1")
                    readSurface(activity, id, report)
                } else {
                    report.put("first", status())
                    if (operation == "input-observation") runOnMainSync {
                        report.put("inputBefore", (activity as ReceiverActivity).inputSnapshot())
                    }
                    val start = SystemClock.elapsedRealtime()
                    measurementStarted = start
                    windowPresentationRecorder?.measurementStarted(start)
                    val samples = JSONArray()
                    report.put("samples", samples)
                    val tapAt = args.getString("tapAtSeconds")?.toInt() ?: -1
                    val x = args.getString("tapX")?.toFloat() ?: 240f
                    val y = args.getString("tapY")?.toFloat() ?: 320f
                    require(x in 0f..<480f && y in 0f..<640f)
                    var tapped = false; var nextSample = 0L
                    while (SystemClock.elapsedRealtime() - start < seconds * 1000L) {
                        val elapsed = SystemClock.elapsedRealtime() - start
                        if (tapAt >= 0 && !tapped && elapsed >= tapAt * 1000L) {
                            require(status().getString("state") == "STREAMING")
                            runOnMainSync { require(activity!!.hasWindowFocus() && !activity!!.isFinishing) }
                            val now = SystemClock.uptimeMillis()
                            windowPresentationRecorder?.tapDispatchStarting(now)
                            sendPointerSync(MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0))
                            sendPointerSync(MotionEvent.obtain(now, now + 30, MotionEvent.ACTION_UP, x, y, 0))
                            windowPresentationRecorder?.tapDispatchReturned(now)
                            report.put("onRGTapElapsedMs", elapsed); tapped = true
                        }
                        if (elapsed >= nextSample) {
                            val sample = status(); require(sample.getString("state") == "STREAMING")
                            require(sample.getString("audio") == "system-bluetooth")
                            val previous = samples.optJSONObject(samples.length() - 1)
                            require(previous == null || sample.getLong("elapsedMs") >= previous.getLong("elapsedMs"))
                            samples.put(sample); nextSample = elapsed + 1000
                        }
                        Thread.sleep(100)
                    }
                    if (operation == "input-observation") runOnMainSync {
                        report.put("inputAfter", (activity as ReceiverActivity).inputSnapshot())
                    }
                    measurementEnded = SystemClock.elapsedRealtime()
                    val last = status()
                    val first = report.getJSONObject("first")
                    require(last.getLong("elapsedMs") - first.getLong("elapsedMs") >= seconds * 1000L - 2500)
                    if (operation == "static-content") {
                        require(last.getString("state") == "STREAMING")
                        report.put("staticScope", "Session survives after initial render; packet silence does not establish producer health. Verify the separate source fixture record.")
                    } else {
                        require(last.getLong("rendered") > first.getLong("rendered")) { "No actual video progress" }
                        require(last.getLong("changingPackets") > first.getLong("changingPackets")) { "No changing encoded video" }
                    }
                    report.put("observedStreamingMs", SystemClock.elapsedRealtime() - start).put("last", last).put("samples", samples)
                    if (operation == "audio-volume") {
                        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
                        Thread.sleep(1000)
                        report.put("muted", status()).put("mutedVolume", audioManager.getStreamVolume(AudioManager.STREAM_MUSIC))
                        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, minOf(2, audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)), 0)
                        Thread.sleep(1000); report.put("safeVolume", status())
                    }
                    if (operation == "audio-focus") {
                        testFocus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
                            .setOnAudioFocusChangeListener { }.build()
                        require(audioManager.requestAudioFocus(testFocus!!) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
                        Thread.sleep(1500)
                        val afterFocus = status()
                        require(afterFocus.getString("state") == "STREAMING")
                        require(afterFocus.getLong("rendered") > last.getLong("rendered"))
                        report.put("independentAudioFocus", afterFocus)
                    }
                    if (operation == "pause") {
                        val retainedStart = status().getLong("startedMonoNs")
                        targetContext.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        val pauseDeadline = SystemClock.elapsedRealtime() + 5000
                        while (status().optBoolean("presentationActive", true) && SystemClock.elapsedRealtime() < pauseDeadline) Thread.sleep(100)
                        val paused = status()
                        require(paused.getString("state") == "STREAMING" && !paused.getBoolean("presentationActive"))
                        require(paused.getLong("startedMonoNs") == retainedStart)
                        report.put("paused", paused).put("pauseScope", "Activity/Surface detach; physical Glasses sleep requires separate device evidence")
                        targetContext.startActivity(intent)
                        val resumeDeadline = SystemClock.elapsedRealtime() + 5000
                        while (!status().optBoolean("presentationActive", false) && SystemClock.elapsedRealtime() < resumeDeadline) Thread.sleep(100)
                        val restored = status()
                        require(restored.getString("state") == "STREAMING" && restored.getBoolean("presentationActive"))
                        require(restored.getLong("startedMonoNs") == retainedStart)
                        report.put("resumedSameReceiverSession", restored)
                    }
                    if (operation == "local-stop") {
                        runOnMainSync { (activity as ReceiverActivity).disconnect() }
                        report.put("localStopInvocation", "Direct Disconnect method; physical control binding not qualified")
                        Thread.sleep(1000); require(status().getString("state") == "STOPPED")
                        report.put("localStop", status())
                    }
                    if (operation == "host-stop") {
                        Tls.connect(File(targetContext.filesDir, "session")).use { socket ->
                            Wire.writeRequest(DataOutputStream(socket.outputStream), Wire.STOP_HOST)
                            require(socket.inputStream.read() == -1)
                        }
                        val deadline = SystemClock.elapsedRealtime() + 5000
                        while (status().getString("state") == "STREAMING" && SystemClock.elapsedRealtime() < deadline) Thread.sleep(100)
                        require(status().getString("state") in listOf("CONNECTION_OR_VIDEO_FAILED", "DISCONNECTED"))
                        report.put("hostStop", status())
                    }
                }
            }
            report.put("passed", true)
        } catch (error: Exception) {
            fixtureSetupPhase?.let { publishFixtureSetup(it, "SETUP_FAILED", quiet = true) }
            report.put("errorType", error.javaClass.simpleName)
            report.put("failureSetupStage", currentSetupStage)
            report.put("failureActivityScope", when {
                activity == null -> "NO_ACTIVITY_RETURNED"
                activity === baselineActivity -> "CURRENT_BASELINE_ACTIVITY"
                else -> "PRIOR_OWNED_ACTIVITY_NO_NEW_ACTIVITY_RETURNED"
            })
            startupErrorFields(error).forEach { (key, value) -> report.put(key, value ?: JSONObject.NULL) }
            startupSnapshot(activity, report)
            if (activity != null) runCatching { report.put("failureStatus", status()) }
                .onFailure { failure -> report.put("failureStatusUnavailable", JSONObject(startupErrorFields(failure))) }
            lastStatusObservation?.let { report.put("lastStatusObservation", it) }
            val causes = JSONArray(); var cause: Throwable? = error
            repeat(6) { cause?.let { causes.put(it.javaClass.simpleName); cause = it.cause } }
            report.put("causeClasses", causes)
        }
        finally {
            fixtureSetupPhase?.let { publishFixtureSetup(it, "SETUP_FINISHED", quiet = true) }
            measurementStarted?.let { report.put("actualMeasurementMs", (measurementEnded ?: SystemClock.elapsedRealtime()) - it) }
            try {
                windowPresentationRecorder?.finish(this, report, ::status, if (freshAfterNs == 0L) checkNotNull(runStartedNs) else freshAfterNs)
            } catch (error: Throwable) {
                runCatching { report.put("windowPresentationObservationFinishFailure", error.javaClass.simpleName) }
            }
            if (activity != null) report.put("baselineTeardown", "FINISH_OWNED_ACTIVITY")
            activity?.let { runOnMainSync { it.finish() } }
            baselineActivity = null
            testFocus?.let { audioManager.abandonAudioFocusRequest(it) }
            priorVolume?.let {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, it, 0)
                report.put("restoredMusicVolume", audioManager.getStreamVolume(AudioManager.STREAM_MUSIC))
            }
            if (ownsRecord) file?.writeText(report.toString())
            // The full bounded timeline stays in the app file; a ten-minute JSON exceeds Binder's reply limit.
            val result = JSONObject().put("passed", report.getBoolean("passed"))
                .put("reportFile", if (ownsRecord) file?.name else null)
                .put("reportBytes", if (ownsRecord) file?.length() else 0)
                .put("actualMeasurementMs", report.optLong("actualMeasurementMs", -1))
                .put("errorType", report.optString("errorType"))
            finish(if (report.getBoolean("passed")) Activity.RESULT_OK else Activity.RESULT_CANCELED,
                Bundle().apply { putString("eyeprojector", result.toString()) })
        }
    }
}
