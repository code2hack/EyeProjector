package android.content
open class Context {
    open val applicationContext:Context get()=this
    val receivers=mutableListOf<BroadcastReceiver>()
    fun registerReceiver(receiver:BroadcastReceiver,filter:IntentFilter,flags:Int=0) { receivers.add(receiver) }
    fun unregisterReceiver(receiver:BroadcastReceiver) { receivers.remove(receiver) }
    fun report(action:String) { receivers.toList().forEach { it.onReceive(this,Intent(action)) } }
    companion object { const val RECEIVER_EXPORTED=2 }
}
class Intent(val action:String?=null) { fun getStringExtra(name:String):String?=null; var flags=0;companion object { const val FLAG_RECEIVER_NO_ABORT=134217728 } }
class IntentFilter { var priority=0;fun addAction(action:String) {} }
abstract class BroadcastReceiver {
    var resultCode=0;val isOrderedBroadcast=false
    fun abortBroadcast() {}
    abstract fun onReceive(context:Context,intent:Intent)
}

class ComponentName
interface ServiceConnection { fun onServiceConnected(name:ComponentName,binder:android.os.IBinder) }
