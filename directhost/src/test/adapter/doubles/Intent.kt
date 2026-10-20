package android.content
class Intent {
    private val extras=mutableMapOf<String,String>()
    fun putExtra(key:String,value:String)=apply{extras[key]=value}
    fun getStringExtra(key:String):String?=extras[key]
}
