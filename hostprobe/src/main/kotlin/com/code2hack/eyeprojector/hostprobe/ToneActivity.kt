package com.code2hack.eyeprojector.hostprobe

import android.app.Activity
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Bundle
import android.widget.TextView
import java.util.concurrent.atomic.AtomicBoolean
import java.io.File
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.sin

/** Separate owned app: quiet 440Hz tone for whole-device mixing/focus comparison. No microphone. */
class ToneActivity : Activity() {
    private val running = AtomicBoolean(true)
    private var track: AudioTrack? = null
    private var worker: Thread? = null
    private var focus: AudioFocusRequest? = null
    private var focusResult = -1
    private fun record(state: String) {
        File(filesDir, "tone-status.json").writeText(JSONObject().put("state", state).put("frequencyHz", 440)
            .put("focusRequested", intent.getBooleanExtra("focus", false)).put("focusResult", focusResult)
            .put("displayId", display?.displayId).put("phoneMonoNs", System.nanoTime()).toString())
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val manager = getSystemService(AudioManager::class.java)
        val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()
        if (intent.getBooleanExtra("focus", false)) {
            focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(attributes)
                .setOnAudioFocusChangeListener { if (it != AudioManager.AUDIOFOCUS_GAIN) finish() }.build()
            focusResult = manager.requestAudioFocus(focus!!)
        }
        setContentView(TextView(this).apply { text = "Owned second app: quiet 440 Hz tone\nTap to stop"; setOnClickListener { finish() } })
        val audio = AudioTrack.Builder().setAudioAttributes(attributes).setAudioFormat(AudioFormat.Builder()
            .setSampleRate(48_000).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(9600).setTransferMode(AudioTrack.MODE_STREAM).build()
        track = audio; audio.play(); record("PLAYING")
        worker = Thread({
            val block = ShortArray(4800) { (655 * sin(2 * PI * 440 * it / 48_000)).toInt().toShort() }
            while (running.get()) if (audio.write(block, 0, block.size) < 0) break
        }, "eyeprojector-owned-tone").apply { start() }
    }
    override fun onStop() {
        record("STOPPED"); running.set(false); track?.pause(); track?.flush(); worker?.join(500); track?.release(); track = null
        focus?.let { getSystemService(AudioManager::class.java).abandonAudioFocusRequest(it) }; focus = null
        super.onStop()
    }
}
