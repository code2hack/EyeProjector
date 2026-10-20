package android.content

open class Context { val packageName = "com.code2hack.eyeprojector.directrg" }
class Intent {
    fun setClassName(packageName: String, className: String) = this
    fun addFlags(flags: Int) = this
    companion object { const val FLAG_ACTIVITY_NEW_TASK = 0x10000000 }
}
