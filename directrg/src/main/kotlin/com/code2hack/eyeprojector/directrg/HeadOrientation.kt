package com.code2hack.eyeprojector.directrg

// Input primitive adapted from v0.0.4@03de921e2400147842ce6c56101b58d514dde77c.

import kotlin.math.*

/** Normalized sensor rotation and device-local angular motion for the pointer. */
internal data class HeadOrientation(val x: Double,val y: Double,val z: Double,val w: Double) {
    fun inverse() = HeadOrientation(-x,-y,-z,w)
    operator fun times(b: HeadOrientation) = HeadOrientation(
        w*b.x+x*b.w+y*b.z-z*b.y,w*b.y-x*b.z+y*b.w+z*b.x,
        w*b.z+x*b.y-y*b.x+z*b.w,w*b.w-x*b.x-y*b.y-z*b.z)
    /** This quaternion is current.inverse() * previous, not a fixed startup ray. */
    fun motion(rotation: Int): Pair<Double,Double> {
        // q and -q describe the same rotation. Use the shorter increment even
        // when a sensor changes quaternion sign between otherwise equal poses.
        val length=sqrt(x*x+y*y+z*z)
        val scale=if (length==0.0) 0.0 else 2*atan2(length,abs(w))/length
        // At exactly 180° w can be either signed zero; choose a consistent
        // representative using the first nonzero vector component as the tie-break.
        val leading=when { w!=0.0->w;x!=0.0->x;y!=0.0->y;else->z }
        val sign=if (leading<0) -1 else 1
        val right=Math.toDegrees(sign*y*scale)
        val down=Math.toDegrees(sign*x*scale)
        return when(rotation) { 1->-down to right;2->-right to -down;3->down to -right;else->right to down }
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
