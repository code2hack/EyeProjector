package com.code2hack.eyeprojector.hostprobe

import android.app.Activity
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.TextView
import java.io.File
import org.json.JSONObject

/** Explicit foreground test capture. No audio samples are read, saved or forwarded. */
class MicrophoneRouteActivity : Activity() {
    private var probe: MicrophoneRouteProbe? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val label = TextView(this).apply { text = "Microphone route probe: no audio is stored" }
        setContentView(label)
        probe = MicrophoneRouteProbe(this) { phase, input ->
            label.text = "Microphone route probe: $phase\nInput type: ${input ?: "unobserved"}\nNo audio is stored"
        }.also { it.start() }
    }
    override fun onPause() { probe?.close(); super.onPause() }
    override fun onDestroy() { probe?.close(); super.onDestroy() }
}

/** Used only by the explicit owned fixture; it creates a real capture but never calls read(). */
internal class MicrophoneRouteProbe(
    private val context: Context,
    private val observed: (String, Int?) -> Unit = { _, _ -> },
) {
    private val handler = Handler(Looper.getMainLooper())
    private var recorder: AudioRecord? = null
    private var startedMs = 0L
    private var terminal = false
    private var startFailure: String? = null
    private val poll = object : Runnable {
        override fun run() {
            if (terminal) return
            if (SystemClock.uptimeMillis() - startedMs >= 10_000) close()
            else { report("RECORDING"); handler.postDelayed(this, 250) }
        }
    }

    fun start() {
        try {
            val format = AudioFormat.Builder().setSampleRate(16_000)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()
            val minimum = AudioRecord.getMinBufferSize(16_000, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT)
            val original = AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioFormat(format).setBufferSizeInBytes(maxOf(minimum, 3_200)).build()
            recorder = original
            original.startRecording()
            startedMs = SystemClock.uptimeMillis()
            handler.post(poll)
        } catch (error: Exception) {
            startFailure = error.javaClass.simpleName
            report("START_FAILED", startFailure)
            close()
        }
    }

    private fun report(phase: String, failure: String? = null) {
        val original = recorder
        val device = runCatching { original?.routedDevice }.getOrNull()
        val value = JSONObject().put("phase", phase).put("sampleUptimeMs", SystemClock.uptimeMillis())
            .put("sessionId", original?.audioSessionId ?: JSONObject.NULL)
            .put("recordingState", original?.recordingState ?: JSONObject.NULL)
            .put("inputId", device?.id ?: JSONObject.NULL).put("inputType", device?.type ?: JSONObject.NULL)
            .put("inputIsSource", device?.isSource ?: JSONObject.NULL)
            .put("mode", context.getSystemService(AudioManager::class.java).mode)
            .put("failureClass", failure ?: JSONObject.NULL).put("audioSamplesRead", 0)
        File(context.filesDir, "microphone-route.json").writeText(value.toString())
        observed(phase, device?.type)
    }

    fun close() {
        if (terminal) return
        terminal = true
        handler.removeCallbacks(poll)
        val original = recorder
        var failure: String? = null
        try { if (original?.recordingState == AudioRecord.RECORDSTATE_RECORDING) original.stop() }
        catch (error: Exception) { failure = error.javaClass.simpleName }
        finally {
            try { original?.release() }
            catch (error: Exception) { failure = failure ?: error.javaClass.simpleName }
            recorder = null
        }
        report(when {
            startFailure != null -> "START_FAILED"
            failure != null -> "CLOSE_FAILED"
            else -> "CLOSED"
        }, failure ?: startFailure)
    }

}
