package android.content.pm
class PackageManager {
    class Info(val lastUpdateTime: Long = 1)
    fun getPackageInfo(name: String, flags: Int) = Info()
    companion object { const val PERMISSION_GRANTED = 0 }
}
