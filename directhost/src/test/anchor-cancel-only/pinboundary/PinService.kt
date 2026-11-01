package com.code2hack.eyeprojector.directhost.hid
interface IHidCaptureListener {
    fun onState(json:String);fun onInput(json:String)
    abstract class Stub:android.os.Binder(),IHidCaptureListener
}
interface IHidCapture {
    fun attach(listener:IHidCaptureListener);fun state():String;fun inventory():String;fun pin(key:String):String;fun unpin():String
    abstract class Stub:android.os.Binder(),IHidCapture {
        companion object {fun asInterface(b:android.os.IBinder)=b as IHidCapture}
    }
}
class PhoneHidPinService {
    fun show(s:String){};fun stopForeground(i:Int){};fun stopSelf(){}
}
