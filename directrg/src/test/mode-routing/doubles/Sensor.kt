package com.code2hack.eyeprojector.directrg
import android.content.Context
/** Raw sensor acquisition only; actual pointer model, fit and overlay execute. */
class SensorHeadPoseSource(context:Context) {
    val description="host acquisition boundary"
    var registered=false
    fun start(consumer:(RotationSample)->Unit) { registered=true }
    fun stop() { registered=false }
}
typealias HeadPoseSource=SensorHeadPoseSource
