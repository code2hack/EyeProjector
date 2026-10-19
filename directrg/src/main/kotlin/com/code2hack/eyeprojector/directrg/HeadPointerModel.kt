package com.code2hack.eyeprojector.directrg

// Input primitive adapted from v0.0.4@03de921e2400147842ce6c56101b58d514dde77c.

import kotlin.math.*

/** Copied raw Android rotation-vector components, not Euler yaw/pitch or screen coordinates. */
data class RotationSample(val timestampNs: Long, val x: Float, val y: Float, val z: Float, val w: Float)
data class PointerPosition(val x: Float, val y: Float, val available: Boolean)
data class PointerBounds(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    init { require(listOf(left,top,right,bottom).all(Float::isFinite) && right >= left && bottom >= top) }
    val width get() = right-left
    val height get() = bottom-top
}

/** Position aiming only. No input dispatch, browser state, transport or Android dependency. */
class HeadPointerModel(val settings: Settings = Settings()) {
    data class Settings(
        val horizontalHalfRangeDegrees: Double = 30.0,
        val verticalHalfRangeDegrees: Double = 22.0,
        val deadbandDegrees: Double = 0.3,
        val smoothingNs: Long = 60_000_000L,
        val maxSpanPerSecond: Double = 3.0,
        val staleNs: Long = 250_000_000L,
    ) {
        init {
            require(horizontalHalfRangeDegrees in 5.0..90.0 && verticalHalfRangeDegrees in 5.0..90.0)
            require(deadbandDegrees in 0.0..2.0 && smoothingNs in 5_000_000L..500_000_000L)
            require(maxSpanPerSecond in 0.1..10.0 && staleNs in 50_000_000L..1_000_000_000L)
        }
    }
    private var bounds = PointerBounds(0f,0f,0f,0f)
    private var verticalPadding = 0f
    val motionBounds get() = bounds.copy(top=bounds.top-verticalPadding,bottom=bounds.bottom+verticalPadding)
    private val minY get() = if (bounds.height>0) -verticalPadding.toDouble()/bounds.height else 0.0
    private val maxY get() = 1.0-minY
    private var rotation = 0
    private var running = false
    private var reference: HeadOrientation? = null
    private var sourceTimeNs = 0L
    private var frameTimeNs = 0L
    private var x = .5
    private var y = .5
    private var targetX = .5
    private var targetY = .5
    private var fresh = false
    private var yaw = 0.0
    private var pitch = 0.0
    private var quietYaw = 0.0
    private var quietPitch = 0.0
    var sensitivity = 2.0
        private set
    val position get() = PointerPosition((bounds.left+x*bounds.width).toFloat(),
        (bounds.top+y*bounds.height).toFloat(),running && fresh && bounds.width>0 && bounds.height>0)
    val settling get() = position.available && (abs(targetX-x)*bounds.width > .05 || abs(targetY-y)*bounds.height > .05)
    val expiresAtNs get() = sourceTimeNs + settings.staleNs

    fun start() { stop();running=true }
    fun stop() {
        running=false;fresh=false;reference=null;sourceTimeNs=0;frameTimeNs=0
        targetX=x;targetY=y
    }
    fun resize(value: PointerBounds, displayRotation: Int, verticalPadding: Float = 0f) {
        require(displayRotation in 0..3)
        require(verticalPadding.isFinite() && verticalPadding>=0)
        if (displayRotation!=rotation) { reference=null;fresh=false;x=.5;y=.5;targetX=.5;targetY=.5 }
        rotation=displayRotation;bounds=value;this.verticalPadding=verticalPadding
        y=y.coerceIn(minY,maxY);targetY=targetY.coerceIn(minY,maxY)
    }

    /** nowNs and sample.timestampNs both use elapsed realtime, including sleep. */
    fun sample(sample: RotationSample, nowNs: Long): Boolean {
        if (!running || nowNs<=0 || sample.timestampNs<=sourceTimeNs || sample.timestampNs>nowNs ||
            nowNs-sample.timestampNs>=settings.staleNs) return false
        val current=HeadOrientation.from(sample) ?: return false
        val rebase=reference==null || !fresh || sample.timestampNs-sourceTimeNs>=settings.staleNs
        sourceTimeNs=sample.timestampNs;fresh=true
        if (rebase) {
            reference=current;x=.5;y=.5;targetX=.5;targetY=.5;frameTimeNs=nowNs
            yaw=0.0;pitch=0.0;quietYaw=0.0;quietPitch=0.0
        } else {
            // Express the reference ray in the current head frame. The inverse order
            // keeps using the startup axes and can reverse/swap aiming after roll.
            aim(current.inverse()*checkNotNull(reference))
        }
        return true
    }

    private fun aim(q: HeadOrientation) {
        val (nextYaw,nextPitch)=q.angles(rotation)
        fun delta(next: Double, previous: Double) = (next-previous+540.0)%360.0-180.0
        fun axis(next: Double, previous: Double, quiet: Double, current: Double,
                 target: Double, halfRange: Double, minimum: Double, maximum: Double): Pair<Double,Double> {
            val movement=delta(next,previous)
            // Discard angular excess at saturation and filtered travel against the new direction.
            // Even a slow first inward sample moves immediately, independent of the old overshoot.
            val reversing=movement*(target-current)<0
            if (target==minimum || target==maximum || reversing) {
                val origin=if (reversing) current else target
                return (origin+movement*sensitivity/(2*halfRange)).coerceIn(minimum,maximum) to next
            }
            val travel=delta(next,quiet)
            val accepted=sign(travel)*max(0.0,abs(travel)-settings.deadbandDegrees)
            return (target+accepted*sensitivity/(2*halfRange)).coerceIn(minimum,maximum) to (quiet+accepted)
        }
        val horizontal=axis(nextYaw,yaw,quietYaw,x,targetX,settings.horizontalHalfRangeDegrees,0.0,1.0)
        val vertical=axis(nextPitch,pitch,quietPitch,y,targetY,settings.verticalHalfRangeDegrees,minY,maxY)
        targetX=horizontal.first;quietYaw=horizontal.second;yaw=nextYaw
        targetY=vertical.first;quietPitch=vertical.second;pitch=nextPitch
    }

    /** Change future gain without moving the cursor or retaining an old filtered destination. */
    fun sensitivity(value: Double, nowNs: Long) {
        require(value in .1..6.0)
        sensitivity=value;targetX=x;targetY=y;quietYaw=yaw;quietPitch=pitch;frameTimeNs=nowNs
    }

    /** Advance on RG display frames, independent of the remote video cadence. */
    fun advance(nowNs: Long): PointerPosition {
        if (!running || sourceTimeNs==0L || nowNs-sourceTimeNs>=settings.staleNs) {
            fresh=false;return position // Freeze; the next good sample establishes a new reference.
        }
        if (nowNs<=frameTimeNs) return position
        val dt=(nowNs-frameTimeNs).coerceAtMost(50_000_000L);frameTimeNs=nowNs
        val alpha=1-exp(-dt.toDouble()/settings.smoothingNs)
        var dx=(targetX-x)*alpha;var dy=(targetY-y)*alpha
        val distance=hypot(dx,dy);val limit=settings.maxSpanPerSecond*sensitivity*dt/1e9
        if (distance>limit) { dx*=limit/distance;dy*=limit/distance }
        x=(x+dx).coerceIn(0.0,1.0);y=(y+dy).coerceIn(minY,maxY)
        if (!settling) { x=targetX;y=targetY }
        return position
    }

}
