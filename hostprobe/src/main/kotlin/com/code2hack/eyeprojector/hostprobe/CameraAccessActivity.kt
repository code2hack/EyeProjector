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
import java.util.concurrent.atomic.AtomicLong
import java.util.UUID

/** Explicit Camera2 selection on the display supplied by Android. No producer/service privileges. */
class CameraAccessActivity : Activity(), TextureView.SurfaceTextureListener {
    private lateinit var view: TextureView
    private lateinit var cameraId: EditText
    private lateinit var status: TextView
    private lateinit var workerThread: HandlerThread
    private lateinit var worker: Handler
    private val instance = UUID.randomUUID().toString()
    private val generation = AtomicLong()
    private var pendingOpen = false
    private var resumed = false
    // Camera resources and counters below belong to worker.
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var preview: Surface? = null
    private var images: ImageReader? = null
    private var captureResults = 0L
    private var jpegCount = 0L
    private var displayFrames = 0L
    private var previewEpoch: Long? = null
    private var previewCameraId: String? = null
    private var selectedId: String? = null
    private lateinit var manager: CameraManager

    @Synchronized private fun record(event: String, vararg fields: Pair<String, Any?>) {
        val json = JSONObject().put("event", event)
            .put("elapsedNanos", SystemClock.elapsedRealtimeNanos()).put("instance", instance)
        fields.forEach { (key, value) -> json.put(key, value ?: JSONObject.NULL) }
        File(filesDir, "camera-access-events.jsonl").appendText("$json\n")
        android.util.Log.i("EyeCameraConsumer", json.toString())
        runOnUiThread { status.text = json.toString() }
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
        status = TextView(this).apply { setTextColor(Color.GREEN); text = "Camera2 owned consumer" }
        cameraId = EditText(this).apply {
            hint = "Exact camera ID from producer"
            setTextColor(Color.WHITE); setHintTextColor(Color.LTGRAY)
            setSingleLine()
            setText(intent.getStringExtra("cameraId") ?: "")
        }
        root.addView(status)
        root.addView(cameraId)
        root.addView(LinearLayout(this).apply {
            fun control(label: String, action: () -> Unit) {
                addView(Button(context).apply { text = label; setOnClickListener { action() } },
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }
            control("Enumerate") { enumerate() }
            control("Open") { open() }
            control("JPEG") { capture() }
            control("Close") { close() }
        })
        view = TextureView(this).apply { surfaceTextureListener = this@CameraAccessActivity }
        root.addView(view, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        record("consumer_created", "uid" to Process.myUid(), "pid" to Process.myPid(),
            "sdk" to Build.VERSION.SDK_INT, "fingerprint" to Build.FINGERPRINT,
            "displayId" to display?.displayId, "androidDeviceId" to if (Build.VERSION.SDK_INT >= 34) deviceId else null,
            "cameraPermission" to checkSelfPermission(Manifest.permission.CAMERA),
            "runId" to intent.getStringExtra("runId"))
        enumerate()
        pendingOpen = intent.getStringExtra("operation") == "open"
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra("cameraId")?.let { cameraId.setText(it) }
        when (intent.getStringExtra("operation")) {
            "open" -> open()
            "capture" -> capture()
            "close" -> close()
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
                val cameras = JSONArray()
                manager.cameraIdList.forEach { id ->
                    val info = manager.getCameraCharacteristics(id)
                    val configs = info.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    cameras.put(JSONObject().put("id", id)
                        .put("lensFacing", info.get(CameraCharacteristics.LENS_FACING))
                        .put("orientation", info.get(CameraCharacteristics.SENSOR_ORIENTATION))
                        .put("jpegSizes", JSONArray(configs?.getOutputSizes(ImageFormat.JPEG)?.map { it.toString() } ?: emptyList<String>()))
                        .put("previewSizes", JSONArray(configs?.getOutputSizes(SurfaceTexture::class.java)?.map { it.toString() } ?: emptyList<String>())))
                }
                record("enumeration", "cameras" to cameras)
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
        val epoch = generation.incrementAndGet()
        worker.post {
            if (generation.get() != epoch) return@post
            release()
            try {
                val info = manager.getCameraCharacteristics(id)
                val configs = requireNotNull(info.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP))
                val previewSize = chooseSize(requireNotNull(configs.getOutputSizes(SurfaceTexture::class.java)))
                val jpegSize = chooseSize(requireNotNull(configs.getOutputSizes(ImageFormat.JPEG)))
                texture.setDefaultBufferSize(previewSize.width, previewSize.height)
                preview = Surface(texture)
                images = ImageReader.newInstance(jpegSize.width, jpegSize.height, ImageFormat.JPEG, 2).apply {
                    setOnImageAvailableListener({ reader -> saveJpeg(reader, epoch) }, worker)
                }
                selectedId = id
                record("open_requested", "cameraId" to id, "generation" to epoch,
                    "previewSize" to previewSize.toString(), "jpegSize" to jpegSize.toString())
                manager.openCamera(id, object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        if (generation.get() != epoch) { camera.close(); return }
                        device = camera
                        record("camera_opened", "cameraId" to camera.id, "generation" to epoch)
                        configure(camera, epoch)
                    }
                    override fun onDisconnected(camera: CameraDevice) {
                        camera.close()
                        if (generation.compareAndSet(epoch, epoch + 1)) release()
                        record("camera_disconnected", "cameraId" to camera.id, "generation" to epoch)
                    }
                    override fun onError(camera: CameraDevice, error: Int) {
                        camera.close()
                        if (generation.compareAndSet(epoch, epoch + 1)) release()
                        record("camera_error", "cameraId" to camera.id, "code" to error, "generation" to epoch)
                    }
                    override fun onClosed(camera: CameraDevice) {
                        record("camera_closed", "cameraId" to camera.id, "generation" to epoch)
                    }
                }, worker)
            } catch (error: Exception) {
                release()
                record("open_failed", "cameraId" to id, "error" to error.toString())
            }
        }
    }

