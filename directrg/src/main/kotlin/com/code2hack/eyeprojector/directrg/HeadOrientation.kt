package com.code2hack.eyeprojector.directrg

// Input primitive adapted from v0.0.4@03de921e2400147842ce6c56101b58d514dde77c.

import kotlin.math.*

/** Normalized rotation and display-axis projection for the local pointer. */
internal data class HeadOrientation(val x: Double,val y: Double,val z: Double,val w: Double) {
    fun inverse() = HeadOrientation(-x,-y,-z,w)
    operator fun times(b: HeadOrientation) = HeadOrientation(
        w*b.x+x*b.w+y*b.z-z*b.y,w*b.y-x*b.z+y*b.w+z*b.x,
        w*b.z+x*b.y-y*b.x+z*b.w,w*b.w-x*b.x-y*b.y-z*b.z)
    fun angles(rotation: Int): Pair<Double,Double> {
        val deviceX=-2*(x*z+w*y);val deviceY=2*(w*x-y*z);val forwardZ=2*(x*x+y*y)-1
        val (right,up)=when(rotation) { 1->deviceY to -deviceX;2->-deviceX to -deviceY;3->-deviceY to deviceX;else->deviceX to deviceY }
        // This is the reference ray in the current head frame: aiming moves
        // opposite that ray, with display-axis remapping applied first.
        return Math.toDegrees(-atan2(right,-forwardZ)) to Math.toDegrees(atan2(up,hypot(right,forwardZ)))
    }
    companion object {
        fun from(sample: RotationSample): HeadOrientation? {
            val values=doubleArrayOf(sample.x.toDouble(),sample.y.toDouble(),sample.z.toDouble(),sample.w.toDouble())
            if(!values.all(Double::isFinite)) return null
            val norm=sqrt(values.sumOf { it*it });if(norm<1e-6)return null
            return HeadOrientation(values[0]/norm,values[1]/norm,values[2]/norm,values[3]/norm)
        }
    }
}
