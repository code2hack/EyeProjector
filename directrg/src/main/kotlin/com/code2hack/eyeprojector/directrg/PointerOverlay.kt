package com.code2hack.eyeprojector.directrg

// Input primitive adapted from v0.0.4@03de921e2400147842ce6c56101b58d514dde77c.

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Looper
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View

/** Local projector cursor. Never captures touches or waits for a remote video frame. */
class PointerOverlay(context: Context, attrs: AttributeSet?=null) : View(context,attrs) {
    private val model=HeadPointerModel()
    private val source: HeadPoseSource=SensorHeadPoseSource(context)
    private var running=false
    private var generation=0L
    private var pendingFrame=false
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private val radius=6f
    val position get() = model.position
    internal var visibleBounds = PointerBounds(0f,0f,0f,0f)
        private set
    internal val cursorVisible get() = position.y>=visibleBounds.top && position.y<visibleBounds.bottom
    internal val expiresAtNs get() = model.expiresAtNs
    internal var onPositionChanged: (() -> Unit)? = null
    internal fun sensitivity(xGain: Double, yGain: Double) {
        model.sensitivity(xGain,yGain,SystemClock.elapsedRealtimeNanos());publish();requestFrame()
    }
    val sourceRegistered get() = source.registered
    val sourceDescription get() = source.description
    internal var acceptedSamples=0L
        private set
    internal var lastSampleReceiptNs=0L
        private set
    internal var lastDrawElapsedNs=0L
        private set
    internal var drawCount=0L
        private set

    init { isClickable=false;isFocusable=false;importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO }

    fun bounds(left: Float, top: Float, right: Float, bottom: Float, rotation: Int) {
        visibleBounds=PointerBounds(left,top,right,bottom)
        // Preserve the old visible-span gain; padding extends only the logical vertical limits.
        val inset=minOf(8f,
            (right-left).coerceAtLeast(0f)/2,(bottom-top).coerceAtLeast(0f)/2)
        model.resize(PointerBounds(left+inset,top+inset,right-inset,bottom-inset),rotation,inset+24f)
        publish();requestFrame()
    }

    fun start() {
        check(Looper.myLooper()==Looper.getMainLooper())
        if (running) return
        running=true;model.start()
        val registration=++generation
        source.start onSample@{ sample ->
            check(Looper.myLooper()==Looper.getMainLooper())
            if (!running || registration!=generation) return@onSample
            val receipt=SystemClock.elapsedRealtimeNanos()
            val before=position
            if (model.sample(sample,receipt)) {
                acceptedSamples++;lastSampleReceiptNs=receipt
                publish()
                if (position!=before || model.settling) requestFrame()
                scheduleExpiry()
            }
        }
        publish();requestFrame()
    }

    fun stop() {
        check(Looper.myLooper()==Looper.getMainLooper())
        running=false;generation++
        source.stop();removeCallbacks(frame);removeCallbacks(expire);pendingFrame=false
        model.stop();publish();invalidate()
    }

    /** Recheck age at admission even if the stale Handler callback has been delayed. */
    internal fun inputPosition(): PointerPosition {
        model.advance(SystemClock.elapsedRealtimeNanos());publish()
        return position
    }

    private fun publish() { onPositionChanged?.invoke() }
    private fun requestFrame() {
        if (running && !pendingFrame) { pendingFrame=true;postOnAnimation(frame) }
    }
    private val frame=Runnable {
        pendingFrame=false
        if (running) {
            model.advance(SystemClock.elapsedRealtimeNanos());publish();invalidate()
            if (model.settling) requestFrame()
        }
    }
    private val expire=Runnable {
        if (running) {
            model.advance(SystemClock.elapsedRealtimeNanos());publish();invalidate()
            if (position.available) scheduleExpiry()
        }
    }
    private fun scheduleExpiry() {
        removeCallbacks(expire)
        val remaining=model.expiresAtNs-SystemClock.elapsedRealtimeNanos()
        postDelayed(expire,((remaining+999_999)/1_000_000).coerceAtLeast(1))
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val point=position
        drawCount++;lastDrawElapsedNs=SystemClock.elapsedRealtimeNanos()
        if (!cursorVisible) return
        paint.style=Paint.Style.STROKE;paint.strokeWidth=3f;paint.color=Color.BLACK
        canvas.drawCircle(point.x,point.y,radius,paint)
        paint.strokeWidth=2f;paint.color=if(point.available) 0xfff2f2f2.toInt() else 0xff8a8a8a.toInt()
        canvas.drawCircle(point.x,point.y,radius,paint)
        paint.style=Paint.Style.FILL
        canvas.drawCircle(point.x,point.y,2f,paint)
    }
    override fun onDetachedFromWindow() { stop();super.onDetachedFromWindow() }
}
