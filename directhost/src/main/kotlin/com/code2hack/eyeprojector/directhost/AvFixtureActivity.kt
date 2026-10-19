package com.code2hack.eyeprojector.directhost

import android.app.Activity
import android.graphics.Color
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.VideoView
import java.io.File
import org.json.JSONObject

/** Owned 12s authored flash/1kHz pulse, looped by the ordinary Android media player. */
class AvFixtureActivity : Activity() {
    private lateinit var video: VideoView
    private var player: MediaPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var began = 0L
    private fun record(playing: Boolean = video.isPlaying) {
        val timestamp = player?.timestamp
        File(filesDir, "av-fixture.json").writeText(JSONObject().put("displayId", display?.displayId)
            .put("elapsedMs", SystemClock.elapsedRealtime() - began).put("playing", playing)
            .put("mediaTimeUs", timestamp?.anchorMediaTimeUs).put("sourceMonoNs", timestamp?.anchorSytemNanoTime)
            .put("clockRate", timestamp?.mediaClockRate).toString())
    }
    private val sample = object : Runnable {
        override fun run() { record(); handler.postDelayed(this, 1000) }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK) }
        root.addView(TextView(this).apply { text = "EyeProjector flash / 1 kHz pulse"; setTextColor(Color.GREEN) })
        video = VideoView(this).apply {
            setOnPreparedListener { media ->
                player = media; media.isLooping = true; media.setVolume(.25f, .25f)
                start()
            }
            setVideoURI(Uri.parse("android.resource://$packageName/${R.raw.owned_flash_beep}"))
        }
        root.addView(Button(this).apply { text = "Pause / resume owned clip"; setOnClickListener { if (video.isPlaying) video.pause() else video.start() } })
        root.addView(video, LinearLayout.LayoutParams(-1, 0, 1f)); setContentView(root)
        began = SystemClock.elapsedRealtime(); handler.post(sample)
    }
    override fun onStop() {
        record(false); handler.removeCallbacksAndMessages(null); player = null; video.stopPlayback()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); super.onStop()
    }
}
