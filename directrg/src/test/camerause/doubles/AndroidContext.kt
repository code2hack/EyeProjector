package android.content

import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import java.util.concurrent.CopyOnWriteArrayList

/** Ordinary platform service/permission observations, with no source installed by default. */
open class Context(var cameraManager: CameraManager? = null) {
    var cameraPermission: Int = PackageManager.PERMISSION_DENIED
    var serviceFailure: Exception? = null
    var permissionFailure: Exception? = null
    val serviceReads = CopyOnWriteArrayList<String>()
    val permissionReads = CopyOnWriteArrayList<String>()
    open val applicationContext: Context get() = this
    open fun getSystemService(name: String): Any? {
        serviceReads.add(name)
        serviceFailure?.let { throw it }
        return if (name == CAMERA_SERVICE) cameraManager else null
    }
    open fun checkSelfPermission(permission: String): Int {
        permissionReads.add(permission)
        permissionFailure?.let { throw it }
        return cameraPermission
    }
    companion object { const val CAMERA_SERVICE = "camera" }
}
