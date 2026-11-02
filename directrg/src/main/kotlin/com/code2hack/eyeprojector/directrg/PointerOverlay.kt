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
import com.code2hack.eyeprojector.protocol.*

/** Local projector cursor. Never captures touches or waits for a remote video frame. */
class PointerOverlay(context: Context, attrs: AttributeSet?=null) : View(context,attrs) {
    private val model=HeadPointerModel()
    private val source: HeadPoseSource=SensorHeadPoseSource(context)
    private var running=false
    private var generation=0L
    private var pendingFrame=false
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private val radius=6f
    private var coordinateMode=CoordinateAnchorState()
    private var canvasGeometry: Geometry?=null
    internal val anchorMode get() = coordinateMode.mode == AnchorInteractionMode.ANCHOR
    val position get() = coordinateMode.selectedPosition?.let(::canvasPosition) ?: model.position
    internal fun coordinateState(state: CoordinateAnchorState,geometry: Geometry?,projected: Boolean) {
        val next=if(projected) state else CoordinateAnchorState()
        if(next == coordinateMode && geometry == canvasGeometry) return
        coordinateMode=next;canvasGeometry=geometry
        publish();invalidate()
    }
    private fun canvasPosition(point: AnchorPosition): PointerPosition {
        val size=canvasGeometry ?: return model.position
        return PointerPosition(visibleBounds.left+point.x.toFloat().coerceIn(0f,(size.width-1).toFloat())*visibleBounds.width/size.width,
            visibleBounds.top+point.y.toFloat().coerceIn(0f,(size.height-1).toFloat())*visibleBounds.height/size.height,true)
    }
    internal var visibleBounds = PointerBounds(0f,0f,0f,0f)
        private set
    internal val cursorVisible get() = position.y>=visibleBounds.top && position.y<visibleBounds.bottom
    internal val expiresAtNs get() = if(anchorMode) Long.MAX_VALUE else model.expiresAtNs
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

    /** Capture the same current model/age without publishing callbacks before a user-control decision. */
    internal fun inputSnapshot(): PointerPosition { model.advance(SystemClock.elapsedRealtimeNanos());return position }

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
        if(coordinateMode.mode == AnchorInteractionMode.ANCHOR_SETTINGS) {
            coordinateMode.positions.forEachIndexed { index,saved ->
                val marker=canvasPosition(saved)
                paint.style=Paint.Style.FILL;paint.color=Color.YELLOW;paint.textSize=22f
                canvas.drawCircle(marker.x,marker.y,4f,paint)
                canvas.drawText((index+1).toString(),marker.x+7f,marker.y-7f,paint)
            }
        }
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
