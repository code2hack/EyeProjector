package com.code2hack.eyeprojector.directhost.anchor.execution

import android.os.IBinder
import android.os.Binder

/** Only Android's generated Binder boundary is doubled; both production consumers are compiled. */
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
    abstract class Stub : Binder(), IAnchorSession { override fun asBinder(): IBinder = this }
}
