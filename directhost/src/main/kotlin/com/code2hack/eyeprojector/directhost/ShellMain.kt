package com.code2hack.eyeprojector.directhost

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.Build
import android.os.Process
import android.os.SystemClock
import com.code2hack.eyeprojector.directhost.input.PhoneInputMerger
import com.code2hack.eyeprojector.directhost.input.FunctionWork
import com.code2hack.eyeprojector.directhost.camera.CapturedTrustedPeer
import com.code2hack.eyeprojector.directhost.camera.PhoneCameraMediaRole
import com.code2hack.eyeprojector.directhost.camera.PhoneCameraMediaOwner
import com.code2hack.eyeprojector.directhost.camera.PhoneCameraMediaSnapshot
import com.code2hack.eyeprojector.directhost.camera.PhoneCameraProfile
import com.code2hack.eyeprojector.directhost.camera.RequestedPhoneStream
import com.code2hack.eyeprojector.directhost.camera.Qpr2VirtualCameraOwner
import android.util.AtomicFile
import android.util.Log
import com.code2hack.eyeprojector.protocol.*
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.UUID
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import org.json.JSONObject

internal enum class SessionStopReason {
    OWNER_CLOSE, SESSION_ERROR, HOST_STOP, STARTUP_DEADLINE, WRITE_DEADLINE,
    CONTROL_END, CONTROL_EOF, CONTROL_TIMEOUT, CONTROL_IO, CONTROL_ERROR,
    VIDEO_END, VIDEO_EOF, VIDEO_TIMEOUT, VIDEO_IO, VIDEO_ERROR,
}

private enum class StartupStage { CREATED, CREATE_MANAGED_DISPLAY, COPY_JAR, START_SERVER, ACCEPT_VIDEO, ACCEPT_CONTROL, READ_CODEC, VIDEO_STREAM }

/** Fixed categories only; exception messages and private payloads never enter telemetry. */
internal fun sessionFailureReason(error: Exception, control: Boolean): SessionStopReason = when (error) {
    is EOFException -> if (control) SessionStopReason.CONTROL_EOF else SessionStopReason.VIDEO_EOF
    is SocketTimeoutException -> if (control) SessionStopReason.CONTROL_TIMEOUT else SessionStopReason.VIDEO_TIMEOUT
    is IOException -> if (control) SessionStopReason.CONTROL_IO else SessionStopReason.VIDEO_IO
    else -> if (control) SessionStopReason.CONTROL_ERROR else SessionStopReason.VIDEO_ERROR
}

/** The admitted stream keeps its finite setup bound until its welcome has flushed successfully. */
internal fun writeStreamWelcome(socket: java.net.Socket, output: DataOutputStream) {
    Wire.writeWelcome(output)
    socket.soTimeout = 0
}

internal fun panelPowerJson(state: PanelPowerState): JSONObject = JSONObject()
    .put("requestId", state.requestId).put("offSubmission", state.off.name)
    .put("restorationSubmission", state.restoration.name).put("physicalObservation", state.observation.name)
    .put("sessionActive", state.sessionActive)
    .put("ownerEpoch", state.context.ownerEpoch).put("projectionToken", state.context.projectionToken)

/** Explicit shell bootstrap only. An ordinary installed APK never acquires shell authority. */
object ShellMain {
    const val SERVER_SHA256 = "764eb6f79811d5211fe9df341120882ba9994c7a61b897d7bf3fb662e53bc536"
    internal fun copySessionJar(master: File, target: File, expectedSHA256: String = SERVER_SHA256) {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newOutputStream(target.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { output ->
            master.inputStream().use { input ->
                val buffer = ByteArray(65536)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count); output.write(buffer, 0, count)
                }
            }
        }
        require(digest.digest().joinToString("") { "%02x".format(it) } == expectedSHA256) { "Unqualified session server" }
    }

    internal fun videoControlCommand(scid: Int): List<String> = listOf("/system/bin/app_process", "/", "com.genymobile.scrcpy.Server", Wire.SCRCPY_VERSION,
            "scid=%08x".format(scid), "log_level=debug", "tunnel_forward=false", "send_dummy_byte=false",
            "send_device_meta=false", "send_stream_meta=true", "send_frame_meta=true", "video=true", "audio=false",
            "control=true", "video_codec=h264", "video_bit_rate=2000000", "max_fps=30", "new_display=480x640/160", "flex_display=true",
            "display_ime_policy=local", "vd_destroy_content=true", "vd_system_decorations=false", "clipboard_autosync=false", "power_on=false", "keep_active=true", "cleanup=true")

    internal fun videoControlCommand(scid: Int, sourceDisplayId: Int?): List<String> {
        val normal = videoControlCommand(scid)
        if (sourceDisplayId == null) return normal
        return normal.filterNot { option -> listOf("new_display=", "flex_display=", "vd_destroy_content=", "vd_system_decorations=",
            "video=", "video_codec=", "video_bit_rate=", "max_fps=")
            .any(option::startsWith) } + listOf("video=false", "display_id=$sourceDisplayId")
    }

    @JvmStatic fun main(args: Array<String>) {
        require(Process.myUid() == 2000 && args.size == 2)
        val directory = File(args[0]); val jar = File(args[1])
        require(directory.isDirectory && jar.isFile)
        val digest = jar.inputStream().use { MessageDigest.getInstance("SHA-256").digest(it.readBytes()) }
        require(digest.joinToString("") { "%02x".format(it) } == SERVER_SHA256)
        Bridge(directory, jar).use { it.run() }
    }
}

