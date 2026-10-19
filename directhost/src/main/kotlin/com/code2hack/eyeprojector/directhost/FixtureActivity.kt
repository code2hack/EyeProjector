package com.code2hack.eyeprojector.directhost

import android.app.Activity
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.os.SystemClock
import android.util.AtomicFile
import android.view.MotionEvent
import android.view.View
import java.io.File
import org.json.JSONObject

/** Harmless owned motion and visible tap effect. This fixture does not prove third-party app compatibility. */
class FixtureActivity : Activity() {
    private var taps = 0
    private var frames = 0L
    private var active = false
    private fun record() {
        val data = JSONObject().put("displayId", display?.displayId).put("taps", taps).put("drawnFrames", frames)
            .put("active", active).put("elapsedRealtimeMs", SystemClock.elapsedRealtime())
        val file = AtomicFile(File(filesDir, "fixture-status.json"))
        val out = file.startWrite()
        try { out.write(data.toString().toByteArray()); file.finishWrite(out) }
        catch (error: Exception) { file.failWrite(out); throw error }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        record()
        setContentView(object : View(this) {
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 28f }
            override fun onDraw(canvas: Canvas) {
                frames++; if (frames % 30 == 0L) record()
                canvas.drawColor(Color.BLACK)
                paint.color = Color.WHITE
                canvas.drawText("EyeProjector motion fixture", 16f, 60f, paint)
                canvas.drawText("Visible taps: $taps", 16f, 120f, paint)
                paint.color = Color.CYAN
                val x = ((System.nanoTime() / 1_000_000 % 3000) * (width - 40).coerceAtLeast(1) / 3000).toFloat()
                canvas.drawCircle(x + 20, height / 2f, 18f, paint)
                postInvalidateDelayed(33)
            }
            override fun onTouchEvent(event: MotionEvent): Boolean {
                if (event.action == MotionEvent.ACTION_UP) { taps++; contentDescription = "Visible taps: $taps"; performClick(); invalidate(); record() }
                return true
            }
            override fun performClick(): Boolean { super.performClick(); return true }
        })
    }
    override fun onStart() { super.onStart(); active = true; record() }
    override fun onStop() { active = false; record(); super.onStop() }
}
