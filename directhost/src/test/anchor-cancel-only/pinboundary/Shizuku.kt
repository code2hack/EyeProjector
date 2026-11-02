package rikka.shizuku
import android.content.*
object Shizuku {
    fun interface OnBinderReceivedListener {fun onBinderReceived()}
    fun interface OnRequestPermissionResultListener {fun onRequestPermissionResult(request:Int,result:Int)}
    fun interface OnBinderDeadListener {fun onBinderDead()}
    class UserServiceArgs(name:ComponentName) {
        fun tag(s:String)=this;fun processNameSuffix(s:String)=this;fun daemon(b:Boolean)=this;fun version(i:Int)=this
    }
    var capture:android.os.IBinder?=null
    fun addBinderReceivedListenerSticky(listener:OnBinderReceivedListener){}
    fun addBinderDeadListener(listener:OnBinderDeadListener){}
    fun addRequestPermissionResultListener(listener:OnRequestPermissionResultListener){}
    fun pingBinder()=true
    fun checkSelfPermission()=0
    fun bindUserService(args:UserServiceArgs,connection:ServiceConnection) {connection.onServiceConnected(ComponentName("boundary","capture"),checkNotNull(capture))}
    fun unbindUserService(args:UserServiceArgs,connection:ServiceConnection,remove:Boolean){}
}
