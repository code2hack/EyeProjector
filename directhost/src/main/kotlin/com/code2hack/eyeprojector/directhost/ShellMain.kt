package com.code2hack.eyeprojector.directhost

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.Build
import android.os.Process
import android.os.SystemClock
import com.code2hack.eyeprojector.directhost.input.PhoneInputMerger
import com.code2hack.eyeprojector.directhost.input.FunctionWork
import android.util.AtomicFile
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

private enum class StartupStage { CREATED, COPY_JAR, START_SERVER, ACCEPT_VIDEO, ACCEPT_CONTROL, READ_CODEC, VIDEO_STREAM }

/** Fixed categories only; exception messages and private payloads never enter telemetry. */
internal fun sessionFailureReason(error: Exception, control: Boolean): SessionStopReason = when (error) {
    is EOFException -> if (control) SessionStopReason.CONTROL_EOF else SessionStopReason.VIDEO_EOF
    is SocketTimeoutException -> if (control) SessionStopReason.CONTROL_TIMEOUT else SessionStopReason.VIDEO_TIMEOUT
    is IOException -> if (control) SessionStopReason.CONTROL_IO else SessionStopReason.VIDEO_IO
    else -> if (control) SessionStopReason.CONTROL_ERROR else SessionStopReason.VIDEO_ERROR
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
) : Closeable {
    private val running = AtomicBoolean(true)
    private val release = OwnedRelease()
    private val admission = Any()
    private val active = AtomicReference<Session?>()
    private var launchOperationsAccepted = true
    @Volatile private var lastPanelState = PanelPowerState.unavailable()
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
        var authenticated = false
        var reason = SessionStopReason.OWNER_CLOSE
        try {
            socket.soTimeout = 3500; socket.tcpNoDelay = true
            socket.sendBufferSize = 65536; socket.receiveBufferSize = 4096
            socket.startHandshake() // No scrcpy process, display, media or input exists before mTLS.
            Tls.requireTrustedPeer(socket, directory)
            authenticated = true
            val input = DataInputStream(socket.inputStream)
            when (Wire.readRequest(input)) {
                Wire.STOP_HOST -> { status("HOST_STOPPING"); close(SessionStopReason.HOST_STOP) }
                Wire.STREAM -> {
                    session = synchronized(admission) {
                        if (!running.get() || active.get() != null) return
                        Session(socket, input, directory, jar, timer, ownerEpoch) { if (running.get()) status("STREAMING") }.also { active.set(it) }
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
            finally { runCatching { socket.close() }; peers.remove(socket) }
            if (session != null && active.get() === session && running.get()) {
                try {
                    status(if (!releaseFailed && session.stopReason in setOf(SessionStopReason.OWNER_CLOSE, SessionStopReason.CONTROL_END)) "DISCONNECTED" else "SESSION_FAILED")
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
    @Volatile var stopReason = SessionStopReason.OWNER_CLOSE
        private set
    private val geometry = AtomicReference<Geometry?>()
    private val writeStarted = AtomicLong()
    private val output = DataOutputStream(peer.outputStream)
    private val controlLock = Any()
    private val projectionToken = UUID.randomUUID().toString()
    private val context = ProjectionContext(ownerEpoch, projectionToken)
    val panelContext = PanelContext(context.ownerEpoch, context.projectionToken)
    @Volatile private var lastScaleReportAttempt: ScaleState? = null
    private val scale = LiveScale(
        resize = { size -> writeControl(Scrcpy.resize(size)) },
        publish = { value ->
            lastScaleReportAttempt = value
            send(Frame(Wire.SCALE_STATE, value.requestId, value.bytes()))
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
                    catch (error: Exception) { close(sessionFailureReason(error, control = true)) }
                }
            }, (deadline - SystemClock.uptimeMillis()).coerceAtLeast(0), TimeUnit.MILLISECONDS)
            val cancel: () -> Unit = { task.cancel(false); Unit }
            cancel
        }, { geometry.get() }, inputControls::touch, inputControls::scroll,
        ::phoneNavigation,
        { inputControls.cancel() },
        { event -> if (running.get()) send(Frame(Wire.RECEIVER_INPUT, payload = event.bytes())) })
    private var server: java.lang.Process? = null
    private var inputThread: Thread? = null
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
        if (action == Navigation.HOME) launches.home() else inputControls.navigate(Navigate(action, size))
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
            close(sessionFailureReason(error, control = true))
            LiveScale.unavailable(id, value)
        }
    }
    fun scaleState(): ScaleState = if (running.get()) scale.state() else LiveScale.unavailable()

    fun projectionContext(): ProjectionContext = if (running.get()) context else ProjectionContext()
    override fun launchApp(requestId: Long, packageName: String): ProjectionDisplayState = launches.launch(requestId, packageName)
    override fun projectionDisplayState(): ProjectionDisplayState = launches.snapshot(observe = true)
    override fun phoneHidEdge(routeId: Long, edge: PhoneHidEdge) {
        try { if (running.get()) phoneInput.edge(routeId, edge) }
        catch (error: Exception) { close(sessionFailureReason(error, control = true)) }
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
        writeStarted.set(System.nanoTime())
        lastWriteChannel.set(frame.channel.toLong())
        try {
            Wire.writeFrame(output, frame)
            if (frame.channel == Wire.PONG) lastPongNs.set(System.nanoTime())
        } finally { writeStarted.set(0) }
    }

    fun run() {
        val socketName = "scrcpy_%08x".format(scid)
        // Reverse local sockets avoid a retry race. These AF_UNIX sockets are never network listeners.
        val local = synchronized(resources) {
            check(running.get())
            LocalServerSocket(socketName).also { localListener = it }
        }
        val startup = timer.schedule({ close(SessionStopReason.STARTUP_DEADLINE) }, 8, TimeUnit.SECONDS)
        val command = ShellMain.videoControlCommand(scid)
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
        startupStage.set(StartupStage.ACCEPT_VIDEO)
        local.accept().also { accepted -> synchronized(resources) {
            if (!running.get()) { accepted.close(); error("Session stopped") }
            video = accepted
        } }
        startupStage.set(StartupStage.ACCEPT_CONTROL)
        local.accept().also { accepted -> synchronized(resources) {
            if (!running.get()) { accepted.close(); error("Session stopped") }
            control = accepted
        } }
        local.close()
        synchronized(resources) { localListener = null }
        watchdog = timer.scheduleWithFixedDelay({
            val since = writeStarted.get()
            if (since != 0L && System.nanoTime() - since > 2_000_000_000L) close(SessionStopReason.WRITE_DEADLINE)
        }, 250, 250, TimeUnit.MILLISECONDS)
        Wire.writeWelcome(output)
        val videoInput = DataInputStream(video!!.inputStream)
        startupStage.set(StartupStage.READ_CODEC)
        require(videoInput.readInt() == Wire.H264)
        inputThread = Thread({
            var reason = SessionStopReason.CONTROL_END
            try {
                while (running.get()) {
                    val frame = Wire.readFrame(input)
                    if (!receiveControl(frame)) break
                }
            } catch (error: Exception) { reason = sessionFailureReason(error, control = true) }
            finally { close(reason) }
        }, "eyeprojector-control").apply { start() }
        startupStage.set(StartupStage.VIDEO_STREAM)
        var reason = SessionStopReason.VIDEO_END
        try {
            while (running.get()) {
                when (val record = Scrcpy.readRecord(videoInput)) {
                    is ScrcpyRecord.Session -> {
                        startup.cancel(false)
                        scale.observe(record, this) {
                            synchronized(controlLock) {
                                phoneInput.invalidate()
                                inputControls.geometry(record.geometry)
                                geometry.set(record.geometry)
                            }
                            send(Frame(Wire.GEOMETRY, payload = record.geometry.bytes()))
                        }
                        // Creation is logged before this metadata by the pinned capture. Do not
                        // launch or query while LiveScale holds the output/publication locks.
                        if (!identifiedDisplay) {
                            identifiedDisplay = true
                            val id = ProjectionLaunchCommands.displayId(File(directory, "scrcpy-%08x.log".format(scid)).readText())
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
            throw error
        } finally { close(reason) }
    }

    /** Actual authenticated control consumer, shared by the reader and composition checks. */
    private fun receiveControl(frame: Frame): Boolean {
        when (frame.channel) {
            Wire.PING -> { lastPingNs.set(System.nanoTime()); send(Frame(Wire.PONG, frame.stamp)) }
            Wire.SCALE_REQUEST -> {
                requestScale(frame.stamp, ScaleRequest.from(frame.payload).scale, streamRequest = true)
            }
            Wire.TAP -> {
                val tap = Tap.from(frame.payload)
                if (tap.geometry == geometry.get()) {
                    if (phoneInput.nativeAction { inputControls.tap(tap) }) touches.incrementAndGet()
                }
            }
            Wire.SCROLL -> {
                val scroll = Scroll.from(frame.payload)
                if (scroll.target.geometry == geometry.get()) phoneInput.nativeAction { inputControls.scroll(scroll) }
            }
            Wire.NAVIGATE -> {
                val navigate = Navigate.from(frame.payload)
                if (navigate.geometry == geometry.get()) {
                    if (navigate.action == Navigation.HOME) launches.home()
                    else phoneInput.nativeAction { inputControls.navigate(navigate) }
                }
            }
            Wire.TOUCH -> {
                if (phoneInput.nativeTouch(Touch.from(frame.payload))) heldTouchFrames.incrementAndGet()
                else rejectedHeldTouchFrames.incrementAndGet()
            }
            Wire.HEAD_POINTER -> phoneInput.pointer(HeadPointerState.from(frame.payload))
            Wire.END -> return false
            else -> error("Unsupported control")
        }
        return true
    }

    override fun close() = close(SessionStopReason.OWNER_CLOSE)

    fun close(reason: SessionStopReason) = release.close {
        if (running.compareAndSet(true, false)) stopReason = reason
        functionWork.close()
        val stopStartedMs = (System.nanoTime() - begin) / 1_000_000
        val lastStartupStage = startupStage.get()
        val phases = JSONObject()
        fun phase(name: String, operation: () -> Unit) {
            val start = System.nanoTime()
            try { operation() } finally { phases.put(name, (System.nanoTime() - start) / 1_000_000) }
        }
        watchdog?.cancel(false)
        // Stop/reap only this Session's fixed launch/query helper before transport release.
        val launchRelease = runCatching { launches.close() }
        synchronized(resources) {
            phase("peerCloseMs") { runCatching { peer.close() } }
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
                runCatching {
                    if (!process.waitFor(2, TimeUnit.SECONDS)) {
                        process.destroy()
                        if (!process.waitFor(2, TimeUnit.SECONDS)) { process.destroyForcibly(); process.waitFor(2, TimeUnit.SECONDS) }
                    }
                }
            } }
        }
        val data = JSONObject().put("audio", "system-bluetooth").put("state", "SESSION_STOPPED").put("frames", frames.get()).put("touches", touches.get())
            .put("heldTouchFrames", heldTouchFrames.get()).put("rejectedHeldTouchFrames", rejectedHeldTouchFrames.get())
            .put("elapsedMs", (System.nanoTime() - begin) / 1_000_000).put("serverAlive", server?.isAlive ?: false)
            .put("stopReason", stopReason.name).put("startupStage", lastStartupStage.name).put("stopStartedMs", stopStartedMs)
            .put("childStartedMs", childStartedMs.get()).put("firstVideoFrameMs", firstVideoFrameMs.get())
            .put("closePhases", phases).put("lastPingMonoNs", lastPingNs.get()).put("lastPongMonoNs", lastPongNs.get())
            .put("panelPower", panelPowerJson(panelPower.state()))
            .put("lastWriteChannel", lastWriteChannel.get()).put("writeStartedMonoNs", writeStarted.get())
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
        File(directory, "last-session.json").writeText(data.toString())
        println(data.toString())
        check(server?.isAlive != true) { "Owned stock child release incomplete" }
        launchRelease.getOrThrow()
    }
}
