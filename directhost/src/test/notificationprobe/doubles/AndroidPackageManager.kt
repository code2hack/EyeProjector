package android.content.pm

import android.content.Context

class PackageInfo(val requestedPermissions: Array<String>?)
class PackageManager(private val context: Context) {
    fun getPackageInfo(packageName: String, flags: Int): PackageInfo {
        check(packageName == context.packageName)
        context.packageInfoFailure?.let { throw it }
        return PackageInfo(context.declaredPermissions.toTypedArray())
    }
    companion object { const val GET_PERMISSIONS = 4096; const val PERMISSION_GRANTED = 0; const val PERMISSION_DENIED = -1 }
}
