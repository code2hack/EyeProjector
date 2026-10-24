package android.content
import android.os.IBinder
open class Context
class ComponentName
interface ServiceConnection { fun onServiceConnected(name:ComponentName,binder:IBinder) }
class Intent() {
    val extras=mutableMapOf<String,Any?>()
    var action:String?=null
    constructor(action:String):this(){this.action=action}
    constructor(context:Context,type:Class<*>):this()
    fun getStringExtra(key:String)=extras[key] as? String
    fun setAction(value:String)=apply{action=value}
    fun putExtra(key:String,value:String?)=apply{extras[key]=value}
    fun putExtra(key:String,value:Int)=apply{extras[key]=value}
    fun addCategory(value:String)=this
    fun addFlags(value:Int)=this
    companion object {const val ACTION_MAIN="main";const val CATEGORY_HOME="home";const val FLAG_ACTIVITY_NEW_TASK=1}
}
