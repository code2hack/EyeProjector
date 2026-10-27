package android.app
import android.content.*
open class Activity:Context() {
    val intent=Intent()
    val serviceIntents=mutableListOf<Intent>()
    var finished=false
    fun startForegroundService(intent:Intent){serviceIntents.add(intent)}
    fun startActivity(intent:Intent)=Unit
    fun finish(){finished=true}
}
