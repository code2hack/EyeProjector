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

/** Explicit bounded on-RG test driver. No input/media loop runs on a computer. */
class ProjectionInstrumentation : Instrumentation() {
    private lateinit var args: Bundle
    private var freshAfterNs = 0L
    override fun onCreate(arguments: Bundle?) { args = arguments ?: Bundle(); super.onCreate(arguments); start() }
    private fun buttons(view: View): List<Button> = when (view) {
        is Button -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { buttons(view.getChildAt(it)) }
        else -> emptyList()
    }
    private fun clickConnect(activity: Activity) {
        val deadline = SystemClock.elapsedRealtime() + 5000
        var ready = false
        while (!ready && SystemClock.elapsedRealtime() < deadline) {
            runOnMainSync { ready = surfaces(activity.window.decorView).singleOrNull()?.holder?.surface?.isValid == true }
            if (!ready) Thread.sleep(50)
        }
        check(ready) { "No current projection Surface" }
        runOnMainSync {
            // Phone Start may already have attached this resumed Receiver through the paired control route.
            buttons(activity.window.decorView).singleOrNull { it.text == "Connect" }?.performClick()
        }
    }
    private fun status(): JSONObject = JSONObject(File(targetContext.filesDir, "status.json").readText()).also {
        check(freshAfterNs == 0L || it.optLong("startedMonoNs", 0) >= freshAfterNs) { "Historical session status" }
        check(it.optString("state") !in listOf("STREAMING", "CONNECTING", "WAITING_FOR_VIDEO") || System.nanoTime() - it.optLong("startedMonoNs", 0) - it.optLong("elapsedMs", 0) * 1_000_000 < 3_000_000_000L) { "Stale session telemetry" }
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
    private fun waitStreaming() {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (runCatching { status().optString("state") == "STREAMING" }.getOrDefault(false)) return
            Thread.sleep(100)
        }
        error("No rendered stream")
    }
    private fun waitFixtureGeometry() {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (SystemClock.elapsedRealtime() < deadline) {
            val ready = runCatching {
                val value = status()
                fixtureBootstrapReady(value.getString("state"), value.optString("tls"), value.optString("peerAddress"),
                    value.optString("localAddress"), value.getInt("width"), value.getInt("height"), value.getLong("touchesSent"))
            }.getOrDefault(false)
            if (ready) return
            Thread.sleep(100)
        }
        error("No authenticated fixture geometry")
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
        var activity: Activity? = null
        var file: File? = null
        var ownsRecord = false
        val audioManager = targetContext.getSystemService(AudioManager::class.java)
        var priorVolume: Int? = null
        var testFocus: AudioFocusRequest? = null
        var measurementStarted: Long? = null
        var measurementEnded: Long? = null
        try {
            val id = args.getString("runId") ?: error("Run identity required")
            require(id.matches(Regex("[A-Za-z0-9_-]{1,48}")))
            file = File(targetContext.filesDir, "probe-$id.json")
            require(file.createNewFile()) { "Existing test record" }
            ownsRecord = true
            val operation = args.getString("operation") ?: "stream"
            report.put("operation", operation)
            if (operation == "native-input-observation") {
                runNativeInputObservation(this, args, id, report)
            } else if (operation == "local-input-replay") {
                val widgets = WidgetCheckDiagnostics.nativeReport()
                report.put("localWidgets", widgets)
                val diagnostics = WidgetCheckDiagnostics(widgets)
                val intent = Intent().setClassName(targetContext.packageName, ReceiverActivity::class.java.name)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                diagnostics.enter(WidgetCheckDiagnostics.Check.ACTIVITY_START)
                activity = startActivitySync(intent)
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
                if (operation == "audio-volume") {
                    priorVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                    report.put("priorMusicVolume", priorVolume)
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, minOf(2, audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)), 0)
                }
                val intent = Intent().setClassName(targetContext.packageName, ReceiverActivity::class.java.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                freshAfterNs = System.nanoTime()
                activity = startActivitySync(intent); waitForIdleSync(); Thread.sleep(250)
                if (args.getString("autoStart") == "true") {
                    File(targetContext.filesDir, "probe-$id.ready").writeText("Receiver resumed; waiting for Phone Start; no Glasses Connect invocation")
                    report.put("initiator", "Phone Start; no Glasses Connect invocation")
                } else clickConnect(activity)
                if (waitForFixture) waitFixtureGeometry() else waitStreaming()
                if (operation == "connected-panel") report.put("connectedPanel", ConnectedPanelChecks.run(this, activity as ReceiverActivity))
                report.put("connected", status())
                if (operation == "reconnect") {
                    runOnMainSync { activity!!.finish() }; Thread.sleep(1500)
                    require(status().getString("state") == "STOPPED")
                    freshAfterNs = System.nanoTime()
                    activity = startActivitySync(intent); waitForIdleSync(); Thread.sleep(250)
                    clickConnect(activity)
                    if (waitForFixture) waitFixtureGeometry() else waitStreaming()
                    require(status().getLong("touchesSent") == 0L)
                    report.put("reconnected", status())
                }
                if (waitForFixture) {
                    val go = File(targetContext.filesDir, "probe-$id.go")
                    val deadline = SystemClock.elapsedRealtime() + 180_000
                    while (!go.isFile && SystemClock.elapsedRealtime() < deadline) Thread.sleep(100)
                    require(go.isFile) { "Bounded fixture setup did not complete" }
                    report.put("fixtureSetupBeforeMeasurement", true)
                    waitStreaming()
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
                            sendPointerSync(MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0))
                            sendPointerSync(MotionEvent.obtain(now, now + 30, MotionEvent.ACTION_UP, x, y, 0))
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
            report.put("errorType", error.javaClass.simpleName)
            if (activity != null) runCatching { report.put("failureStatus", status()) }
            val causes = JSONArray(); var cause: Throwable? = error
            repeat(6) { cause?.let { causes.put(it.javaClass.simpleName); cause = it.cause } }
            report.put("causeClasses", causes)
        }
        finally {
            measurementStarted?.let { report.put("actualMeasurementMs", (measurementEnded ?: SystemClock.elapsedRealtime()) - it) }
            activity?.let { runOnMainSync { it.finish() } }
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
