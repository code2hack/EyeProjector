package com.code2hack.eyeprojector.directhost.camera

import android.companion.virtualcamera.IVirtualCameraService
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.Process
import android.os.SystemClock
import java.lang.reflect.InvocationTargetException

/** Caller facts describe this backend process, never the application using a camera. */
data class PhoneCameraCaller(
    val uid: Int,
    val pid: Int,
    val api: Int,
    val createVirtualDevicePermission: Int?,
    val permissionObservationFailure: Throwable? = null,
)

data class PhoneCameraServiceLookup(
    val service: IVirtualCameraService?,
    val descriptor: String?,
    val cachedBinderPresent: Boolean,
    val lazyLookupRequested: Boolean,
)

/** These calls run on the registration's private native lane, except local token/clock creation. */
interface Qpr2CameraPlatform {
    fun caller(): PhoneCameraCaller
    fun lookup(): PhoneCameraServiceLookup
    fun newToken(): IBinder = Binder()
    fun elapsedRealtimeNanos(): Long = SystemClock.elapsedRealtimeNanos()
}

/** Explicit QPR2 service adapter. It performs no work until its methods are called. */
class AndroidQpr2CameraPlatform(
    private val serviceCall: (String, String) -> IBinder? = { method, name ->
        Class.forName("android.os.ServiceManager").getMethod(method, String::class.java)
            .invoke(null, name) as IBinder?
    },
) : Qpr2CameraPlatform {
    override fun caller(): PhoneCameraCaller {
        val uid = Process.myUid()
        var failure: Throwable? = null
        val permission = try {
            val manager = Class.forName("android.app.AppGlobals")
                .getMethod("getPackageManager").invoke(null)
            Class.forName("android.content.pm.IPackageManager")
                .getMethod("checkUidPermission", String::class.java, Int::class.javaPrimitiveType)
                .invoke(manager, "android.permission.CREATE_VIRTUAL_DEVICE", uid) as Int
        } catch (error: Throwable) {
            failure = unwrap(error)
            null
        }
        return PhoneCameraCaller(uid, Process.myPid(), Build.VERSION.SDK_INT, permission, failure)
    }

    override fun lookup(): PhoneCameraServiceLookup {
        val cached = serviceCall("checkService", "virtual_camera")
        // getService may request lazy startup and still return null. Resolve that same
        // service on the original native lane before the owner decides whether to register.
        val binder = serviceCall("getService", "virtual_camera")
            ?: serviceCall("waitForService", "virtual_camera")
        return PhoneCameraServiceLookup(
            binder?.let { IVirtualCameraService.Stub.asInterface(it) },
            binder?.interfaceDescriptor,
            cached != null,
            true,
        )
    }

    private fun unwrap(error: Throwable): Throwable =
        if (error is InvocationTargetException) error.targetException ?: error else error
}
