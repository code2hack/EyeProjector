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
}
interface ServiceConnection { fun onServiceConnected(name: ComponentName, binder: android.os.IBinder); fun onServiceDisconnected(name: ComponentName) }

class ApplicationInfo { val uid = 10001; val sourceDir = "unused-modeled.apk" }
class Assets { fun open(name: String): java.io.InputStream = error("Capture/bootstrap creation is not run by host composition") }

class SharedPreferences {
 private val values=mutableMapOf<String,String>()
 fun getString(key:String,fallback:String?):String?=values[key]?:fallback
 fun edit()=Editor()
 inner class Editor {
  private val pending=mutableMapOf<String,String>()
  fun putString(key:String,value:String)=apply{pending[key]=value}
  fun commit():Boolean{values.putAll(pending);return true}
 }
}
