package android.app
import android.content.*
import android.os.Bundle
import java.io.File
open class Activity: Context(File(".")) {
    private var incoming=Intent()
    val intent get()=incoming
    fun setIntent(value:Intent){incoming=value}
    open fun onCreate(state:Bundle?){}
    open fun onNewIntent(intent:Intent){}
    open fun onResume(){}
    fun setContentView(view:Any){}
    fun getString(id:Int,vararg args:Any)="resource:"+id+":"+args.joinToString()
}
