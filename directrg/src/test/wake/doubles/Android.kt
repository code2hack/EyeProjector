package android.content
import android.os.PowerManager
open class Context {
    val power=PowerManager()
    val packageName="fixture.receiver"
    val launches=mutableListOf<Intent>()
    var failLaunch=false
    fun <T> getSystemService(type:Class<T>):T=type.cast(power)
    fun startActivity(intent:Intent){check(!failLaunch);launches.add(intent)}
}
class Intent(context:Context,type:Class<*>) {
    val extras=mutableMapOf<String,Any?>()
    fun putExtra(key:String,value:String?)=apply{extras[key]=value}
    fun putExtra(key:String,value:Long)=apply{extras[key]=value}
    fun getStringExtra(key:String)=extras[key] as? String
    fun getLongExtra(key:String,default:Long)=extras[key] as? Long ?: default
    fun addFlags(value:Int)=this
    companion object {const val FLAG_ACTIVITY_NEW_TASK=1;const val FLAG_ACTIVITY_SINGLE_TOP=2;const val FLAG_ACTIVITY_CLEAR_TOP=4}
}
