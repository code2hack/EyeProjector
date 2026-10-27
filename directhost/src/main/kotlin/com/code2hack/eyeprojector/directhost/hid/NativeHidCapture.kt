package com.code2hack.eyeprojector.directhost.hid

import android.content.Context
import android.os.Build
import android.os.Process
import android.view.ViewConfiguration
import androidx.annotation.Keep
import java.io.File
import java.util.zip.ZipFile

/** One native reader owns all fds, grabs, hotplug observation and press identities. */
@Keep
internal class NativeHidCapture(
    context: Context,
    private val stateChanged: (String) -> Unit,
    private val input: (String) -> Unit,
) : AutoCloseable {
    private var handle: Long

    init {
        loadLibrary(context)
        val metrics = context.resources.displayMetrics
        val slop = ViewConfiguration.get(context).scaledTouchSlop.toDouble()
        handle = create(slop / metrics.widthPixels.coerceAtLeast(1), slop / metrics.heightPixels.coerceAtLeast(1))
        check(handle != 0L) { "HID reader could not start" }
    }

    fun inventory(): String = inventory(handle)
    fun state(): String = state(handle)
    fun pin(key: String) = pin(handle, key)
    fun unpin() = unpin(handle)

    override fun close() {
        val owned = handle
        handle = 0
        if (owned != 0L) close(owned)
    }

    @Keep private fun onNativeState(json: String) = stateChanged(json)
    @Keep private fun onNativeInput(json: String) = input(json)

    private fun loadLibrary(context: Context) {
        try {
            System.loadLibrary("eyeprojector_hid")
            return
        } catch (_: UnsatisfiedLinkError) {
            // A Shizuku shell process need not inherit the app's native search path.
        }
        val app = context.applicationInfo
        val extracted = File(app.nativeLibraryDir, "libeyeprojector_hid.so")
        if (extracted.isFile) {
            try {
                System.load(extracted.absolutePath)
                return
            } catch (_: UnsatisfiedLinkError) {
                // The shell process can have a different ABI from the app's
                // primary native directory. Select its ABI explicitly below.
            }
        }
        // Modern APKs can retain aligned, uncompressed .so entries instead of
        // extracting nativeLibraryDir files. Bionic supports loading these in place.
        val abis = if (Process.is64Bit()) Build.SUPPORTED_64_BIT_ABIS else Build.SUPPORTED_32_BIT_ABIS
        for (apk in listOf(app.sourceDir) + app.splitSourceDirs.orEmpty()) {
            ZipFile(apk).use { zip ->
                for (abi in abis) {
                    val entry = "lib/$abi/libeyeprojector_hid.so"
                    if (zip.getEntry(entry) != null) {
                        System.load("$apk!/$entry")
                        return
                    }
                }
            }
        }
        throw UnsatisfiedLinkError("The installed APK has no HID library for this process ABI")
    }

    private external fun create(touchSlopXFraction: Double, touchSlopYFraction: Double): Long
    private external fun inventory(handle: Long): String
    private external fun state(handle: Long): String
    private external fun pin(handle: Long, key: String)
    private external fun unpin(handle: Long)
    private external fun close(handle: Long)
}
