package android.content.pm
class PackageInfo(val lastUpdateTime: Long = 123L)
class PackageManager {
    fun getPackageInfo(name: String, flags: Int): PackageInfo {
        check(shizukuInstalled || name != "moe.shizuku.privileged.api")
        return PackageInfo()
    }
    companion object { const val PERMISSION_GRANTED = 0; var shizukuInstalled = true }
}
