package com.code2hack.eyeprojector.directhost.anchor.execution

import android.os.IBinder
import android.os.Binder

/** Generated Android Binder boundary only; both actual consumers and value codecs are compiled. */
interface IAnchorAppBridge {
    fun asBinder(): IBinder
    fun onIntent(transactionId: Long, functionIntent: ByteArray, original: IAnchorSession)
    fun onRegistration(message: ByteArray, original: IAnchorSession)
    abstract class Stub : Binder(), IAnchorAppBridge { override fun asBinder(): IBinder = this }
}
interface IAnchorSession {
    fun asBinder(): IBinder
    fun source(): ByteArray?
    fun reconcile(settingContext: ByteArray): Boolean
    fun click(settingContext: ByteArray, resolvedTarget: ByteArray): Int
    fun retire()
    fun send(typedMessage: ByteArray)
    fun heldSetting(): ByteArray?
    fun releaseHeld(expectedSetting: ByteArray, settingCurrent: Boolean): Int
    fun hold(settingContext: ByteArray, resolvedTarget: ByteArray): Int
    abstract class Stub : Binder(), IAnchorSession { override fun asBinder(): IBinder = this }
}
