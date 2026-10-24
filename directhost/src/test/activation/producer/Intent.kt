package android.content
class Intent(val action:String?=null) {
 fun putExtra(key:String,value:Any)=this
 fun addFlags(flags:Int)=this
 companion object {const val EXTRA_COMPONENT_NAME="fixture.component";const val FLAG_ACTIVITY_NEW_TASK=1}
}
