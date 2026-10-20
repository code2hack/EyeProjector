package android.content.pm
class PackageInfo(val lastUpdateTime: Long = 123L)
class PackageManager {
 fun getPackageInfo(name: String, flags: Int) = PackageInfo()
 companion object { const val PERMISSION_GRANTED = 0 }
}
