package com.code2hack.eyeprojector.hostprobe

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.util.AtomicFile
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Owned controls and independent View geometry; no accessibility reader or automatic input. */
class UiTargetFixtureActivity : Activity() {
    private lateinit var root: LinearLayout
    private lateinit var click: Button
    private lateinit var hold: Button
    private lateinit var status: TextView
    private var resumed = false
    private var clicks = 0
    private var downs = 0
    private var ups = 0
    private var cancels = 0
    private var holding = false
    private var runId = "unspecified"
    private var revision = 0L
    private var lastEdge = "NONE"
    private var lastEdgeTimeMs: Long? = null
    private val publish = Runnable { writeObservation() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.getBooleanExtra("finishFixture", false)) {
            finish()
            return
        }
        runId = intent.getStringExtra("runId") ?: "unspecified"
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                view.setPadding(24 + bars.left, 24 + bars.top, 24 + bars.right, 24 + bars.bottom)
                insets
            }
        }
        root.addView(TextView(this).apply {
            text = "Owned UI access fixture"
            textSize = 20f
            setTextColor(Color.WHITE)
        })
        click = Button(this).apply {
            id = R.id.ui_target_click
            text = "Click"
            setOnClickListener {
                clicks++
                edge("CLICK")
            }
        }
        hold = Button(this).apply {
            id = R.id.ui_target_hold
            text = "Hold while touched"
            isClickable = true
            // A sustained touch control. No long-click handler or semantic Hold inference.
            setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downs++
                        holding = true
                        view.isPressed = true
                        edge("DOWN")
                    }
                    MotionEvent.ACTION_UP -> {
                        ups++
                        holding = false
                        view.isPressed = false
                        edge("UP")
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        cancels++
                        holding = false
                        view.isPressed = false
                        edge("CANCEL")
                    }
                }
                true
            }
        }
        root.addView(click)
        root.addView(hold)
        status = TextView(this).apply { setTextColor(Color.GREEN) }
        root.addView(status)
        for (view in listOf(root, click, hold)) {
            view.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> scheduleObservation() }
        }
        setContentView(root)
        updateStatus()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra("finishFixture", false)) finish()
        else if (::root.isInitialized) scheduleObservation()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        if (::root.isInitialized) scheduleObservation()
    }

    override fun onPause() {
        resumed = false
        if (::root.isInitialized) {
            root.removeCallbacks(publish)
            writeObservation()
        }
        super.onPause()
    }

    override fun onDestroy() {
        if (::root.isInitialized) root.removeCallbacks(publish)
        super.onDestroy()
    }

    private fun edge(value: String) {
        lastEdge = value
        lastEdgeTimeMs = SystemClock.elapsedRealtime()
        updateStatus()
        scheduleObservation()
    }

    private fun updateStatus() {
        status.text = "Clicks $clicks; DOWN $downs; UP $ups; CANCEL $cancels; held $holding"
    }

    private fun scheduleObservation() {
        root.removeCallbacks(publish)
        root.post(publish)
    }

    private fun geometry(view: View): JSONObject {
        val screen = IntArray(2)
        val inWindow = IntArray(2)
        view.getLocationOnScreen(screen)
        view.getLocationInWindow(inWindow)
        return JSONObject()
            .put("viewIdResourceName", resources.getResourceName(view.id))
            .put("displayId", view.display?.displayId ?: JSONObject.NULL)
            .put("layoutBoundsOnScreen", JSONArray(listOf(screen[0], screen[1],
                screen[0] + view.width, screen[1] + view.height)))
            .put("layoutBoundsInWindow", JSONArray(listOf(inWindow[0], inWindow[1],
                inWindow[0] + view.width, inWindow[1] + view.height)))
            .put("shown", view.isShown).put("attached", view.isAttachedToWindow)
            .put("laidOut", view.isLaidOut)
    }

    private fun writeObservation() {
        val report = JSONObject()
            .put("schema", "eyeprojector.ui-target-fixture.v1")
            .put("runId", runId).put("layoutRevision", ++revision)
            .put("observedElapsedRealtimeMs", SystemClock.elapsedRealtime())
            .put("activityResumed", resumed).put("displayId", display?.displayId ?: JSONObject.NULL)
            .put("controls", JSONArray().put(geometry(click)).put(geometry(hold)))
            .put("clickCount", clicks).put("holdDownCount", downs).put("holdUpCount", ups)
            .put("holdCancelCount", cancels).put("holding", holding)
            .put("lastEdge", lastEdge).put("lastEdgeElapsedRealtimeMs", lastEdgeTimeMs ?: JSONObject.NULL)
            .put("coordinateSpace", "View.getLocationOnScreen plus untransformed width/height; no encoded-frame association")
        val file = AtomicFile(File(filesDir, "ui-target-fixture.json"))
        val stream = file.startWrite()
        try {
            stream.write((report.toString(2) + "\n").toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
    }
}
