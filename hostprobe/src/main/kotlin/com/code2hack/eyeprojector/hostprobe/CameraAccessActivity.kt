package com.code2hack.eyeprojector.hostprobe

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.ImageReader
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.text.TextUtils
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/** Ordinary Camera2 fixture. Each opening retains its own original resources and callbacks. */
class CameraAccessActivity : Activity(), TextureView.SurfaceTextureListener {
    private lateinit var view: TextureView
    private lateinit var cameraId: EditText
    private lateinit var status: TextView
    private lateinit var photo: Button
    private lateinit var workerThread: HandlerThread
    private lateinit var worker: Handler
    private lateinit var manager: CameraManager
    private val instance = UUID.randomUUID().toString()
    private val generation = AtomicLong()
    // Main-thread admission/UI state; changing it immediately invalidates old preview callbacks.
    private var pendingOpen = false
    private var resumed = false
    private var requestedMode = CameraOutputMode.PREVIEW_JPEG
    private var shownPreview: OpenRequest? = null
    private var displayFrames = 0L
    // Worker-thread ownership. Never replace current until its original closure completes.
    private var current: Opening? = null
    private var replacement: OpenRequest? = null
    private val retiredTextures = mutableSetOf<SurfaceTexture>()
    private var finishRequested = false
    private var finishPosted = false
    private var destroyed = false
    private var workerQuit = false

    private data class OpenRequest(
        val epoch: Long, val id: String, val mode: CameraOutputMode, val texture: SurfaceTexture,
    )

