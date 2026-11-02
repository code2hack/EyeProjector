package android.util
object Log {
    fun d(tag:String,message:String)=Unit
    fun i(tag:String,message:String)=Unit
    fun w(tag:String,message:String,error:Throwable?=null)=Unit
}
