package android.content.pm

class PackageInfo { val lastUpdateTime = 12345L }
class PackageManager {
    fun getPackageInfo(name: String, flags: Int) = PackageInfo()
    companion object { const val PERMISSION_GRANTED = 0; const val PERMISSION_DENIED = -1 }
}