    @Synchronized private fun record(event: String, vararg fields: Pair<String, Any?>) {
        val json = JSONObject().put("event", event).put("instance", instance)
            .put("elapsedNanos", SystemClock.elapsedRealtimeNanos())
        fields.forEach { (key, value) -> json.put(key, value ?: JSONObject.NULL) }
        File(filesDir, "camera-access-events.jsonl").appendText("$json\n")
        android.util.Log.i("EyeCameraConsumer", json.toString())
        // Full metadata remains in JSONL/logcat. Enumeration must not consume preview height.
        val summary = listOfNotNull(event, json.optString("cameraId").takeIf { it.isNotEmpty() },
            json.optString("outputMode").takeIf { it.isNotEmpty() }).joinToString(" ")
        runOnUiThread { status.text = summary }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        manager = getSystemService(CameraManager::class.java)
        workerThread = HandlerThread("owned-camera-consumer").apply { start() }
        worker = Handler(workerThread.looper)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            setPadding(12, 12, 12, 12)
        }
        status = TextView(this).apply {
            setTextColor(Color.GREEN)
            text = "Camera2 owned consumer"
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        cameraId = EditText(this).apply {
            hint = "Exact camera ID from producer"
            setTextColor(Color.WHITE); setHintTextColor(Color.LTGRAY)
            setSingleLine()
            setText(intent.getStringExtra("cameraId") ?: "")
        }
        root.addView(status)
        root.addView(cameraId)
        root.addView(LinearLayout(this).apply {
            fun control(label: String, action: () -> Unit): Button = Button(context).also { button ->
                button.text = label
                button.setOnClickListener { action() }
                addView(button, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }
            control("Enumerate") { enumerate() }
            control("Open") { open() }
            photo = control("JPEG") { capture() }
            control("Close") { close("button") }
        })
        view = TextureView(this).apply { surfaceTextureListener = this@CameraAccessActivity }
        root.addView(view, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        readMode(intent)
        record("consumer_created", "uid" to Process.myUid(), "pid" to Process.myPid(),
            "sdk" to Build.VERSION.SDK_INT, "fingerprint" to Build.FINGERPRINT,
            "displayId" to display?.displayId,
            "androidDeviceId" to if (Build.VERSION.SDK_INT >= 34) deviceId else null,
            "cameraPermission" to checkSelfPermission(Manifest.permission.CAMERA),
            "runId" to intent.getStringExtra("runId"), "outputMode" to requestedMode.wire)
        enumerate()
        pendingOpen = intent.getStringExtra("operation") == "open"
    }

    private fun readMode(command: Intent) {
        command.getStringExtra("outputMode")?.let { value ->
            CameraOutputMode.entries.firstOrNull { it.wire == value }?.let { requestedMode = it }
                ?: record("unknown_output_mode", "value" to value, "retainedMode" to requestedMode.wire)
        }
        photo.isEnabled = requestedMode.jpeg
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra("cameraId")?.let { cameraId.setText(it) }
        readMode(intent)
        when (intent.getStringExtra("operation")) {
            "open" -> open()
            "capture" -> capture()
            "close" -> close("intent")
            "enumerate" -> enumerate()
            "finish" -> finish()
        }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        if (pendingOpen && view.isAvailable) open()
    }

    private fun enumerate() {
        worker.post {
            try {
                val ids = manager.cameraIdList
                val cameras = JSONArray()
                ids.forEach { id ->
                    val row = JSONObject().put("id", id)
                    try {
                        val info = manager.getCameraCharacteristics(id)
                        val configs = info.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                        row.put("lensFacing", info.get(CameraCharacteristics.LENS_FACING))
                            .put("orientation", info.get(CameraCharacteristics.SENSOR_ORIENTATION))
                            .put("jpegSizes", JSONArray(configs?.getOutputSizes(ImageFormat.JPEG)?.map { it.toString() } ?: emptyList<String>()))
                            .put("previewSizes", JSONArray(configs?.getOutputSizes(SurfaceTexture::class.java)?.map { it.toString() } ?: emptyList<String>()))
                    } catch (error: Exception) { row.put("metadataError", error.toString()) }
                    cameras.put(row)
                }
                record("enumeration", "cameraIds" to JSONArray(ids.toList()), "cameras" to cameras)
            } catch (error: Exception) { record("enumeration_failed", "error" to error.toString()) }
        }
    }

    private fun open() {
        if (!resumed || !view.isAvailable) { pendingOpen = true; return }
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            pendingOpen = true
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 67)
            return
        }
        pendingOpen = false
        val id = cameraId.text.toString()
        if (id.isEmpty()) { record("open_needs_camera_id"); return }
        val texture = view.surfaceTexture ?: return
        val request = OpenRequest(generation.incrementAndGet(), id, requestedMode, texture)
        shownPreview = null
        worker.post {
            if (request.epoch != generation.get() || destroyed || finishRequested) return@post
            replacement = request
            current?.close("replacement")
            advance()
        }
    }

    private fun close(reason: String) {
        pendingOpen = false
        shownPreview = null
        generation.incrementAndGet()
        worker.post {
            replacement = null
            val owner = current
            if (owner == null) record("consumer_idle", "operation" to reason) else owner.close(reason)
            advance()
        }
    }

    /** Explicit finish (including Back) waits for the original callbacks without blocking either thread. */
    override fun finish() {
        if (!::worker.isInitialized) { super.finish(); return }
        pendingOpen = false
        shownPreview = null
        generation.incrementAndGet()
        worker.post {
            finishRequested = true
            replacement = null
            current?.close("finish")
            advance()
        }
    }

    private fun finishAfterClose() { if (!isDestroyed) super.finish() }

    private fun advance() {
        if (current != null) return
        val textures = retiredTextures.iterator()
        while (textures.hasNext()) {
            try { textures.next().release(); textures.remove() }
            catch (error: Exception) {
                record("texture_release_unconfirmed", "error" to error.toString())
                return
            }
        }
        if (destroyed) {
            if (!workerQuit) {
                workerQuit = true
                record("consumer_worker_shutdown", "ownershipResolved" to true)
                workerThread.quitSafely()
            }
        } else if (finishRequested) {
            if (!finishPosted) { finishPosted = true; runOnUiThread { finishAfterClose() } }
        } else {
            val next = replacement
            replacement = null
            if (next != null && next.epoch == generation.get()) {
                val opening = Opening(next)
                current = opening
                opening.start()
            }
        }
    }

