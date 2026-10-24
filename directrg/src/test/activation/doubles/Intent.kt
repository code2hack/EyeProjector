package android.content
/** Named Android Intent extra/delivery boundary; actual production readIntent body is extracted. */
class Intent {
 private val extras=mutableMapOf<String,Any?>()
 fun putExtra(key:String,value:String?)=apply{extras[key]=value}
 fun putExtra(key:String,value:Long)=apply{extras[key]=value}
 fun getStringExtra(key:String)=extras[key] as? String
 fun getLongExtra(key:String,default:Long)=extras[key] as? Long ?: default
}
