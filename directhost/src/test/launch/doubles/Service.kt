package com.code2hack.eyeprojector.directhost
interface IHostBootstrap {
    fun phoneHidEdge(routeId: Long, typedEdge: ByteArray, expectedOwnerEpoch: String, expectedProjectionToken: String) {}
    fun status():String
    fun start(identity:ByteArray,password:ByteArray,legacyPeer:ByteArray?,endpoint:ByteArray,pairedPeer:ByteArray?,sessionEpoch:String):String
    fun stop():String
    fun requestScale(requestId:Long,scale:Double,expectedOwnerEpoch:String,expectedProjectionToken:String):ByteArray
    fun scaleState(expectedOwnerEpoch:String,expectedProjectionToken:String):ByteArray
    fun requestPanelOff(requestId:Long,expectedOwnerEpoch:String,expectedProjectionToken:String):ByteArray
    fun panelState(expectedOwnerEpoch:String,expectedProjectionToken:String):ByteArray
    fun launchApp(requestId:Long,packageName:String,expectedOwnerEpoch:String,expectedProjectionToken:String):ByteArray
    fun projectionDisplayState(expectedOwnerEpoch:String,expectedProjectionToken:String):ByteArray
    fun destroy()
    abstract class Stub:IHostBootstrap
}