    private inner class Opening(val request: OpenRequest) {
        private var preview: Surface? = null
        private var images: ImageReader? = null
        private var captureResults = 0L
        private var jpegCount = 0L
        private var closeReason: String? = null
        private var allocatedOutputCount = 0
        val ownership: CameraCloseOwner<CameraDevice, CameraCaptureSession> = CameraCloseOwner(
            closeDevice = { it.close() }, closeSession = { it.close() },
            releaseOutputs = {
                images?.close(); images = null
                preview?.release(); preview = null
            },
            event = { name, error ->
                if (name == "consumer_close_requested") generation.compareAndSet(request.epoch, request.epoch + 1)
                event(name, "error" to error?.toString(), "reason" to closeReason,
                    "openPending" to ownershipOpenPending(), "configurationPending" to ownershipConfigurationPending(),
                    "deviceObserved" to ownershipDeviceObserved(), "sessionObserved" to ownershipSessionObserved(),
                    "sessionClosedObserved" to ownershipSessionClosedObserved(),
                    "sessionRetirementBasis" to ownershipSessionRetirementBasis(),
                    "parentDeviceClosedObserved" to ownershipParentDeviceClosedObserved(),
                    "allocatedOutputCount" to allocatedOutputCount)
            },
            completed = {
                if (current === this) { current = null; advance() }
            },
        )

        // Indirection keeps initialization lambdas from reading an uninitialized ownership property.
        private fun ownershipOpenPending(): Boolean = ownership.openPending
        private fun ownershipConfigurationPending(): Boolean = ownership.configurationPending
        private fun ownershipDeviceObserved(): Boolean = ownership.device != null
        private fun ownershipSessionObserved(): Boolean = ownership.session != null
        private fun ownershipSessionClosedObserved(): Boolean = ownership.sessionClosedObserved
        private fun ownershipSessionRetirementBasis(): String? = ownership.sessionRetirementBasis
        private fun ownershipParentDeviceClosedObserved(): Boolean = ownership.parentDeviceClosedObserved
        fun event(name: String, vararg fields: Pair<String, Any?>) {
            record(name, "cameraId" to request.id, "generation" to request.epoch,
                "outputMode" to request.mode.wire, *fields)
        }
        private fun admitted() = !ownership.closing && !ownership.complete && current === this &&
            generation.get() == request.epoch && !destroyed && !finishRequested

        fun start() {
            try {
                val info = manager.getCameraCharacteristics(request.id)
                val configs = requireNotNull(info.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP))
                val previewSize = chooseSize(requireNotNull(configs.getOutputSizes(SurfaceTexture::class.java)))
                request.texture.setDefaultBufferSize(previewSize.width, previewSize.height)
                preview = Surface(request.texture)
                allocatedOutputCount++
                // Preview-only never consults JPEG sizes or allocates a JPEG reader/output.
                val jpegSize = if (request.mode.jpeg) {
                    chooseSize(requireNotNull(configs.getOutputSizes(ImageFormat.JPEG))).also { size ->
                        val reader = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 2)
                        images = reader
                        allocatedOutputCount++
                        reader.setOnImageAvailableListener({ available -> saveJpeg(available) }, worker)
                    }
                } else null
                event("open_requested", "previewSize" to previewSize.toString(), "jpegSize" to jpegSize?.toString())
            } catch (error: Exception) {
                event("open_setup_failed", "error" to error.toString())
                close("setup_failed")
                return
            }
            if (!admitted()) { close("cancelled_before_open"); return }
            ownership.expectOpen()
            try {
                manager.openCamera(request.id, object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        if (!ownership.deviceArrived(camera)) return
                        event("camera_opened")
                        if (admitted()) configure(camera) else close("cancelled_open")
                    }
                    override fun onDisconnected(camera: CameraDevice) {
                        ownership.deviceArrived(camera)
                        event("camera_disconnected")
                        close("disconnected")
                    }
                    override fun onError(camera: CameraDevice, error: Int) {
                        ownership.deviceArrived(camera)
                        event("camera_error", "code" to error)
                        close("camera_error")
                    }
                    override fun onClosed(camera: CameraDevice) { ownership.deviceClosed(camera) }
                }, worker)
            } catch (error: SecurityException) {
                event("open_call_unresolved", "error" to error.toString())
                close("open_call_exception")
            } catch (error: Exception) {
                // An invoked framework operation may still have queued callbacks. Do not invent them.
                event("open_call_unresolved", "error" to error.toString())
                close("open_call_exception")
            }
        }

        private fun configure(camera: CameraDevice) {
            val output = preview
            if (output == null) {
                event("session_setup_failed", "error" to "preview output absent")
                close("session_setup_failed")
                return
            }
            val outputs = try {
                mutableListOf(output).apply { images?.surface?.let { add(it) } }
            } catch (error: Exception) {
                event("session_setup_failed", "error" to error.toString())
                close("session_setup_failed")
                return
            }
            ownership.expectConfiguration()
            event("session_configure_requested", "outputCount" to outputs.size)
            try {
                @Suppress("DEPRECATION")
                camera.createCaptureSession(outputs, object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(configured: CameraCaptureSession) {
                        if (!ownership.sessionArrived(configured)) return
                        if (!admitted()) { close("cancelled_configuration"); return }
                        try {
                            val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply { addTarget(output) }
                            configured.setRepeatingRequest(builder.build(), captureCallback(), worker)
                            runOnUiThread {
                                if (generation.get() == request.epoch) {
                                    shownPreview = request
                                    displayFrames = 0
                                    photo.isEnabled = request.mode.jpeg
                                }
                            }
                            event("preview_started", "outputCount" to outputs.size)
                        } catch (error: Exception) {
                            event("preview_failed", "error" to error.toString())
                            close("preview_failed")
                        }
                    }
                    override fun onConfigureFailed(configured: CameraCaptureSession) {
                        closeReason = "configuration_failed"
                        ownership.configurationFailed(configured)
                    }
                    override fun onClosed(configured: CameraCaptureSession) { ownership.sessionClosed(configured) }
                }, worker)
            } catch (error: Exception) {
                event("configuration_call_unresolved", "error" to error.toString())
                close("configuration_call_exception")
            }
        }

        fun close(reason: String) {
            if (closeReason == null) closeReason = reason
            ownership.requestClose()
            if (!ownership.complete) event("consumer_close_pending",
                "openPending" to ownership.openPending, "configurationPending" to ownership.configurationPending)
        }

        private fun captureCallback() = object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                if (!admitted() || ownership.session !== session) return
                captureResults++
                if (request.tag == "jpeg" || captureResults == 1L || captureResults % 30 == 0L) {
                    event("capture_result", "kind" to (request.tag ?: "preview"), "frameNumber" to result.frameNumber,
                        "sensorTimestamp" to result.get(CaptureResult.SENSOR_TIMESTAMP), "results" to captureResults)
                }
            }
            override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
                event("capture_failed", "reason" to failure.reason, "frameNumber" to failure.frameNumber)
            }
        }

        fun capture() {
            if (!request.mode.jpeg) { event("jpeg_not_configured"); return }
            try {
                check(admitted()) { "opening is not active" }
                val camera = requireNotNull(ownership.device)
                val configured = requireNotNull(ownership.session)
                val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    addTarget(requireNotNull(images).surface)
                    set(CaptureRequest.JPEG_ORIENTATION, 0)
                    set(CaptureRequest.JPEG_QUALITY, 90.toByte())
                    setTag("jpeg")
                }
                configured.capture(builder.build(), captureCallback(), worker)
                event("jpeg_requested")
            } catch (error: Exception) { event("jpeg_request_failed", "error" to error.toString()) }
        }

        private fun saveJpeg(reader: ImageReader) {
            try {
                val image = reader.acquireNextImage() ?: return
                image.use {
                    if (!admitted() || images !== reader) return
                    val bytes = ByteArray(image.planes[0].buffer.remaining())
                    image.planes[0].buffer.get(bytes)
                    val bitmap = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size)) { "invalid JPEG" }
                    val sample = sampleHash(bitmap)
                    val width = bitmap.width; val height = bitmap.height
                    bitmap.recycle()
                    jpegCount++
                    val file = File(filesDir, "camera-access-$instance-${request.epoch}-$jpegCount.jpg")
                    file.writeBytes(bytes)
                    event("jpeg_saved", "file" to file.name, "bytes" to bytes.size, "sha256" to hash(bytes),
                        "decodedSampleSha256" to sample, "width" to width, "height" to height, "timestamp" to image.timestamp)
                }
            } catch (error: Exception) { event("jpeg_save_failed", "error" to error.toString()) }
        }
    }

    private fun capture() {
        worker.post { current?.capture() ?: record("jpeg_request_failed", "error" to "camera is closed") }
    }

    private fun chooseSize(sizes: Array<Size>): Size = sizes.firstOrNull { it.width == 640 && it.height == 480 }
        ?: sizes.minBy { it.width.toLong() * it.height }
    private fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun sampleHash(bitmap: Bitmap): String {
        val pixels = ByteArray(16 * 12 * 3)
        var position = 0
        for (y in 0 until 12) for (x in 0 until 16) {
            val color = bitmap.getPixel(x * bitmap.width / 16, y * bitmap.height / 12)
            pixels[position++] = Color.red(color).toByte()
            pixels[position++] = Color.green(color).toByte()
            pixels[position++] = Color.blue(color).toByte()
        }
        return hash(pixels)
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        record("surface_available", "width" to width, "height" to height, "displayId" to display?.displayId)
        if (pendingOpen && resumed) open()
    }
    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        record("surface_size_changed", "width" to width, "height" to height)
    }
    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        pendingOpen = false
        shownPreview = null
        generation.incrementAndGet()
        val accepted = worker.post {
            replacement = null
            retiredTextures.add(surface)
            current?.close("surface_destroyed")
            advance()
        }
        if (!accepted) surface.release() // Worker exits only after its ownership has resolved.
        // Retain this exact texture until its original camera/session has finished using it.
        return false
    }
    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
        val request = shownPreview ?: return
        if (generation.get() != request.epoch || request.texture !== surface) return
        displayFrames++
        if (displayFrames == 1L || displayFrames % 30 == 0L) {
            val bitmap = view.getBitmap(16, 12)
            val sample = bitmap?.let { sampleHash(it) }
            bitmap?.recycle()
            record("preview_presented", "cameraId" to request.id, "generation" to request.epoch,
                "outputMode" to request.mode.wire, "updates" to displayFrames, "decodedSampleSha256" to sample,
                "displayId" to display?.displayId, "viewWidth" to view.width, "viewHeight" to view.height)
        }
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 67) {
            record("camera_permission_result", "granted" to (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED))
            if (pendingOpen && checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) open()
            else pendingOpen = false
        }
    }
    override fun onPause() {
        resumed = false
        close("pause")
        super.onPause()
    }
    override fun onDestroy() {
        pendingOpen = false
        shownPreview = null
        generation.incrementAndGet()
        worker.post {
            destroyed = true
            replacement = null
            record("consumer_destroyed")
            current?.close("destroy")
            advance()
        }
        super.onDestroy()
    }
}

