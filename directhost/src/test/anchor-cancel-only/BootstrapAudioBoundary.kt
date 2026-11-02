package com.code2hack.eyeprojector.directhost

/** Unrelated Bluetooth/audio service is an explicit no-hardware boundary for normal bootstrap proof. */
internal class ActiveHeadsetSco(private val changed:()->Unit) {
    fun resolve(context:Any?,outputs:List<Any>):Any?=null
    fun status()=org.json.JSONObject().put("hostBoundary",true)
    fun close()=Unit
}
internal class GlassesMicrophoneRoute(private val resolve:(Any?,List<Any>)->Any?) {
    data class Recording(val sessionId:Int=0,val clientSource:Int=0,val inputId:Int?=null,val inputType:Int?=null,val silenced:Boolean=false)
    data class Status(val phase:String="HOST_BOUNDARY",val enabled:Boolean=false,val observedMode:Int?=null,
        val observedCommunicationId:Int?=null,val routeAccepted:Boolean?=null,val routeReleaseOutstanding:Boolean=false,
        val modeReleaseOutstanding:Boolean=false,val failureStage:String?=null,val failureClass:String?=null,
        val recordings:List<Recording> = emptyList())
    fun start()=Unit
    fun setEnabled(value:Boolean)=Unit
    fun refresh()=Unit
    fun status()=Status()
    fun close()=Unit
}
