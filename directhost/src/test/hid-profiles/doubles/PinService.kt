package com.code2hack.eyeprojector.directhost.hid

import android.os.IBinder

internal interface IHidCaptureListener {
    fun onState(stateJson: String)
    fun onInput(inputJson: String)
    abstract class Stub : IHidCaptureListener
}
internal interface IHidCapture : IBinder {
    fun attach(listener: IHidCaptureListener)
    fun state(): String
    fun inventory(): String
    fun pin(key: String): String
    fun unpin(): String
    object Stub { fun asInterface(binder: IBinder) = binder as IHidCapture }
}
