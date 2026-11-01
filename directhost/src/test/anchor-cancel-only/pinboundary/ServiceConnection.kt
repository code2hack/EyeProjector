package android.content
class ComponentName(val packageName:String,val className:String) {
    constructor(context:Context,name:String):this(context.packageName,name)
}
interface ServiceConnection {
    fun onServiceConnected(name:ComponentName,binder:android.os.IBinder)
    fun onServiceDisconnected(name:ComponentName)
}
