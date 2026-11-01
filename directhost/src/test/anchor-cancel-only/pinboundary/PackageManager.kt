package android.content.pm
class PackageManager {
    class PackageInfo(val lastUpdateTime:Long=1)
    fun getPackageInfo(name:String,flags:Int)=PackageInfo()
    companion object {const val PERMISSION_GRANTED=0}
}
