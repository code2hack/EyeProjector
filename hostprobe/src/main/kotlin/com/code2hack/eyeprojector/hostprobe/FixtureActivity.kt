package com.code2hack.eyeprojector.hostprobe

import android.app.Activity
import android.app.ActivityOptions
import android.content.Intent
import android.graphics.Color
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.VideoView

/** Owned text/media with an explicit ordinary-settings launch on the current display. */
class FixtureActivity : Activity() {
    private lateinit var video: VideoView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                view.setPadding(16 + bars.left, 16 + bars.top, 16 + bars.right, 16 + bars.bottom)
                insets
            }
        }
        val status = TextView(this).apply {
            text = "EyeProjector — owned fixture"
            setTextColor(Color.GREEN)
        }
        root.addView(status)
        root.addView(EditText(this).apply {
            id = R.id.owned_text
            hint = "Owned text only"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.LTGRAY)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            maxLines = 2
            setSelectAllOnFocus(false)
        })
        video = VideoView(this).apply {
            id = R.id.owned_video
            setOnInfoListener { _, what, _ ->
                if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) status.text = "PLAYING owned clip"
                false
            }
            setOnCompletionListener { status.text = "Owned clip finished" }
            setOnErrorListener { _, what, extra ->
                status.text = "Owned clip error $what/$extra"
                true
            }
        }
        root.addView(Button(this).apply {
            id = R.id.play_owned_clip
            text = "Play owned flash/beep"
            setOnClickListener {
                video.setVideoURI(Uri.parse("android.resource://$packageName/${R.raw.owned_flash_beep}"))
                video.start()
            }
        })
        root.addView(Button(this).apply {
            id = R.id.stop_owned_clip
            text = "Stop owned clip"
            setOnClickListener {
                video.stopPlayback()
                status.text = "Owned clip stopped"
            }
        })
        fun openHere(intent: Intent) {
            val currentDisplay = display ?: return
            try {
                startActivity(intent, ActivityOptions.makeBasic()
                    .setLaunchDisplayId(currentDisplay.displayId).toBundle())
            } catch (error: RuntimeException) {
                status.text = "Launch failed: ${error.javaClass.simpleName}"
            }
        }
        root.addView(LinearLayout(this).apply {
            addView(Button(context).apply {
                text = "Display settings"
                setOnClickListener {
                    openHere(Intent(Settings.ACTION_DISPLAY_SETTINGS)
                        .setPackage("com.android.settings"))
                }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        })
        root.addView(video, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    override fun onStop() {
        video.stopPlayback()
        super.onStop()
    }
}