internal class Bridge(
    private val directory: File,
    private val jar: File,
    private val timer: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor(),
    private val listener: SSLServerSocket = Tls.context(directory).serverSocketFactory.createServerSocket() as SSLServerSocket,
    private val reportStatus: ((String) -> Unit)? = null,
    private val ownerEpoch: String = UUID.randomUUID().toString(),
    private val managedDisplayFactory: (((String, Int?, Int?) -> Unit) -> ManagedProjectionDisplay)? = null,
) : Closeable {
    private val cameraTasksPrepared = CameraTasks.prepare()
    private val running = AtomicBoolean(true)
    private val release = OwnedRelease()
    private val admission = Any()
    private val active = AtomicReference<Session?>()
    private var launchOperationsAccepted = true
    @Volatile private var lastPanelState = PanelPowerState.unavailable()
    @Volatile private var lastManagedProjection = JSONObject()
    @Volatile private var lastManagedSession: Session? = null
    private val peers = ConcurrentHashMap.newKeySet<SSLSocket>()
    private val workers = ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS, ArrayBlockingQueue(2))
    private val settings = Tls.settings(directory)
    private val refused = AtomicLong()
    private val unauthenticatedRefused = AtomicLong()
    private var boundPort = 0

    @Synchronized private fun status(state: String) {
        reportStatus?.let { it(state); return }
        val data = JSONObject().put("state", state).put("model", Build.MODEL)
            .put("pid", Process.myPid()).put("shellUid", Process.myUid()).put("refused", refused.get())
            .put("unauthenticatedRefused", unauthenticatedRefused.get())
            .put("bind", settings.getProperty("host")).put("port", boundPort)
            .put("scrcpy", Wire.SCRCPY_VERSION).put("audio", "system-bluetooth")
            .put("cameraMediaStatusFile", active.get()?.cameraStatusFileName ?: JSONObject.NULL)
            .put("managedProjection", managedProjectionStatus())
        val file = AtomicFile(File(directory, "bridge-status.json"))
        val out = file.startWrite()
        try { out.write(data.toString().toByteArray()); file.finishWrite(out) }
        catch (error: Exception) { file.failWrite(out); throw error }
        println(data.toString())
    }

    fun run() {
        val address = InetAddress.getByName(settings.getProperty("host"))
        require(!address.isAnyLocalAddress && !address.isLoopbackAddress && address.isSiteLocalAddress)
        val port = settings.getProperty("port").toInt().also { require(it in 1024..65535) }
        listener.reuseAddress = false
        listener.needClientAuth = true
        listener.enabledProtocols = listener.supportedProtocols.filter { it in listOf("TLSv1.3", "TLSv1.2") }.toTypedArray()
        listener.bind(InetSocketAddress(address, port), 2)
        boundPort = listener.localPort
        listener.soTimeout = 1000
        // Session deadlines remain active; elapsed host lifetime alone does not stop this bootstrap.
        status("HOST_READY")
        while (running.get()) {
            val socket = try { listener.accept() as SSLSocket }
            catch (_: java.net.SocketTimeoutException) { continue }
            catch (error: Exception) { if (running.get()) throw error else break }
            val admitted = synchronized(admission) { running.get() && peers.add(socket) }
            if (!admitted) { socket.close(); continue }
            try { workers.execute { serve(socket) } }
            catch (_: java.util.concurrent.RejectedExecutionException) { peers.remove(socket); socket.close() }
        }
    }

    private fun serve(socket: SSLSocket) {
        var session: Session? = null
        var cameraTransferred = false
        var authenticated = false
        var reason = SessionStopReason.OWNER_CLOSE
        try {
            socket.soTimeout = 3500; socket.tcpNoDelay = true
            // Leave media socket buffers to Android TCP autotuning.
            socket.startHandshake() // No scrcpy process, display, media or input exists before mTLS.
            Tls.requireTrustedPeer(socket, directory)
            authenticated = true
            val input = DataInputStream(socket.inputStream)
            when (Wire.readRequest(input)) {
                Wire.CAMERA -> {
                    val trustedPeer = CapturedTrustedPeer.authenticated(socket)
                    val transfer = synchronized(admission) {
                        if (!running.get()) null else active.get()?.cameraRoles?.takePendingSocket(socket, input, trustedPeer)
                    }
                    if (transfer?.claimed == true) {
                        // Claim precedes external start. This original Session now owns even failed starts.
                        cameraTransferred = true
                        peers.remove(socket)
                        transfer.pending!!.startOwnedLane()
                    }
                }
                Wire.STOP_HOST -> { status("HOST_STOPPING"); close(SessionStopReason.HOST_STOP) }
                Wire.STREAM -> {
                    session = synchronized(admission) {
                        if (!running.get() || active.get() != null) return
                        Session(socket, input, directory, jar, timer, ownerEpoch) { if (running.get()) status("STREAMING") }.also {
                            it.installManagedDisplayFactory(managedDisplayFactory)
                            active.set(it)
                        }
                    }
                    status("SESSION_STARTING")
                    session.run()
                }
            }
        } catch (_: Exception) {
            reason = SessionStopReason.SESSION_ERROR
            refused.incrementAndGet() // Fixed status only; never log private config/payload/exception text.
            if (!authenticated) unauthenticatedRefused.incrementAndGet()
            if (running.get() && session == null && active.get() == null) status("HOST_READY")
        } finally {
            var releaseFailed = false
            try { session?.close(reason) }
            catch (_: Exception) { releaseFailed = true }
            finally { if (!cameraTransferred) runCatching { socket.close() }; peers.remove(socket) }
            if (session != null && active.get() === session && running.get()) {
                try {
                    status(session.terminationStatus(releaseFailed))
                } finally { close(reason) }
            }
        }
    }

    override fun close() = close(SessionStopReason.OWNER_CLOSE)

    /** Typed local controller hooks capture only this Bridge's exact actual Session. */
    fun requestScale(requestId: Long, value: Double, expectedOwnerEpoch: String, expectedProjectionToken: String): ScaleState {
        val target = panelTarget(PanelContext(expectedOwnerEpoch, expectedProjectionToken))
            ?: return LiveScale.unavailable(requestId, value)
        return target.requestScale(requestId, value)
    }

    fun scaleState(expectedOwnerEpoch: String, expectedProjectionToken: String): ScaleState {
        val target = panelTarget(PanelContext(expectedOwnerEpoch, expectedProjectionToken)) ?: return LiveScale.unavailable()
        return target.scaleState()
    }

    fun captureProjection(expected: ProjectionContext): CapturedProjection? = synchronized(admission) {
        if (!running.get() || !launchOperationsAccepted || !expected.available || ownerEpoch != expected.ownerEpoch) return@synchronized null
        active.get()?.takeIf { it.projectionContext() == expected }
    }

    /** Future actual Phone membership obtains this exact Session owner, never a tuple-adopted use. */
    fun captureCameraUses(expected: ProjectionContext): PhoneCameraMediaRole? = synchronized(admission) {
        if (!running.get() || !launchOperationsAccepted || !expected.available || ownerEpoch != expected.ownerEpoch) null
        else active.get()?.takeIf { it.projectionContext() == expected }?.cameraRoles
    }

    private fun panelTarget(context: PanelContext): Session? = synchronized(admission) {
        if (!launchOperationsAccepted || !running.get() || !context.resolved || context.ownerEpoch != ownerEpoch) null
        else active.get()?.takeIf { it.panelContext == context }
    }

    fun projectionContext(): ProjectionContext = synchronized(admission) {
        if (!running.get() || !launchOperationsAccepted) ProjectionContext()
        else active.get()?.projectionContext() ?: ProjectionContext()
    }

    fun requestPanelOff(requestId: Long, expectedOwnerEpoch: String, expectedProjectionToken: String): PanelPowerState {
        val context = PanelContext(expectedOwnerEpoch, expectedProjectionToken)
        val target = panelTarget(context) ?: return PanelPowerState.unavailable(requestId, context)
        return target.requestPanelOff(requestId)
    }

    fun panelState(expectedOwnerEpoch: String, expectedProjectionToken: String): PanelPowerState {
        val context = PanelContext(expectedOwnerEpoch, expectedProjectionToken)
        val target = panelTarget(context)
        return target?.panelState() ?: lastPanelState.takeIf { context.resolved && it.context == context }
            ?: PanelPowerState.unavailable(context = context)
    }

    fun panelState(): PanelPowerState = active.get()?.panelState() ?: lastPanelState

    fun managedProjectionStatus(): JSONObject = (active.get() ?: lastManagedSession)?.managedProjectionStatus() ?: lastManagedProjection
    val managedProjectionEnabled: Boolean get() = managedDisplayFactory != null
    fun managedProjectionRetired(): Boolean = (active.get() ?: lastManagedSession)?.managedProjectionRetired() ?: true

    fun invalidateLaunchOperations() = synchronized(admission) { launchOperationsAccepted = false }

    // All typed operations share the same admission boundary; Stop cannot leave launch admitted.
    fun invalidatePanelOperations() = invalidateLaunchOperations()

    private fun close(reason: SessionStopReason) {
        invalidatePanelOperations()
        release.close {
            running.set(false)
            runCatching { listener.close() }
            val owned = synchronized(admission) { active.get() }
            peers.filterNot { owned?.owns(it) == true }.forEach { runCatching { it.close() } }
            peers.clear()
            try { owned?.close(reason) }
            finally {
                lastPanelState = owned?.panelState() ?: lastPanelState
                lastManagedProjection = owned?.managedProjectionStatus() ?: lastManagedProjection
                if (managedDisplayFactory != null && owned != null) lastManagedSession = owned
                workers.shutdownNow(); timer.shutdownNow()
            }
            active.compareAndSet(owned, null)
            status("HOST_STOPPED")
        }
    }
}

