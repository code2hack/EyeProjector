package com.code2hack.eyeprojector.directhost
/** Matched typed Binder input API; transport is a host double, all downstream routing is actual. */
interface IHostBootstrap {
    fun phoneHidSnapshot(expectedOwnerEpoch:String,expectedProjectionToken:String):com.code2hack.eyeprojector.directhost.activation.snapshot.NativeSnapshotDescriptor?=null
    fun phoneHidStampedEdge(routeId:Long,typedEdge:ByteArray,expectedOwnerEpoch:String,expectedProjectionToken:String,regionId:String,word:Long) {}

    fun phoneHidEdge(routeId: Long, typedEdge: ByteArray, expectedOwnerEpoch: String, expectedProjectionToken: String) {}
 fun status(): String
 fun start(identity: ByteArray, password: ByteArray, legacy: ByteArray?, endpoint: ByteArray, paired: ByteArray?, sessionEpoch: String): String
 fun stop(): String
 fun destroy()
 fun requestScale(requestId: Long, scale: Double, expectedOwnerEpoch: String, expectedProjectionToken: String): ByteArray
 fun scaleState(expectedOwnerEpoch: String, expectedProjectionToken: String): ByteArray
 fun requestPanelOff(requestId: Long, expectedOwnerEpoch: String, expectedProjectionToken: String): ByteArray
 fun panelState(expectedOwnerEpoch: String, expectedProjectionToken: String): ByteArray
 fun launchApp(requestId: Long, packageName: String, expectedOwnerEpoch: String, expectedProjectionToken: String): ByteArray
 fun projectionDisplayState(expectedOwnerEpoch: String, expectedProjectionToken: String): ByteArray
 fun asBinder(): android.os.IBinder
 abstract class Stub : IHostBootstrap, android.os.IBinder {
  override val isBinderAlive = true
  override fun linkToDeath(recipient: () -> Unit, flags: Int) = Unit
  override fun asBinder() = this
  companion object { fun asInterface(binder: android.os.IBinder): IHostBootstrap = binder as IHostBootstrap }
 }
}
