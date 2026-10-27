package android.hardware.camera2

import android.os.Handler
import android.view.Surface
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class CameraAccessException(val reason: Int, message: String) : Exception(message)

/** Queued deliveries use the original Handler; explicit inline controls require its same owner thread. */
class OpenCall(val cameraId: String, val callback: CameraDevice.StateCallback, val handler: Handler) {
    val deliveryThreads = CopyOnWriteArrayList<Thread>()
    private fun deliver(action: () -> Unit) = handler.post {
        deliveryThreads.add(Thread.currentThread())
        action()
    }
    fun opened(device: CameraDevice) = deliver { callback.onOpened(device) }
    fun disconnected(device: CameraDevice) = deliver { callback.onDisconnected(device) }
    fun error(device: CameraDevice, code: Int) = deliver { callback.onError(device, code) }
    fun closed(device: CameraDevice) = deliver { callback.onClosed(device) }

    /** Explicit reentrant platform control: original callback, already on its original Handler thread. */
    fun errorInlineBeforeReturn(device: CameraDevice, code: Int) {
        check(Thread.currentThread() === handler.looper.owner)
        deliveryThreads.add(Thread.currentThread())
        callback.onError(device, code)
    }
}

class ConfigurationCall(
    val outputs: List<Surface>,
    val callback: CameraCaptureSession.StateCallback,
    val handler: Handler,
) {
    val deliveryThreads = CopyOnWriteArrayList<Thread>()
    private fun deliver(action: () -> Unit) = handler.post {
        deliveryThreads.add(Thread.currentThread())
        action()
    }
    fun configured(session: CameraCaptureSession) = deliver { callback.onConfigured(session) }
    fun failed(session: CameraCaptureSession) = deliver { callback.onConfigureFailed(session) }
    fun closed(session: CameraCaptureSession) = deliver { callback.onClosed(session) }

    /** Explicit callback-before-catch control; it does not route an old callback to a new Handler. */
    fun failedInlineBeforeReturn(session: CameraCaptureSession) {
        check(Thread.currentThread() === handler.looper.owner)
        deliveryThreads.add(Thread.currentThread())
        callback.onConfigureFailed(session)
    }
}

class RepeatingCall(
    val session: CameraCaptureSession,
    val request: CaptureRequest,
    val callback: CameraCaptureSession.CaptureCallback,
    val handler: Handler,
    val sequenceId: Int,
) {
    fun completed(result: TotalCaptureResult, from: CameraCaptureSession = session,
        originalRequest: CaptureRequest = request) = handler.post {
        callback.onCaptureCompleted(from, originalRequest, result)
    }
    fun failed(failure: CaptureFailure, from: CameraCaptureSession = session,
        originalRequest: CaptureRequest = request) = handler.post {
        callback.onCaptureFailed(from, originalRequest, failure)
    }
}

class CameraManager {
    val opens = CopyOnWriteArrayList<OpenCall>()
    private val arrivals = LinkedBlockingQueue<OpenCall>()
    var afterInvocationException: Exception? = null
    var duringInvocation: ((OpenCall) -> Unit)? = null
    fun openCamera(cameraId: String, callback: CameraDevice.StateCallback, handler: Handler) {
        val original = OpenCall(cameraId, callback, handler)
        opens.add(original)
        try {
            duringInvocation?.invoke(original)
            afterInvocationException?.let { throw it }
        } finally { arrivals.put(original) }
    }
    fun awaitOpen(): OpenCall = checkNotNull(arrivals.poll(2, TimeUnit.SECONDS)) { "No actual openCamera invocation" }
}

open class CameraDevice(val id: String) {
    val configurations = CopyOnWriteArrayList<ConfigurationCall>()
    val templates = CopyOnWriteArrayList<Int>()
    var afterConfigurationInvocationException: Exception? = null
    var duringConfigurationInvocation: ((ConfigurationCall) -> Unit)? = null
    var requestException: Exception? = null
    var closeException: Exception? = null
    @Volatile var closeCount = 0
        private set

    fun createCaptureSession(outputs: List<Surface>, callback: CameraCaptureSession.StateCallback, handler: Handler) {
        val original = ConfigurationCall(outputs.toList(), callback, handler)
        configurations.add(original)
        duringConfigurationInvocation?.invoke(original)
        afterConfigurationInvocationException?.let { throw it }
    }
    fun createCaptureRequest(template: Int): CaptureRequest.Builder {
        templates.add(template)
        requestException?.let { throw it }
        return CaptureRequest.Builder()
    }
    @Synchronized fun close() {
        closeCount++
        closeException?.let { throw it }
        // Deliberately no onClosed: only the scenario may deliver that original callback.
    }

    abstract class StateCallback {
        abstract fun onOpened(camera: CameraDevice)
        abstract fun onDisconnected(camera: CameraDevice)
        abstract fun onError(camera: CameraDevice, error: Int)
        open fun onClosed(camera: CameraDevice) = Unit
    }
    companion object { const val TEMPLATE_RECORD = 3 }
}

class CaptureRequest internal constructor(val targets: List<Surface>, val tag: Any?) {
    class Builder {
        private val targets = mutableListOf<Surface>()
        private var tag: Any? = null
        fun addTarget(surface: Surface) { targets.add(surface) }
        fun setTag(value: Any?) { tag = value }
        fun build() = CaptureRequest(targets.toList(), tag)
    }
}

open class CameraCaptureSession(val label: String) {
    val repeating = CopyOnWriteArrayList<RepeatingCall>()
    var repeatingException: Exception? = null
    var closeException: Exception? = null
    @Volatile var closeCount = 0
        private set

    fun setRepeatingRequest(request: CaptureRequest, callback: CaptureCallback, handler: Handler): Int {
        val sequence = 73
        repeating.add(RepeatingCall(this, request, callback, handler, sequence))
        repeatingException?.let { throw it }
        return sequence
    }
    @Synchronized fun close() {
        closeCount++
        closeException?.let { throw it }
        // Deliberately no onClosed and no endpoint release.
    }
    abstract class StateCallback {
        abstract fun onConfigured(session: CameraCaptureSession)
        abstract fun onConfigureFailed(session: CameraCaptureSession)
        open fun onClosed(session: CameraCaptureSession) = Unit
    }
    abstract class CaptureCallback {
        open fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) = Unit
        open fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) = Unit
    }
}

open class CaptureResult {
    class Key<T>
    companion object { val SENSOR_TIMESTAMP = Key<Long>() }
}

class TotalCaptureResult(val frameNumber: Long, private val sensorTimestamp: Long?,
    private val readException: Exception? = null) : CaptureResult() {
    @Suppress("UNCHECKED_CAST")
    fun <T> get(key: CaptureResult.Key<T>): T? {
        readException?.let { throw it }
        check(key === CaptureResult.SENSOR_TIMESTAMP)
        return sensorTimestamp as T?
    }
}

class CaptureFailure(val frameNumber: Long, val reason: Int)