    private fun configure(camera: CameraDevice, epoch: Long) {
        try {
            val output = requireNotNull(preview)
            val jpeg = requireNotNull(images).surface
            @Suppress("DEPRECATION")
            camera.createCaptureSession(listOf(output, jpeg), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(configured: CameraCaptureSession) {
                    if (generation.get() != epoch) { configured.close(); return }
                    session = configured
                    try {
                        val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply { addTarget(output) }
                        configured.setRepeatingRequest(request.build(), captureCallback(epoch), worker)
                        runOnUiThread {
                            if (generation.get() == epoch) {
                                previewEpoch = epoch
                                previewCameraId = camera.id
                                displayFrames = 0
                            }
                        }
                        record("preview_started", "cameraId" to camera.id, "generation" to epoch)
                    } catch (error: Exception) {
                        if (generation.compareAndSet(epoch, epoch + 1)) release()
                        record("preview_failed", "error" to error.toString())
                    }
                }
                override fun onConfigureFailed(configured: CameraCaptureSession) {
                    configured.close()
                    if (generation.compareAndSet(epoch, epoch + 1)) release()
                    record("session_configure_failed", "generation" to epoch)
                }
                override fun onClosed(configured: CameraCaptureSession) {
                    record("session_closed", "generation" to epoch)
                }
            }, worker)
        } catch (error: Exception) {
            camera.close()
            if (generation.compareAndSet(epoch, epoch + 1)) release()
            record("session_failed", "error" to error.toString())
        }
    }

    private fun captureCallback(epoch: Long) = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            if (generation.get() != epoch) return
            captureResults++
            if (request.tag == "jpeg" || captureResults == 1L || captureResults % 30 == 0L) {
                record("capture_result", "cameraId" to selectedId, "generation" to epoch,
                    "kind" to (request.tag ?: "preview"), "frameNumber" to result.frameNumber,
                    "sensorTimestamp" to result.get(CaptureResult.SENSOR_TIMESTAMP), "results" to captureResults)
            }
        }
        override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
            record("capture_failed", "generation" to epoch, "reason" to failure.reason, "frameNumber" to failure.frameNumber)
        }
    }

    private fun capture() {
        worker.post {
            try {
                val camera = requireNotNull(device) { "camera is closed" }
                val configured = requireNotNull(session) { "session not configured" }
                val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    addTarget(requireNotNull(images).surface)
                    set(CaptureRequest.JPEG_ORIENTATION, 0)
                    set(CaptureRequest.JPEG_QUALITY, 90.toByte())
                    setTag("jpeg")
                }
                configured.capture(request.build(), captureCallback(generation.get()), worker)
                record("jpeg_requested", "cameraId" to camera.id)
            } catch (error: Exception) { record("jpeg_request_failed", "error" to error.toString()) }
        }
    }

    private fun saveJpeg(reader: ImageReader, epoch: Long) {
        try {
            val image = reader.acquireNextImage() ?: return
            image.use {
                if (generation.get() != epoch) return
                val bytes = ByteArray(image.planes[0].buffer.remaining())
                image.planes[0].buffer.get(bytes)
                val bitmap = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size)) { "invalid JPEG" }
                val sample = sampleHash(bitmap)
                val width = bitmap.width; val height = bitmap.height
                bitmap.recycle()
                jpegCount++
                val file = File(filesDir, "camera-access-$instance-$epoch-$jpegCount.jpg")
                file.writeBytes(bytes)
                record("jpeg_saved", "cameraId" to selectedId, "generation" to epoch, "file" to file.name,
                    "bytes" to bytes.size, "sha256" to hash(bytes), "decodedSampleSha256" to sample,
                    "width" to width, "height" to height, "timestamp" to image.timestamp)
            }
        } catch (error: Exception) { record("jpeg_save_failed", "generation" to epoch, "error" to error.toString()) }
    }

    private fun chooseSize(sizes: Array<Size>): Size = sizes.firstOrNull { it.width == 640 && it.height == 480 }
        ?: sizes.minBy { it.width.toLong() * it.height }

    private fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun sampleHash(bitmap: Bitmap): String {
        val pixels = ByteArray(16 * 12 * 3)
        var position = 0
        for (y in 0 until 12) for (x in 0 until 16) {
            val color = bitmap.getPixel((x * bitmap.width / 16), (y * bitmap.height / 12))
            pixels[position++] = Color.red(color).toByte()
            pixels[position++] = Color.green(color).toByte()
            pixels[position++] = Color.blue(color).toByte()
        }
        return hash(pixels)
    }

    private fun close() {
        pendingOpen = false
        previewEpoch = null
        previewCameraId = null
        generation.incrementAndGet()
        worker.post { release(); record("consumer_close_requested") }
    }

    private fun release() {
        session?.close(); session = null
        device?.close(); device = null
        images?.close(); images = null
        preview?.release(); preview = null
        selectedId = null
        captureResults = 0
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        if (pendingOpen && resumed) open()
    }
    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit
    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        close()
        return true
    }
    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
        val epoch = previewEpoch ?: return
        if (generation.get() != epoch) return
        displayFrames++
        if (displayFrames == 1L || displayFrames % 30 == 0L) {
            // Sample actual TextureView pixels; result callbacks alone cannot prove visible changing frames.
            val bitmap = view.getBitmap(16, 12)
            val sample = bitmap?.let { sampleHash(it) }
            bitmap?.recycle()
            record("preview_presented", "cameraId" to previewCameraId, "generation" to epoch,
                "updates" to displayFrames, "decodedSampleSha256" to sample, "displayId" to display?.displayId)
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
        close()
        super.onPause()
    }
    override fun onDestroy() {
        close()
        worker.post { record("consumer_destroyed"); workerThread.quitSafely() }
        super.onDestroy()
    }
}
