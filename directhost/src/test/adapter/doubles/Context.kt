package android.content
import java.io.File
open class Context(val filesDir: File = File("unused-accessibility-boundary")) {
 val packageManager = android.content.pm.PackageManager()
 val packageName = "review.fake"
 val applicationInfo = ApplicationInfo()
 val assets = Assets()
 val applicationContext: Context get() = this
 fun <T> getSystemService(type:Class<T>):T=type.getDeclaredConstructor().newInstance()
 fun startActivity(intent:Any)=Unit
 private val preferences=mutableMapOf<String,SharedPreferences>()
 fun getSharedPreferences(name:String,mode:Int)=preferences.getOrPut(name){SharedPreferences()}
 companion object {const val MODE_PRIVATE=0}
}
data class ComponentName(val packageName:String,val className:String) {
 constructor(context:Context,klass:Class<*>):this(context.packageName,klass.name)
 fun flattenToString() = "$packageName/$className"
}
interface ServiceConnection { fun onServiceConnected(name: ComponentName, binder: android.os.IBinder); fun onServiceDisconnected(name: ComponentName) }

class ApplicationInfo { val uid = 10001; val sourceDir = "unused-modeled.apk" }
class Assets { fun open(name: String): java.io.InputStream = error("Capture/bootstrap creation is not run by host composition") }

class SharedPreferences {
 private val values=mutableMapOf<String,Any>()
 private val listeners=linkedSetOf<OnSharedPreferenceChangeListener>()
 fun interface OnSharedPreferenceChangeListener { fun onSharedPreferenceChanged(preferences:SharedPreferences,key:String?) }
 fun registerOnSharedPreferenceChangeListener(listener:OnSharedPreferenceChangeListener){listeners.add(listener)}
 fun unregisterOnSharedPreferenceChangeListener(listener:OnSharedPreferenceChangeListener){listeners.remove(listener)}
 fun contains(key:String)=values.containsKey(key)
 fun getString(key:String,fallback:String?):String?{beforeRead?.invoke(key);return values[key] as String? ?: fallback}
 fun getBoolean(key:String,fallback:Boolean):Boolean{beforeRead?.invoke(key);return values[key] as Boolean? ?: fallback}
 fun edit()=Editor()
 inner class Editor {
  private val pending=mutableMapOf<String,Any>()
  fun putString(key:String,value:String)=apply{pending[key]=value}
  fun putBoolean(key:String,value:Boolean)=apply{pending[key]=value}
  fun commit():Boolean{beforeApply?.invoke();values.putAll(pending);for(key in pending.keys)for(listener in listeners.toList()){
   val work={listener.onSharedPreferenceChanged(this@SharedPreferences,key)};if(deferListeners)queued.add(work) else work()
  };return true}
  fun apply(){commit()}
 }
 companion object {
  var beforeRead:((String)->Unit)?=null
  var beforeApply:(()->Unit)?=null
  var deferListeners=false
  private val queued=java.util.concurrent.ConcurrentLinkedQueue<()->Unit>()
  fun flush(){while(true)(queued.poll()?:return).invoke()}
 }
}