// BEGIN CAMERA_CLOSE_OWNER -- compiled verbatim by the focused cached host fixture.
internal enum class CameraOutputMode(val wire: String, val jpeg: Boolean) {
    PREVIEW_JPEG("preview-jpeg", true), PREVIEW_ONLY("preview-only", false),
}

/** Serial, original-resource close ownership. No polling, timer or callback synthesized from close(). */
internal class CameraCloseOwner<D : Any, S : Any>(
    private val closeDevice: (D) -> Unit,
    private val closeSession: (S) -> Unit,
    private val releaseOutputs: () -> Unit,
    private val event: (String, Exception?) -> Unit,
    private val completed: () -> Unit,
) {
    var device: D? = null; private set
    var session: S? = null; private set
    var openPending = false; private set
    var configurationPending = false; private set
    var closing = false; private set
    var complete = false; private set
    var parentDeviceClosedObserved = false; private set
    var sessionClosedObserved = false; private set
    var sessionRetirementBasis: String? = null; private set
    private var sessionTerminal = false
    private var deviceCloseSent = false
    private var sessionCloseSent = false
    private var driving = false

    fun expectOpen() { openPending = true }
    fun expectConfiguration() { configurationPending = true }
    fun deviceArrived(value: D): Boolean {
        if (complete || (device != null && device !== value)) { event("ignored_camera_callback", null); return false }
        device = value
        openPending = false
        drive()
        return !complete && !parentDeviceClosedObserved
    }
    fun sessionArrived(value: S): Boolean {
        if (complete || (session != null && session !== value)) { event("ignored_session_callback", null); return false }
        session = value
        configurationPending = false
        retireSessionByParentDeviceClosed()
        drive()
        return !complete && !sessionTerminal
    }
    fun configurationFailed(value: S) {
        if (complete || (session != null && session !== value)) { event("ignored_session_callback", null); return }
        session = value
        configurationPending = false
        // Android defines onConfigureFailed as a closed failed session, without requiring onClosed.
        sessionTerminal = true
        if (sessionRetirementBasis == null) sessionRetirementBasis = "CONFIGURATION_FAILED"
        event("session_configure_failed", null)
        requestClose()
    }
    fun deviceClosed(value: D) {
        if (complete || (device != null && device !== value)) { event("ignored_camera_callback", null); return }
        device = value
        openPending = false
        parentDeviceClosedObserved = true
        event("camera_closed", null)
        retireSessionByParentDeviceClosed()
        requestClose()
    }
    fun sessionClosed(value: S) {
        if ((session != null && session !== value) || (complete && session !== value)) {
            event("ignored_session_callback", null); return
        }
        if (sessionClosedObserved) return
        session = value
        configurationPending = false
        sessionTerminal = true
        sessionClosedObserved = true
        if (sessionRetirementBasis == null) sessionRetirementBasis = "SESSION_CLOSED"
        event("session_closed", null)
        if (!complete) requestClose()
    }
    private fun retireSessionByParentDeviceClosed() {
        if (parentDeviceClosedObserved && session != null && !sessionTerminal) {
            // The actual original parent callback is a resource fence, not a session callback.
            // Pending configuration remains a separate unresolved leg until its own callback.
            sessionTerminal = true
            sessionRetirementBasis = "PARENT_DEVICE_CLOSED"
            event("session_retired_by_parent_device_closed", null)
        }
    }
    fun requestClose() {
        if (complete) return
        if (!closing) { closing = true; event("consumer_close_requested", null) }
        drive()
    }
    private fun drive() {
        if (!closing || complete || driving) return
        driving = true
        try {
            val originalSession = session
            if (originalSession != null && !sessionTerminal && !sessionCloseSent) {
                sessionCloseSent = true
                try { closeSession(originalSession) }
                catch (error: Exception) { event("session_close_unconfirmed", error) }
            }
            val originalDevice = device
            if (originalDevice != null && !parentDeviceClosedObserved && !deviceCloseSent) {
                deviceCloseSent = true
                try { closeDevice(originalDevice) }
                catch (error: Exception) { event("camera_close_unconfirmed", error) }
            }
            if (openPending || configurationPending || (device != null && !parentDeviceClosedObserved) ||
                (session != null && !sessionTerminal)) return
            try { releaseOutputs() }
            catch (error: Exception) { event("outputs_release_unconfirmed", error); return }
            complete = true
            event("consumer_close_completed", null)
            completed()
        } finally { driving = false }
    }
}
// END CAMERA_CLOSE_OWNER
