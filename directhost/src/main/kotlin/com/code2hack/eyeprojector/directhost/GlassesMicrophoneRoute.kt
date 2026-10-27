package com.code2hack.eyeprojector.directhost

import android.content.Context
import android.content.ContextWrapper
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import java.lang.reflect.InvocationTargetException
import java.util.ArrayDeque

/**
 * One owner per ShizukuHostService, initially disabled. Root owns the active-headset
 * selector and its profile proxy; the resolver receives the genuine retained Shell context.
 * start/setEnabled/refresh/close enqueue work. Status delivery runs on this owner's lane.
 */
class GlassesMicrophoneRoute(
    private val resolveOutput: (Context, List<AudioDeviceInfo>) -> AudioDeviceInfo?,
    private val onStatus: (Status) -> Unit = {},
) : AutoCloseable {
    data class Recording(
        val sessionId: Int, val clientSource: Int, val inputId: Int?, val inputType: Int?,
        val inputIsSource: Boolean?, val silenced: Boolean,
    )
    data class Status(
        val phase: String = "NEW", val enabled: Boolean = false,
        val callerUid: Int = Process.myUid(), val recordings: List<Recording> = emptyList(),
        val outputId: Int? = null, val outputType: Int? = null, val routeAccepted: Boolean? = null,
        val modeReleaseOutstanding: Boolean = false, val routeReleaseOutstanding: Boolean = false,
        val observedMode: Int? = null, val observedCommunicationId: Int? = null,
        val observedCommunicationType: Int? = null, val cleanupSucceeded: Boolean? = null,
        val failureStage: String? = null, val failureClass: String? = null,
    )
    private data class Capture(val sessionId: Int, val clientSource: Int)
    private class Request(val outputId: Int, val outputType: Int) {
        var mode = false
        var route = false
        var accepted: Boolean? = null
        var releaseAttempted = false
        var cleanupSucceeded: Boolean? = null
    }

    private val gate = Any()
    private val pending = ArrayDeque<() -> Unit>()
    private var handler: Handler? = null
    @Volatile private var closing = false
    @Volatile private var closed = false
    @Volatile private var latest = Status()
    private var started = false
    private var enabled = false
    private var context: Context? = null
    private var audio: AudioManager? = null
    private var callback: AudioManager.AudioRecordingCallback? = null
    private var recordings: List<Recording> = emptyList()
    private var captures = emptySet<Capture>()
    private var request: Request? = null
    private var failureStage: String? = null
    private var failureClass: String? = null
    private val thread = object : HandlerThread("eyeprojector-glasses-microphone") {
        override fun onLooperPrepared() {
            synchronized(gate) {
                val lane = Handler(looper)
                handler = lane
                while (pending.isNotEmpty()) {
                    val action = pending.removeFirst()
                    lane.post { action() }
                }
            }
        }
    }

    init { thread.start() }

    fun status(): Status = latest

    fun start() = post {
        if (started || closing || closed) return@post
        started = true
        try {
            val shell = shellContext()
            context = shell
            audio = AudioManager::class.java.getConstructor(Context::class.java).newInstance(shell)
            val observer = object : AudioManager.AudioRecordingCallback() {
                override fun onRecordingConfigChanged(configs: List<AudioRecordingConfiguration>) {
                    if (closing || closed) return
                    accept(configs)
                }
            }
            callback = observer
            audio!!.registerAudioRecordingCallback(observer, handler!!)
            readCurrent()
        } catch (error: Throwable) {
            failed("START", error)
            publish("FAILED")
        }
    }

    fun setEnabled(value: Boolean) = post {
        if (closing || closed || enabled == value) return@post
        enabled = value
        captures = emptySet()
        if (!value) {
            release(force = true)
            if (request?.let { !it.mode && !it.route } == true) request = null
            publish(if (hasOutstanding()) "CLEANUP_FAILED" else "DISABLED")
        } else {
            if (!hasOutstanding()) request = null
            if (audio != null) readCurrent() else publish("WAITING_START")
        }
    }

    /** Root calls this on selector/profile service changes; this is not a polling/retry loop. */
    fun refresh() = post {
        if (!closing && !closed && audio != null) readCurrent()
    }

    override fun close() {
        synchronized(gate) {
            if (closing || closed) return
            closing = true
            post {
                enabled = false
                captures = emptySet()
                var unregistered = true
                callback?.let { observer ->
                    try { audio?.unregisterAudioRecordingCallback(observer) }
                    catch (error: Throwable) { unregistered = false; failed("UNREGISTER", error) }
                }
                callback = null
                release(force = true)
                closed = true
                publish(if (unregistered && !hasOutstanding()) "CLOSED" else "CLOSE_FAILED")
                thread.quitSafely()
            }
        }
    }

    private fun post(action: () -> Unit) {
        synchronized(gate) {
            if (closed) return
            val lane = handler
            if (lane == null) pending.addLast(action) else lane.post { action() }
        }
    }

    private fun readCurrent() {
        try { accept(audio!!.activeRecordingConfigurations) }
        catch (error: Throwable) { failed("RECORDING_SNAPSHOT", error); publish("FAILED") }
    }

    private fun accept(configs: List<AudioRecordingConfiguration>) {
        try {
            recordings = configs.map { config ->
                val input = config.audioDevice
                Recording(config.clientAudioSessionId, config.clientAudioSource, input?.id,
                    input?.type, input?.isSource, config.isClientSilenced)
            }
            val voice = recordings.filter { voiceSource(it.clientSource) }
            val present = voice.map { Capture(it.sessionId, it.clientSource) }.toSet()
            // Admission needs unsilenced capture. Once admitted, a still-reported session
            // remains present through input/format/silencing changes caused by rerouting.
            // Actual disappearance, disable and close remove its demand; no grace timer.
            captures = if (enabled) captures.intersect(present) + voice.filterNot { it.silenced }
                .map { Capture(it.sessionId, it.clientSource) } else emptySet()
            reconcile()
        } catch (error: Throwable) {
            // An unreadable observation is not an invented recording-stop observation.
            failed("RECORDING_CHANGE", error)
            publish("FAILED")
        }
    }

    private fun reconcile() {
        if (closing || closed) return
        if (!enabled || captures.isEmpty()) {
            release()
            if (!hasOutstanding()) request = null
        }
        if (!enabled) { publish(if (hasOutstanding()) "CLEANUP_FAILED" else "DISABLED"); return }
        val manager = audio ?: return
        val selected = resolveOutput(context!!, manager.availableCommunicationDevices.toList())
        if (captures.isEmpty()) { publish(if (hasOutstanding()) "CLEANUP_FAILED" else "IDLE"); return }
        if (selected == null) {
            release()
            if (!hasOutstanding()) request = null
            publish(if (hasOutstanding()) "CLEANUP_FAILED" else "OUTPUT_UNRESOLVED")
            return
        }
        val old = request
        if (old != null && old.outputId == selected.id) {
            publish(if (old.releaseAttempted) "REQUEST_FAILED" else "REQUESTED")
            return // Our own routing callbacks never duplicate a mode/SCO write.
        }
        release()
        if (hasOutstanding()) { publish("CLEANUP_FAILED"); return }
        if (recordings.none { voiceSource(it.clientSource) && !it.silenced }) {
            publish("WAITING_UNSILENCED_CAPTURE")
            return
        }
        if (closing) return
        val next = Request(selected.id, selected.type)
        request = next // Publish ownership before either potentially partially delivered call.
        failureStage = null
        failureClass = null
        try {
            next.mode = true
            manager.setMode(AudioManager.MODE_IN_COMMUNICATION)
            if (closing) { release(); return }
            next.route = true
            next.accepted = manager.setCommunicationDevice(selected)
            if (next.accepted != true) release()
            publish(if (next.accepted == true) "REQUESTED" else "REJECTED")
        } catch (error: Throwable) {
            failed("REQUEST", error)
            release()
            publish("FAILED")
        }
    }

    private fun hasOutstanding() = request?.let { it.mode || it.route } == true

    private fun release(force: Boolean = false) {
        val original = request ?: return
        if (original.releaseAttempted && !force) return
        original.releaseAttempted = true
        val manager = audio ?: return
        if (original.route) {
            try { manager.clearCommunicationDevice(); original.route = false }
            catch (error: Throwable) { failed("CLEAR_ROUTE", error) }
        }
        if (original.mode) {
            try { manager.setMode(AudioManager.MODE_NORMAL); original.mode = false }
            catch (error: Throwable) { failed("RELEASE_MODE", error) }
        }
        original.cleanupSucceeded = !original.route && !original.mode
    }

    private fun publish(phase: String) {
        val communication = runCatching { audio?.communicationDevice }.getOrNull()
        val original = request
        latest = Status(phase, enabled, Process.myUid(), recordings.toList(), original?.outputId,
            original?.outputType, original?.accepted, original?.mode == true, original?.route == true,
            runCatching { audio?.mode }.getOrNull(), communication?.id, communication?.type,
            original?.cleanupSucceeded, failureStage, failureClass)
        // Observer failures cannot trigger audio writes or recursive status delivery.
        runCatching { onStatus(latest) }
    }

    private fun failed(stage: String, error: Throwable) {
        var cause = error
        while (cause is InvocationTargetException && cause.cause != null) cause = cause.cause!!
        failureStage = stage
        failureClass = cause.javaClass.name
    }

    private fun voiceSource(source: Int) = source in setOf(
        MediaRecorder.AudioSource.DEFAULT, MediaRecorder.AudioSource.MIC,
        MediaRecorder.AudioSource.CAMCORDER, MediaRecorder.AudioSource.VOICE_RECOGNITION,
        MediaRecorder.AudioSource.VOICE_COMMUNICATION, MediaRecorder.AudioSource.UNPROCESSED,
        MediaRecorder.AudioSource.VOICE_PERFORMANCE,
    )

    private class ShellApplicationContext(base: Context) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
    }

    private fun shellContext(): Context {
        val type = Class.forName("android.app.ActivityThread")
        val activityThread = type.getMethod("currentActivityThread").invoke(null)
            ?: type.getMethod("systemMain").invoke(null)
        val system = type.getMethod("getSystemContext").invoke(activityThread) as Context
        val shellPackage = system.createPackageContext("com.android.shell", 0)
        val implementation = Class.forName("android.app.ContextImpl")
        val loadedApk = Class.forName("android.app.LoadedApk")
        val loadedShell = implementation.getDeclaredField("mPackageInfo").apply { isAccessible = true }
            .get(shellPackage)
        val shell = implementation.getDeclaredMethod("createAppContext", type, loadedApk)
            .apply { isAccessible = true }.invoke(null, activityThread, loadedShell) as Context
        return ShellApplicationContext(shell)
    }
}