private class Session(
    private val peer: SSLSocket,
    private val input: DataInputStream,
    private val directory: File,
    private val jar: File,
    private val timer: java.util.concurrent.ScheduledExecutorService,
    private val ownerEpoch: String,
    private val firstFrame: () -> Unit,
) : Closeable, CapturedProjection {
    private val running = AtomicBoolean(true)
    private val release = OwnedRelease()
    private val resources = Any()
    val stopReason: SessionStopReason
        get() = firstCloseRequest.get()?.reason ?: SessionStopReason.OWNER_CLOSE

    fun terminationStatus(releaseFailed: Boolean): String =
        if (!releaseFailed && stopReason in setOf(SessionStopReason.OWNER_CLOSE, SessionStopReason.CONTROL_END))
            "DISCONNECTED" else "SESSION_FAILED"
    private val geometry = AtomicReference<Geometry?>()
    private val writes = ProjectionWrites()
    private data class CloseRequest(val reason: SessionStopReason, val requestedMonoNs: Long,
        val uptimeMs: Long, val write: ProjectionWriteAttempt?, val completionAtRequest: ProjectionWriteCompletion?, val primary: Throwable?,
        val lastChannel: Long, val lastPing: Long, val lastPong: Long,
        val controlState: Thread.State?, val streamState: Thread.State?)
    private val firstCloseRequest = AtomicReference<CloseRequest?>()
    private val primaryFailure = AtomicReference<Throwable?>()
    private val cleanupFailure = AtomicReference<Throwable?>()
    private val output = DataOutputStream(peer.outputStream)
    private val controlLock = Any()
    private val projectionToken = UUID.randomUUID().toString()
    val cameraStatusFileName = "camera-media-$projectionToken.json"
    private val context = ProjectionContext(ownerEpoch, projectionToken)
    @Volatile private var managedDisplayFactory: (((String, Int?, Int?) -> Unit) -> ManagedProjectionDisplay)? = null
    private val managedDisplayGate = Any()
    private var managedDisplay: ManagedProjectionDisplay? = null
    private var managedDisplayCreating = false
    private var managedDisplayCloseRequested = false
    private var managedMediaCloseObserved = false
    private var managedTransportRetired = false
    private val managedMediaCloseStarted = AtomicBoolean()
    private val managedDisplayReleaseStarted = AtomicBoolean()
    @Volatile private var managedDisplayState = if (managedDisplayFactory == null) "OFF" else "NOT_STARTED"
    @Volatile private var managedDisplayFailure: String? = null
    @Volatile private var managedPlatformStage = "NOT_STARTED"
    @Volatile private var managedDeviceId: Int? = null
    @Volatile private var managedSourceDisplayId: Int? = null
    @Volatile private var scrcpyNewDisplayId: Int? = null
    private val managedStatusFileName = "managed-projection-$projectionToken.json"
    val cameraRoles = PhoneCameraMediaRole(ownerEpoch, ::send,
        projectionFailure = { closeSafely(SessionStopReason.CONTROL_IO, it) })
    private val pongLane = SessionPongLane({ stamp -> send(Frame(Wire.PONG, stamp)) },
        { closeSafely(SessionStopReason.CONTROL_IO, it) })
    // Construction is inert. This Session, rather than a diagnostic actor, owns the new token.
    private val cameraProfile = PhoneCameraProfile(listOf(RequestedPhoneStream(640, 480,
        android.companion.virtualcamera.Format.RGBA_8888, 30)),
        android.companion.virtualcamera.LensFacing.EXTERNAL,
        android.companion.virtualcamera.SensorOrientation.ORIENTATION_0, 0)
    private val cameraMediaOwner = PhoneCameraMediaOwner(this, cameraRoles, Qpr2VirtualCameraOwner(),
        cameraProfile,
        CameraStreamRequest(640, 480, android.companion.virtualcamera.Format.RGBA_8888,
            "video/avc", bitRate = 4_000_000, frameRate = 30, iFrameIntervalSeconds = 1),
        ::recordCameraMedia)

    // Installed on the newly captured Session before Bridge publishes it. The original seven-arg
    // constructor and no-VDM construction path remain available to existing callers/fixtures.
    fun installManagedDisplayFactory(factory: (((String, Int?, Int?) -> Unit) -> ManagedProjectionDisplay)?) {
        managedDisplayFactory = factory
        managedDisplayState = if (factory == null) "OFF" else "NOT_STARTED"
    }

    fun managedProjectionRetired(): Boolean = managedDisplayState in setOf("OFF", "CLOSED", "CLOSED_AFTER_FAILURE", "CLOSED_NOT_CREATED")

    fun managedProjectionStatus(): JSONObject = JSONObject()
        .put("ownerEpoch", ownerEpoch).put("projectionToken", projectionToken)
        .put("enabled", managedDisplayFactory != null).put("state", managedDisplayState)
        .put("failure", managedDisplayFailure ?: JSONObject.NULL)
        .put("platformStage", managedPlatformStage)
        .put("callerUid", Process.myUid()).put("statusFile", managedStatusFileName)
        .put("shellContext", synchronized(managedDisplayGate) { managedDisplay }?.shellContextStatus() ?: JSONObject.NULL)
        .put("geometry", synchronized(managedDisplayGate) { managedDisplay }?.geometryStatus() ?: JSONObject.NULL)
        .put("androidDeviceId", managedDeviceId ?: JSONObject.NULL)
        .put("sourceDisplayId", managedSourceDisplayId ?: JSONObject.NULL)
        .put("scrcpyNewDisplayId", scrcpyNewDisplayId ?: JSONObject.NULL)
        .put("scrcpyMirrorDisplayId", JSONObject.NULL).put("mirrorIdObservation", "UNOBSERVED")
        .put("displayRole", if (managedSourceDisplayId == null) "UNOBSERVED" else "REAL_APP_SOURCE_WITH_ENCODER_INPUT_SURFACE")

    private val managedStatusLock = Any()
    private fun observeManagedProjection(stage: String, sourceId: Int?, deviceId: Int?) {
        synchronized(managedDisplayGate) {
            managedPlatformStage = stage
            if (sourceId != null) managedSourceDisplayId = sourceId
            if (deviceId != null) managedDeviceId = deviceId
            if (stage in setOf("DISPLAY_ENCODER_STREAM_FAILED", "DISPLAY_LOGICAL_LAYOUT_FAILED", "DISPLAY_OBSERVATION_FAILED")) {
                managedDisplayFailure = stage
                if (!managedDisplayCloseRequested) managedDisplayState = "FAILED"
            }
        }
        runCatching { recordManagedProjection() }
    }

    private fun recordManagedProjection() {
        if (managedDisplayFactory == null) return
        synchronized(managedStatusLock) {
            val file = AtomicFile(File(directory, managedStatusFileName))
            val out = file.startWrite()
            try { out.write(managedProjectionStatus().toString().toByteArray()); file.finishWrite(out) }
            catch (error: Exception) { file.failWrite(out); throw error }
        }
    }

    private fun createManagedDisplay(): ManagedProjectionDisplay? {
        val factory = managedDisplayFactory ?: return null
        synchronized(managedDisplayGate) {
            check(running.get() && !managedDisplayCloseRequested)
            managedDisplayCreating = true
            managedDisplayState = "CREATING"
        }
        try {
            recordManagedProjection()
            val original = factory(::observeManagedProjection) // No Session/controller lock around native creation.
            val admitted = synchronized(managedDisplayGate) {
                managedDisplay = original // Retain returned originals even if Stop won during creation.
                managedDeviceId = original.deviceId
                managedSourceDisplayId = original.sourceDisplayId
                managedDisplayCreating = false
                (running.get() && !managedDisplayCloseRequested).also {
                    managedDisplayState = if (managedDisplayFailure != null) "FAILED"
                        else if (it) "READY" else "WAITING_CAMERA_RETIREMENT"
                }
            }
            recordManagedProjection()
            tryReleaseManagedDisplay(cameraMediaOwner.snapshot())
            managedDisplayFailure?.let { throw ManagedProjectionDisplay.Failure(it) }
            check(admitted) { "Managed projection stopped during creation" }
            return original
        } catch (error: Throwable) {
            synchronized(managedDisplayGate) {
                managedDisplayCreating = false
                if (managedDisplay == null) {
                    (error as? ManagedProjectionDisplay.Failure)?.retainedOwner?.let { original ->
                        managedDisplay = original
                        managedDeviceId = original.deviceIdOrNull
                        managedSourceDisplayId = original.sourceDisplayIdOrNull
                    }
                    managedDisplayState = "FAILED"
                    managedDisplayFailure = (error as? ManagedProjectionDisplay.Failure)?.category ?: "VDM_CREATION_FAILED"
                }
            }
            runCatching { recordManagedProjection() }
            throw error
        }
    }

    private fun retireManagedDisplay() {
        if (managedDisplayFactory != null) synchronized(managedDisplayGate) {
            managedDisplayCloseRequested = true
            if (managedDisplayState !in setOf("FAILED", "CLOSED", "CLOSED_AFTER_FAILURE", "CLOSED_NOT_CREATED", "RELEASING", "RELEASE_FAILED"))
                managedDisplayState = "WAITING_CAMERA_RETIREMENT"
        }
        if (managedMediaCloseStarted.compareAndSet(false, true)) {
            cameraMediaOwner.closeResourcesAsync(java.util.concurrent.Executor { it.run() }) { observation ->
                if (observation.snapshot.originalSession !== this) return@closeResourcesAsync
                // Legacy sessions also retire their media-owned status lane.
                if (managedDisplayFactory == null) return@closeResourcesAsync
                if (observation.failure != null) {
                    synchronized(managedDisplayGate) {
                        managedMediaCloseObserved = false
                        if (managedDisplayState != "FAILED") managedDisplayState = "WAITING_CAMERA_RETIREMENT"
                        if (managedDisplayFailure == null) managedDisplayFailure = "CAMERA_RESOURCE_RETIREMENT_FAILED"
                    }
                    runCatching { recordManagedProjection() }
                    return@closeResourcesAsync
                }
                synchronized(managedDisplayGate) { managedMediaCloseObserved = true }
                tryReleaseManagedDisplay(observation.snapshot)
                recordManagedProjection()
            }
        }
    }

    private fun tryReleaseManagedDisplay(value: PhoneCameraMediaSnapshot) {
        if (managedDisplayFactory == null || value.originalSession !== this || !value.closeRequested) return
        val eligible = synchronized(managedDisplayGate) {
            managedDisplayCloseRequested && managedMediaCloseObserved && managedTransportRetired && !managedDisplayCreating
        }
        if (!eligible) return
        // The resource-close observer supplies fresh original media/native outcomes; never infer
        // successful retirement merely from the callback returning or an earlier cached revision.
        val registration = value.registration?.original?.snapshot()
        val registrationClear = if (!value.startSubmitted) true else registration != null &&
            registration.closeRequested && registration.knownResourcesClearAtRevision &&
            (registration.tokenAbsenceObserved || registration.registerCall.state ==
                com.code2hack.eyeprojector.directhost.camera.PhoneNativeCallState.NOT_STARTED) &&
            registration.inputs.all { it.input.snapshot().resourcesClear }
        val inputsClear = value.inputs.all { original -> original.input.snapshot().let {
            it.resourcesClear && (!it.producerBound || it.producerUseStopped)
        } }
        val mediaClear = value.groups.all { it.retired && it.fanout.nativeResourcesClear && it.fanout.joined }
        if (!registrationClear || !inputsClear || !mediaClear) return // Remain owned/pending; no invented full-H or timer.
        val original = synchronized(managedDisplayGate) {
            managedDisplay.also { if (it == null) managedDisplayState = "CLOSED_NOT_CREATED" }
        } ?: return
        if (!managedDisplayReleaseStarted.compareAndSet(false, true)) return
        managedDisplayState = "RELEASING"
        val closer = Thread({
            try {
                original.close()
                synchronized(managedDisplayGate) {
                    managedDisplayState = if (managedDisplayFailure == null) "CLOSED" else "CLOSED_AFTER_FAILURE"
                }
            } catch (error: Throwable) {
                managedDisplayState = "RELEASE_FAILED"
                managedDisplayFailure = (error as? ManagedProjectionDisplay.Failure)?.category ?: "VDM_RELEASE_FAILED"
            } finally { runCatching { recordManagedProjection() } }
        }, "managed-projection-close")
        try { closer.start() } catch (_: Throwable) {
            managedDisplayState = "RELEASE_FAILED"; managedDisplayFailure = "VDM_RELEASE_WORKER_START_FAILED"
        }
    }

    private fun recordCameraMedia(value: PhoneCameraMediaSnapshot) {
        tryReleaseManagedDisplay(value)
        // Executed on the media-owned status lane. A late original cannot replace a successor file.
        val registration = value.registration
        val data = JSONObject().put("ownerEpoch", ownerEpoch).put("projectionToken", projectionToken)
            .put("basis", "CAPTURED_ORIGINAL_AS_OF_REVISION").put("revision", value.revision)
            .put("sampleMonoNs", value.sampleMonoNs)
            .put("statusWorkerCount", value.statusWorkers.size)
            .put("statusWorkerJoined", value.statusWorker.joined)
            .put("statusProgress", value.statusProgress?.json() ?: JSONObject.NULL)
            .put("startSubmitted", value.startSubmitted).put("closeRequested", value.closeRequested)
            .put("callerUid", registration?.caller?.uid ?: JSONObject.NULL)
            .put("callerPid", registration?.caller?.pid ?: JSONObject.NULL)
            .put("callerApi", registration?.caller?.api ?: JSONObject.NULL)
            .put("createVirtualDevicePermission", registration?.caller?.createVirtualDevicePermission ?: JSONObject.NULL)
            .put("serviceDescriptor", registration?.serviceLookup?.descriptor ?: JSONObject.NULL)
            .put("requestedAndroidDeviceId", registration?.original?.requestedProfile?.androidDeviceId ?: JSONObject.NULL)
            .put("targetApplicationUid", "UNOBSERVED")
            .put("registerCall", registration?.registerCall?.state?.name ?: "NOT_STARTED")
            .put("registered", registration?.registerCall?.value ?: JSONObject.NULL)
            .put("cameraIdCall", registration?.cameraIdCalls?.lastOrNull()?.state?.name ?: "NOT_STARTED")
            .put("cameraId", registration?.cameraIdCalls?.lastOrNull()?.value ?: JSONObject.NULL)
            .put("unregisterCall", registration?.unregisterCall?.state?.name ?: "NOT_STARTED")
            .put("postUnregisterId", registration?.postUnregisterId?.value ?: JSONObject.NULL)
            .put("tokenAbsenceObserved", registration?.tokenAbsenceObserved ?: false)
            .put("knownResourcesClearAtRevision", registration?.knownResourcesClearAtRevision ?: false)
            .put("registrationRevision", registration?.revision ?: JSONObject.NULL)
            .put("callbackQuiescence", value.callbackQuiescence).put("fullH", value.fullH)
            .put("sensorAlignment", "UNOBSERVED").put("localGlassesViewfinderAlignment", "UNOBSERVED")
            .put("primaryFailure", value.primaryFailure?.javaClass?.simpleName ?: JSONObject.NULL)
            .put("ownerWorkerLoopReturned", value.worker.loopReturned).put("ownerWorkerJoined", value.worker.joined)
        val inputs = org.json.JSONArray()
        value.inputs.forEach { original -> inputs.put(JSONObject()
            .put("localOriginal", System.identityHashCode(original.input)).put("streamId", original.input.streamId)
            .put("width", original.input.width).put("height", original.input.height)
            .put("pixelFormat", original.input.pixelFormat).put("closeRequested", original.closeRequested)
            .put("producerBound", original.producerBound).put("producerUseStopped", original.producerUseStopped)
            .put("surfaceReleased", original.surfaceReleased).put("resourcesClear", original.resourcesClear)
            .put("releaseFailure", original.releaseFailure?.javaClass?.simpleName ?: JSONObject.NULL)) }
        val groups = org.json.JSONArray()
        value.groups.forEach { group ->
            val decoders = org.json.JSONArray()
            group.decoders.forEach { decoder -> decoders.put(JSONObject()
                .put("localEpoch", System.identityHashCode(decoder.original)).put("stage", decoder.stage)
                .put("queuedAccessUnits", decoder.queuedAccessUnits).put("renderedOutputs", decoder.renderedOutputs)
                .put("rawEncoderPtsUs", decoder.rawPtsUs ?: JSONObject.NULL)
                .put("clockJoin", "UNOBSERVED").put("codecReleaseReturned", decoder.codecReleaseReturned)
                .put("loopReturned", decoder.loopReturned).put("joined", decoder.joined)
                .put("failure", decoder.failure?.javaClass?.simpleName ?: JSONObject.NULL)) }
            val outputs = org.json.JSONArray()
            group.fanout.targets.forEach { target -> outputs.put(JSONObject()
                .put("localInput", System.identityHashCode(target.original.lease))
                .put("admissionClosed", target.admissionClosed).put("capturedNativeCalls", target.capturedCalls)
                .put("drawReturns", target.drawReturns).put("swapReturns", target.swapReturns)
                .put("windowCreateAttempted", target.windowCreateAttempted).put("windowCreated", target.windowCreated)
                .put("glFinishReturned", target.glFinishReturned).put("pbufferRestored", target.pbufferRestored)
                .put("windowDestroyReturned", target.windowDestroyReturned).put("knownNoNativeUse", target.knownNoNativeUse)
                .put("producerUseStopped", target.producerUseStopped)) }
            val textures = org.json.JSONArray()
            group.fanout.decoders.forEach { texture -> textures.put(JSONObject()
                .put("localEpoch", System.identityHashCode(texture.original)).put("frameCallbacks", texture.frameCallbacks)
                .put("framesLatched", texture.framesLatched)
                .put("lastSurfaceTextureTimestampNanos", texture.lastTextureTimestampNanos ?: JSONObject.NULL)
                .put("timestampClockJoin", "UNOBSERVED").put("decoderUseStopped", texture.decoderUseStopped)
                .put("surfaceReleaseReturned", texture.surfaceReleaseReturned)
                .put("surfaceTextureReleaseReturned", texture.surfaceTextureReleaseReturned)
                .put("oesDeleteReturned", texture.oesDeleteReturned)) }
            groups.put(JSONObject().put("localGroup", System.identityHashCode(group.original))
                .put("useGeneration", group.use?.useGeneration ?: JSONObject.NULL)
                .put("liveInputCount", group.liveInputs.size).put("retired", group.retired)
                .put("ingress", org.json.JSONArray().also { rows -> group.ingress.forEach { rows.put(it.json()) } })
                .put("decoders", decoders).put("outputs", outputs).put("textures", textures).put("lastFanoutStage", group.fanoutEvents.lastOrNull()?.stage ?: "UNOBSERVED")
                .put("eglResourcesClear", group.fanout.nativeResourcesClear)
                .put("glLoopReturned", group.fanout.loopReturned).put("glJoined", group.fanout.joined))
        }
        data.put("inputs", inputs).put("groups", groups)
        val file = AtomicFile(File(directory, cameraStatusFileName))
        val out = file.startWrite()
        try { out.write(data.toString().toByteArray()); file.finishWrite(out) }
        catch (error: Exception) { file.failWrite(out); throw error }
    }
    private val receiverUsability = SessionUsability(context)
    private val preparingHidRegion = AtomicBoolean()
    private val nativeResourcesClosing = AtomicBoolean()
    // Resource work only. JNI/map/FD cleanup never occupies Function/startup/write deadlines.
    private val nativeSetup = Executors.newSingleThreadExecutor { task -> Thread(task, "eyeprojector-native-setup") }
    val panelContext = PanelContext(context.ownerEpoch, context.projectionToken)
    @Volatile private var lastScaleReportAttempt: ScaleState? = null
    private val scale = LiveScale(
        resize = { size -> writeControl(Scrcpy.resize(size)) },
        publish = { value ->
            lastScaleReportAttempt = value
            send(Frame(Wire.SCALE_STATE, value.requestId, value.bytes()))
        },
        resizePrecise = { size, requestedScale ->
            if (managedDisplayFactory == null) writeControl(Scrcpy.resize(size))
            else {
                val original = synchronized(managedDisplayGate) { managedDisplay }
                    ?: error("Managed projection display is unavailable")
                synchronized(controlLock) {
                    check(running.get())
                    // A new contact can arrive while the pending state is being sent.
                    phoneInput.invalidate()
                    inputControls.cancel()
                    // Queue only. The stream owner performs the WM change off these locks.
                    original.resize(requestedScale)
                }
            }
        },
        beforeRequestPublish = {
            if (managedDisplayFactory != null) {
                synchronized(controlLock) {
                    check(running.get())
                    phoneInput.invalidate()
                    inputControls.cancel()
                }
            }
        },
    )
    private var localListener: LocalServerSocket? = null
    private var video: LocalSocket? = null
    private var control: LocalSocket? = null
    private val inputControls = PhoneTouchDispatcher(controlLock) { control!!.outputStream.write(it) }
    private val functionWork = FunctionWork()
    private val phoneInput: PhoneInputMerger = PhoneInputMerger(controlLock, SystemClock::uptimeMillis,
        { deadline, action ->
            val task = timer.schedule({
                functionWork.submit {
                    try { if (running.get()) action() }
                    catch (error: Exception) { closeSafely(sessionFailureReason(error, control = true), error) }
                }
            }, (deadline - SystemClock.uptimeMillis()).coerceAtLeast(0), TimeUnit.MILLISECONDS)
            val cancel: () -> Unit = { task.cancel(false); Unit }
            cancel
        }, { geometry.get() },
        { event -> running.get() && inputControls.touch(event) },
        { event -> running.get() && inputControls.scroll(event) },
        ::phoneNavigation,
        { inputControls.cancel() },
        { event -> if (running.get()) send(Frame(Wire.RECEIVER_INPUT, payload = event.bytes())) },
        { running.get() })
    private var server: java.lang.Process? = null
    private var inputThread: Thread? = null
    @Volatile private var streamThread: Thread? = null
    private var watchdog: java.util.concurrent.ScheduledFuture<*>? = null
    private val frames = AtomicLong()
    private val panelPower = SessionPanelPower(controlLock, { running.get() && frames.get() > 0 }, panelContext) {
        control!!.outputStream.write(it)
    }
    private val touches = AtomicLong()
    private val heldTouchFrames = AtomicLong()
    private val rejectedHeldTouchFrames = AtomicLong()
    private val begin = System.nanoTime()
    private val startupStage = AtomicReference(StartupStage.CREATED)
    private val childStartedMs = AtomicLong(-1)
    private val firstVideoFrameMs = AtomicLong(-1)
    private val lastPingNs = AtomicLong()
    private val lastPongNs = AtomicLong()
    private val lastWriteChannel = AtomicLong(-1)
    private val scid = SecureRandom().nextInt(Int.MAX_VALUE)
    private val launches: ProjectedAppLaunch = ProjectedAppLaunch("$projectionToken:scrcpy-%08x".format(scid), PlatformProjectionLaunch(context = context), context) {
        synchronized(controlLock) {
            check(running.get())
            phoneInput.invalidate()
            inputControls.cancel()
        }
    }
    private var identifiedDisplay = false
    private fun phoneNavigation(action: Navigation, size: Geometry) {
        if (!running.get()) return
        when (action) {
            Navigation.BACK -> inputControls.navigate(Navigate(action, size))
            Navigation.HOME -> launches.home()
            Navigation.FUNCTION_DOUBLE, Navigation.CONTEXTUAL_HOME -> {
                val original = receiverUsability.snapshot().fact ?: return
                launches.contextualHome {
                    val latest = receiverUsability.snapshot()
                    val fact = latest.fact
                    if (running.get() && !latest.retired && fact != null &&
                        fact.ownerEpoch == original.ownerEpoch && fact.incarnation == original.incarnation &&
                        fact.inputRevision == original.inputRevision && fact.mode == original.mode) {
                        send(Frame(Wire.RECEIVER_INPUT, payload = ReceiverInput(ReceiverInputKind.PANEL, original.inputRevision).bytes()))
                    }
                }
            }
        }
    }
    private val tlsProtocol = peer.session.protocol
    private val localAddress = peer.localAddress.hostAddress
    private val localPort = peer.localPort
    private val peerAddress = peer.inetAddress.hostAddress
    private val peerPort = peer.port

    fun owns(socket: SSLSocket): Boolean = peer === socket

    fun requestScale(id: Long, value: Double, streamRequest: Boolean = false): ScaleState {
        if (!running.get()) return LiveScale.unavailable(id, value)
        return try { scale.request(id, value, publishUnavailable = streamRequest) }
        catch (error: Exception) {
            closeSafely(sessionFailureReason(error, control = true), error)
            LiveScale.unavailable(id, value)
        }
    }
    fun scaleState(): ScaleState = if (running.get()) scale.state() else LiveScale.unavailable()

    fun projectionContext(): ProjectionContext = if (running.get()) context else ProjectionContext()
    override fun receiverUsability(): SessionUsabilitySnapshot = receiverUsability.snapshot()
    override fun launchApp(requestId: Long, packageName: String): ProjectionDisplayState = launches.launch(requestId, packageName)
    override fun projectionDisplayState(): ProjectionDisplayState = launches.snapshot(observe = true)
    override fun phoneHidEdge(routeId: Long, edge: PhoneHidEdge) {
        try { if (running.get()) phoneInput.edge(routeId, edge) }
        catch (error: Exception) { closeSafely(sessionFailureReason(error, control = true), error) }
    }
    override fun phoneHidSnapshot(): com.code2hack.eyeprojector.directhost.activation.snapshot.NativeSnapshotDescriptor? {
        if (running.get() && receiverUsability.needsRegion()) prepareHidRegionAsync()
        return receiverUsability.descriptor()
    }
    override fun phoneHidStampedEdge(routeId: Long, edge: PhoneHidEdge, regionId: String, word: Long) {
        val timed = edge.action == PhoneHidAction.TAP
        val arrived = if (timed) SystemClock.uptimeMillis() else 0L
        if (timed) Log.i("EyeProjectorInput", "hid_tap phase=${edge.phase} route=$routeId stage=arrival uptimeMs=$arrived sourceEventMs=${edge.eventTimeMs} sourceDownMs=${edge.downTimeMs} sourceAgeMs=${arrived - edge.eventTimeMs} tid=${Process.myTid()}")
        try {
            try {
                if (running.get()) phoneInput.stampedEdge(routeId, edge) { point, source ->
                    receiverUsability.accepts(regionId, word, point, source)
                }
            } finally {
                if (timed) {
                    val returned = SystemClock.uptimeMillis()
                    Log.i("EyeProjectorInput", "hid_tap phase=${edge.phase} route=$routeId stage=merger_return uptimeMs=$returned elapsedMs=${returned - arrived} tid=${Process.myTid()}")
                }
            }
        } catch (error: Exception) { closeSafely(sessionFailureReason(error, control = true), error) }
    }
    private fun prepareHidRegion() {
        // Normal producer is package-loaded ShizukuHostService(Context) -> inline Bridge -> Session.
        // Standalone app_process loading is not assumed to have the APK native-library route.
        try { if (running.get()) receiverUsability.prepareRegion() }
        catch (_: LinkageError) { /* Unestablished descriptor remains UNKNOWN; never forge NONE. */ }
        catch (_: Exception) { /* A later original setup request can retry without another key. */ }
        finally { receiverUsability.closeRetiredRegions() }
    }
    private fun prepareHidRegionAsync() {
        if (nativeResourcesClosing.get()) return
        if (!preparingHidRegion.compareAndSet(false, true)) return
        try { nativeSetup.execute { try { prepareHidRegion() } finally { preparingHidRegion.set(false) } } }
        catch (_: java.util.concurrent.RejectedExecutionException) { preparingHidRegion.set(false) }
    }
    private fun beginStartup(): java.util.concurrent.ScheduledFuture<*> {
        val deadline = timer.schedule({ closeSafely(SessionStopReason.STARTUP_DEADLINE) }, 8, TimeUnit.SECONDS)
        prepareHidRegionAsync()
        return deadline
    }
    private fun checkWriteDeadline() {
        val attempt = writes.active.get() ?: return
        val decisionNs = System.nanoTime()
        if (decisionNs - attempt.startedMonoNs > 2_000_000_000L)
            closeSafely(SessionStopReason.WRITE_DEADLINE, trigger = attempt, decisionNs = decisionNs)
    }
    private fun retireNativeResources() {
        receiverUsability.retire()
        if (nativeResourcesClosing.compareAndSet(false, true)) {
            // No wait/join, including when an original load/map/descriptor operation is blocked.
            nativeSetup.execute { receiverUsability.closeResources() }
            nativeSetup.shutdown()
        }
    }

    fun requestPanelOff(id: Long): PanelPowerState {
        val result = panelPower.requestOff(id)
        if (result.off == PanelSubmission.FAILED) {
            close(SessionStopReason.CONTROL_IO)
            return panelPower.state()
        }
        return result
    }

    fun panelState(): PanelPowerState = panelPower.state()

    private fun writeControl(payload: ByteArray) = synchronized(controlLock) {
        check(running.get())
        phoneInput.invalidate()
        inputControls.cancel()
        control!!.outputStream.write(payload)
    }

    @Synchronized private fun send(frame: Frame) {
        check(running.get())
        val attempt = writes.begin(frame.channel, frame.payload.size)
        lastWriteChannel.set(frame.channel.toLong())
        var failure: Throwable? = null
        try {
            Wire.writeFrame(output, frame)
            if (frame.channel == Wire.PONG) lastPongNs.set(System.nanoTime())
        } catch (error: Throwable) {
            failure = error
            if (firstCloseRequest.get() == null) primaryFailure.compareAndSet(null, error)
            throw error
        } finally {
            writes.complete(attempt, failure)
            if (failure != null || firstCloseRequest.get()?.write === attempt) runCatching {
                Log.w("EyeProjectorTransport", "write_completion ${writeObservation(attempt)}")
            }
        }
    }

    private fun failureCategory(error: Throwable?): String = when (error) {
        null -> "NONE"
        is EOFException -> "EOF"
        is SocketTimeoutException -> "TIMEOUT"
        is IOException -> "IO"
        is InterruptedException -> "INTERRUPTED"
        else -> "INTERNAL"
    }

    private fun writeObservation(attempt: ProjectionWriteAttempt?): JSONObject {
        if (attempt == null) return JSONObject().put("observed", false)
        val completion = attempt.completion.get()
        return JSONObject().put("observed", true).put("attemptId", attempt.id)
            .put("channel", attempt.channel).put("payloadBytes", attempt.bytes)
            .put("startedMonoNs", attempt.startedMonoNs).put("writerId", attempt.writerId)
            .put("writerName", attempt.writerName).put("completedMonoNs", completion?.endedMonoNs ?: JSONObject.NULL)
            .put("outcome", if (completion == null) "PENDING" else if (completion.failure == null) "RETURNED" else "FAILED")
            .put("failureCategory", failureCategory(completion?.failure))
    }

    private fun closeObservation(request: CloseRequest): JSONObject = JSONObject()
        .put("reason", request.reason.name).put("requestedMonoNs", request.requestedMonoNs)
        .put("uptimeMs", request.uptimeMs).put("write", writeObservation(request.write))
        .put("writeOutcomeAtRequest", request.completionAtRequest?.let { if (it.failure == null) "RETURNED" else "FAILED" } ?: "PENDING")
        .put("writeCompletedAtRequestMonoNs", request.completionAtRequest?.endedMonoNs ?: JSONObject.NULL)
        .put("writeAgeMs", request.write?.let { (request.requestedMonoNs - it.startedMonoNs) / 1_000_000 } ?: 0)
        .put("primaryFailureCategory", failureCategory(request.primary))
        .put("lastWriteChannel", request.lastChannel).put("lastPingMonoNs", request.lastPing)
        .put("lastPongMonoNs", request.lastPong).put("controlState", request.controlState?.name ?: JSONObject.NULL)
        .put("streamState", request.streamState?.name ?: JSONObject.NULL)

    private fun closeWrite(error: Throwable? = null): ProjectionWriteAttempt? {
        val failed = writes.failed.get()
        val cause = failed?.completion?.get()?.failure
        // A different writer can enter send after the failing writer releases its monitor.
        // Bind error-driven close to the original failed attempt rather than that newer write.
        if (cause != null && (cause === error || cause === primaryFailure.get())) return failed
        return writes.active.get() ?: failed
    }

    private fun closeSafely(reason: SessionStopReason, error: Throwable? = null,
        trigger: ProjectionWriteAttempt? = closeWrite(error), decisionNs: Long = System.nanoTime()) {
        try { closeObserved(reason, error, trigger, decisionNs) }
        catch (cleanup: Throwable) {
            cleanupFailure.compareAndSet(null, cleanup)
            runCatching { Log.w("EyeProjectorTransport", "cleanup_failure reason=${firstCloseRequest.get()?.reason} category=${failureCategory(cleanup)} primaryCategory=${failureCategory(primaryFailure.get())}") }
        }
    }

    fun run() {
        val socketName = "scrcpy_%08x".format(scid)
        streamThread = Thread.currentThread()
        // Reverse local sockets avoid a retry race. These AF_UNIX sockets are never network listeners.
        val local = synchronized(resources) {
            check(running.get())
            LocalServerSocket(socketName).also { localListener = it }
        }
        val startup = beginStartup() // Deadline is armed before asynchronous native work can block.
        if (managedDisplayFactory != null) startupStage.set(StartupStage.CREATE_MANAGED_DISPLAY)
        val source = createManagedDisplay()
        if (source != null) inputControls.bindMotionTarget(ManagedProjectionInput(source))
        val command = ShellMain.videoControlCommand(scid, source?.sourceDisplayId)
        // Stock cleanup unlinks its own CLASSPATH JAR. Keep the pinned master for
        // reconnects and let each stock session consume its own verified copy.
        val sessionJar = File(directory, "scrcpy-%08x.jar".format(scid))
        startupStage.set(StartupStage.COPY_JAR)
        ShellMain.copySessionJar(jar, sessionJar)
        startupStage.set(StartupStage.START_SERVER)
        synchronized(resources) {
            check(running.get())
            server = ProcessBuilder(command).apply {
                environment()["CLASSPATH"] = sessionJar.absolutePath
                redirectErrorStream(true); redirectOutput(File(directory, "scrcpy-%08x.log".format(scid)))
            }.start()
        }
        childStartedMs.set((System.nanoTime() - begin) / 1_000_000)
        if (!running.get()) { server!!.destroyForcibly(); error("Session stopped during startup") }
        if (source == null) {
            startupStage.set(StartupStage.ACCEPT_VIDEO)
            local.accept().also { accepted -> synchronized(resources) {
                if (!running.get()) { accepted.close(); error("Session stopped") }
                video = accepted
            } }
        }
        // Pinned scrcpy opens video, audio, control in that order, omitting disabled lanes.
        // Managed mode has exactly one socket: video=false audio=false control=true.
        startupStage.set(StartupStage.ACCEPT_CONTROL)
        local.accept().also { accepted -> synchronized(resources) {
            if (!running.get()) { accepted.close(); error("Session stopped") }
            control = accepted
        } }
        local.close()
        synchronized(resources) { localListener = null }
        watchdog = timer.scheduleWithFixedDelay(::checkWriteDeadline, 250, 250, TimeUnit.MILLISECONDS)
        writeStreamWelcome(peer, output)
        // The override copies this Session's existing stream/orientation configuration. The device
        // belongs to the real VDM source display; frames still arrive from the captured Glasses feed.
        if (source == null) cameraMediaOwner.startRegistration()
        else cameraMediaOwner.startRegistration(PhoneCameraProfile(cameraProfile.streams,
            android.companion.virtualcamera.LensFacing.BACK, cameraProfile.sensorOrientation, source.deviceId))
        if (source != null) {
            // This display already exists. Give it real app content before waiting for
            // encoder metadata; an empty managed display may not produce the first frame.
            source.prepareInitialLayout()
            launches.initialize(source.sourceDisplayId)
            identifiedDisplay = true
        }
        val videoInput = video?.let { DataInputStream(it.inputStream) }
        startupStage.set(StartupStage.READ_CODEC)
        if (videoInput != null) require(videoInput.readInt() == Wire.H264)
        inputThread = Thread({
            var reason = SessionStopReason.CONTROL_END
            var failure: Exception? = null
            try {
                while (running.get()) {
                    val frame = Wire.readFrame(input)
                    if (!receiveControl(frame)) break
                }
            } catch (error: Exception) { reason = sessionFailureReason(error, control = true); failure = error }
            finally { closeSafely(reason, failure) }
        }, "eyeprojector-control").apply { start() }
        startupStage.set(StartupStage.VIDEO_STREAM)
        var reason = SessionStopReason.VIDEO_END
        var failure: Exception? = null
        try {
            while (running.get()) {
                val record = if (source != null) {
                    when (val next = source.readRecord { running.get() }) {
                        null -> break
                        is ManagedProjectionDisplay.Read.Layout -> {
                            val videoGeometry = geometry.get()
                            if (videoGeometry != null) {
                                scale.observeManagedLayout(next.source.layout, this) {
                                    synchronized(controlLock) {
                                        phoneInput.invalidate()
                                        inputControls.cancel()
                                        inputControls.geometry(videoGeometry)
                                    }
                                }
                            }
                            runCatching { recordManagedProjection() }
                            continue
                        }
                        is ManagedProjectionDisplay.Read.Video -> next.record
                    }
                } else Scrcpy.readRecord(videoInput!!) ?: break
                when (record) {
                    is ScrcpyRecord.Session -> {
                        startup.cancel(false)
                        val layout = source?.layoutGeometry() ?: record.geometry
                        scale.observe(record, this, managedResize = source != null, observedGeometry = layout) {
                            synchronized(controlLock) {
                                phoneInput.invalidate()
                                inputControls.geometry(record.geometry)
                                geometry.set(record.geometry)
                            }
                            send(Frame(Wire.GEOMETRY, payload = record.geometry.bytes()))
                        }
                        if (source != null) runCatching { recordManagedProjection() }
                        // Creation is logged before this metadata by the pinned capture. Do not
                        // launch or query while LiveScale holds the output/publication locks.
                        if (!identifiedDisplay) {
                            identifiedDisplay = true
                            val id = source?.sourceDisplayId ?: ProjectionLaunchCommands.displayId(
                                File(directory, "scrcpy-%08x.log".format(scid)).readText()).also { scrcpyNewDisplayId = it }
                            if (id != null) launches.initialize(id)
                        }
                    }
                    is ScrcpyRecord.Packet -> {
                        send(Frame(Wire.VIDEO, record.stamp, record.data))
                        if (!record.config && frames.incrementAndGet() == 1L) {
                            firstVideoFrameMs.set((System.nanoTime() - begin) / 1_000_000)
                            firstFrame()
                        }
                    }
                }
            }
        } catch (error: Exception) {
            reason = sessionFailureReason(error, control = false)
            failure = error
            throw error
        } finally {
            if (failure == null) close(reason) else closeSafely(reason, failure)
        }
    }

    /** Actual authenticated control consumer, shared by the reader and composition checks. */
    private fun receiveControl(frame: Frame): Boolean {
        when (frame.channel) {
            Wire.PING -> { lastPingNs.set(System.nanoTime()); pongLane.submit(frame.stamp) }
            Wire.SCALE_REQUEST -> {
                requestScale(frame.stamp, ScaleRequest.from(frame.payload).scale, streamRequest = true)
            }
            Wire.TAP -> {
                val arrived = SystemClock.uptimeMillis()
                Log.i("EyeProjectorInput", "wire_tap stage=arrival uptimeMs=$arrived tid=${Process.myTid()}")
                try {
                    val tap = Tap.from(frame.payload)
                    if (tap.geometry == geometry.get()) {
                        if (phoneInput.nativeAction { inputControls.tap(tap) }) touches.incrementAndGet()
                    }
                } finally {
                    val returned = SystemClock.uptimeMillis()
                    Log.i("EyeProjectorInput", "wire_tap stage=merger_return uptimeMs=$returned elapsedMs=${returned - arrived} tid=${Process.myTid()}")
                }
            }
            Wire.SCROLL -> {
                val scroll = Scroll.from(frame.payload)
                if (scroll.target.geometry == geometry.get()) phoneInput.nativeAction { inputControls.scroll(scroll) }
            }
            Wire.NAVIGATE -> {
                val navigate = Navigate.from(frame.payload)
                if (navigate.geometry == geometry.get()) {
                    if (navigate.action == Navigation.BACK) phoneInput.nativeAction { inputControls.navigate(navigate) }
                    else phoneNavigation(navigate.action, navigate.geometry)
                }
            }
            Wire.TOUCH -> {
                val arrived = SystemClock.uptimeMillis()
                val touch = Touch.from(frame.payload)
                val timed = touch.phase != Touch.Phase.MOVE
                if (timed) Log.i("EyeProjectorInput", "wire_touch phase=${touch.phase} contact=${touch.contactId} stage=arrival uptimeMs=$arrived tid=${Process.myTid()}")
                try {
                    if (phoneInput.nativeTouch(touch)) heldTouchFrames.incrementAndGet()
                    else rejectedHeldTouchFrames.incrementAndGet()
                } finally {
                    if (timed) {
                        val returned = SystemClock.uptimeMillis()
                        Log.i("EyeProjectorInput", "wire_touch phase=${touch.phase} contact=${touch.contactId} stage=merger_return uptimeMs=$returned elapsedMs=${returned - arrived} tid=${Process.myTid()}")
                    }
                }
            }
            Wire.RECEIVER_USABILITY -> {
                receiverUsability.publish(ReceiverUsability.from(frame.payload))
                if (receiverUsability.needsRegion()) prepareHidRegionAsync()
            }
            Wire.HEAD_POINTER -> {
                val point = HeadPointerState.from(frame.payload)
                val original = receiverUsability.snapshot().fact
                phoneInput.pointer(point, original) { receiverUsability.acceptsPointer(original, point) }
            }
            Wire.CAMERA_PROGRESS -> { require(frame.stamp == 0L); cameraRoles.observeProgress(CameraFeed.readProgress(frame.payload)) }
            Wire.END -> return false
            else -> error("Unsupported control")
        }
        return true
    }

    override fun close() = close(SessionStopReason.OWNER_CLOSE)

    fun close(reason: SessionStopReason) = closeObserved(reason, null, closeWrite(), System.nanoTime())

    private fun closeObserved(reason: SessionStopReason, error: Throwable?, trigger: ProjectionWriteAttempt?, decisionNs: Long) {
        if (error != null && firstCloseRequest.get() == null) primaryFailure.compareAndSet(null, error)
        val request = CloseRequest(reason, decisionNs, SystemClock.uptimeMillis(), trigger,
            trigger?.completion?.get(), primaryFailure.get(), lastWriteChannel.get(), lastPingNs.get(), lastPongNs.get(),
            inputThread?.state, streamThread?.state)
        if (firstCloseRequest.compareAndSet(null, request)) {
            // Capture the request and exact watchdog attempt before retirement can finish a write.
            runCatching {
                val ageMs = request.write?.let { (request.requestedMonoNs - it.startedMonoNs) / 1_000_000 } ?: 0
                Log.w("EyeProjectorTransport", "first_close reason=${request.reason} uptimeMs=${request.uptimeMs} lastWriteChannel=${request.lastChannel} writeAgeMs=$ageMs lastPingMonoNs=${request.lastPing} lastPongMonoNs=${request.lastPong} controlState=${request.controlState} streamState=${request.streamState} observation=${closeObservation(request)}")
            }
        }
        pongLane.retire()
        cameraMediaOwner.requestClose(CameraEndReason.PROJECTION_CLOSED)
        retireManagedDisplay() // Admission closed first; actual VDM destruction is later/off-caller.
        cameraRoles.retireAll(CameraEndReason.PROJECTION_CLOSED)
        retireNativeResources() // Terminal store precedes resource/deadline/stock cleanup; never joins setup.
        release.close {
        running.set(false)
        functionWork.close()
        val stopStartedMs = (System.nanoTime() - begin) / 1_000_000
        val lastStartupStage = startupStage.get()
        val phases = JSONObject()
        var peerInputShutdownReturned: Boolean? = null
        var peerCloseReturned: Boolean? = null
        var childRelease: OwnedChildReleaseObservation? = null
        fun phase(name: String, operation: () -> Unit) {
            val start = System.nanoTime()
            try { operation() } finally { phases.put(name, (System.nanoTime() - start) / 1_000_000) }
        }
        watchdog?.cancel(false)
        // Stop/reap only this Session's fixed launch/query helper before transport release.
        val launchRelease = runCatching { launches.close() }
        synchronized(resources) {
            phase("peerInputShutdownMs") { peerInputShutdownReturned = runCatching { peer.shutdownInput() }.isSuccess }
            phase("peerCloseMs") { peerCloseReturned = runCatching { peer.close() }.isSuccess }
            phase("localListenerCloseMs") { runCatching { localListener?.close() } }
            // Reuse the bounded held-release turn: a stuck writer must not prevent socket/child release.
            phase("inputAndPanelReleaseMs") {
                HeldInputRelease.beforeTransportClose {
                    try { phoneInput.invalidate() }
                    finally { try { inputControls.close() } finally { panelPower.close() } }
                }
            }

            phase("videoCloseMs") { runCatching { video?.close() } }
            phase("controlCloseMs") { runCatching { control?.close() } }
            phase("childReleaseMs") { server?.let { process ->
                childRelease = OwnedChildRelease.release(process)
            } }
        }
        val data = JSONObject().put("audio", "system-bluetooth").put("state", "SESSION_STOPPED").put("frames", frames.get()).put("touches", touches.get())
            .put("heldTouchFrames", heldTouchFrames.get()).put("rejectedHeldTouchFrames", rejectedHeldTouchFrames.get())
            .put("elapsedMs", (System.nanoTime() - begin) / 1_000_000).put("serverAlive", server?.isAlive ?: false)
            .put("stopReason", stopReason.name).put("startupStage", lastStartupStage.name).put("stopStartedMs", stopStartedMs)
            .put("childStartedMs", childStartedMs.get()).put("firstVideoFrameMs", firstVideoFrameMs.get())
            .put("closePhases", phases).put("lastPingMonoNs", lastPingNs.get()).put("lastPongMonoNs", lastPongNs.get())
            .put("peerInputShutdownReturned", peerInputShutdownReturned ?: JSONObject.NULL)
            .put("peerCloseReturned", peerCloseReturned ?: JSONObject.NULL)
            .put("panelPower", panelPowerJson(panelPower.state()))
            .put("lastWriteChannel", lastWriteChannel.get()).put("writeStartedMonoNs", writes.active.get()?.startedMonoNs ?: 0L)
            .put("firstClose", firstCloseRequest.get()?.let(::closeObservation) ?: JSONObject.NULL)
            .put("primaryFailureCategory", failureCategory(primaryFailure.get()))
            .put("cleanupFailureCategory", failureCategory(cleanupFailure.get()))
            .put("pongWorkerTerminated", pongLane.terminated)
            .put("childRelease", childRelease?.let { observation -> JSONObject()
                .put("initiallyInterrupted", observation.initiallyInterrupted)
                .put("steps", org.json.JSONArray().also { steps -> observation.steps.forEach { step ->
                    steps.put(JSONObject().put("stage", step.stage).put("elapsedNanos", step.elapsedNanos)
                        .put("returned", step.returned).put("interruptions", step.interruptions)
                        .put("failureCategory", failureCategory(step.failure)))
                } }) } ?: JSONObject.NULL)
            .put("tls", tlsProtocol).put("localAddress", localAddress).put("localPort", localPort)
            .put("peerAddress", peerAddress).put("peerPort", peerPort).put("scid", "%08x".format(scid))
        // A single sampled report attempt; never wait for the scale transaction during release.
        val scaleState = lastScaleReportAttempt
        data.put("scaleSnapshotBasis", if (scaleState == null) "UNKNOWN_BEFORE_FIRST_REPORT" else "LAST_KNOWN_REPORT_ATTEMPT")
            .put("finalAppliedScaleVerified", false)
            .put("scaleStatus", scaleState?.status?.name ?: "UNKNOWN")
            .put("scaleRequestId", scaleState?.requestId ?: JSONObject.NULL)
            .put("requestedScale", scaleState?.requested?.takeIf { it.isFinite() } ?: JSONObject.NULL)
            .put("observedWidth", scaleState?.observed?.width ?: JSONObject.NULL)
            .put("observedHeight", scaleState?.observed?.height ?: JSONObject.NULL)
            .put("appliedScale", scaleState?.observed?.let(LayoutScale::applied) ?: JSONObject.NULL)
        val displayState = launches.snapshot()
        data.put("displayGeneration", displayState.generation).put("projectionDisplayId", displayState.displayId ?: JSONObject.NULL)
            .put("launchStatus", displayState.status.name).put("launchHelperReleased", launchRelease.isSuccess)
            .put("managedProjection", managedProjectionStatus())
        File(directory, "last-session.json").writeText(data.toString())
        println(data.toString())
        check(server?.isAlive != true) { "Owned stock child release incomplete" }
        launchRelease.getOrThrow()
        }
        if (managedDisplayFactory != null) {
            synchronized(managedDisplayGate) { managedTransportRetired = true }
            tryReleaseManagedDisplay(cameraMediaOwner.snapshot())
        }
    }
}
