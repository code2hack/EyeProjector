package com.code2hack.eyeprojector.directrg
import com.code2hack.eyeprojector.protocol.*
internal class ProjectionSession
internal object NativePadReader { const val SOCKET_NAME="test-original-pad";const val POWER_SOCKET_NAME="test-original-power" }
internal data class PointerPosition(val x:Float,val y:Float,val available:Boolean)
internal class HeldInputOwner {
    fun accept(owner:Any,event:PadGestureRecognizer.Event):Boolean=error("Ordinary pad is outside this Function test")
    fun cancel(owner:Any,at:Long)=Unit
}
internal object PadGestureRecognizer {
    enum class Key { HELD_CONTACT }; enum class Phase { DOWN,UP }
    data class Event(val key:Key,val phase:Phase,val timeMs:Long,val downTimeMs:Long,val position:PointerPosition?)
}
