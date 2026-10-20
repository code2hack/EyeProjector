package com.code2hack.eyeprojector.directhost

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.Build
import android.os.Process
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
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
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
            "display_ime_policy=local", "vd_destroy_content=true", "clipboard_autosync=false", "power_on=false", "keep_active=true", "cleanup=true")

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
) : Closeable {
    private val running = AtomicBoolean(true)
    private val active = AtomicReference<Session?>()
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
            try { workers.execute { serve(socket) } }
            catch (_: java.util.concurrent.RejectedExecutionException) { socket.close() }
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
            authenticated = true
            val input = DataInputStream(socket.inputStream)
            when (Wire.readRequest(input)) {
                Wire.STOP_HOST -> { status("HOST_STOPPING"); close(SessionStopReason.HOST_STOP) }
                Wire.STREAM -> {
                    session = Session(socket, input, directory, jar, timer)
                    if (!active.compareAndSet(null, session)) return
                    status("STREAMING")
                    session.run()
                }
            }
        } catch (_: Exception) {
            reason = SessionStopReason.SESSION_ERROR
            refused.incrementAndGet() // Fixed status only; never log private config/payload/exception text.
            if (!authenticated) unauthenticatedRefused.incrementAndGet()
            if (running.get()) status(if (active.get() == null) "HOST_READY" else "STREAMING")
        } finally {
            session?.close(reason); socket.close()
            if (session != null && active.compareAndSet(session, null) && running.get()) status("DISCONNECTED")
        }
    }

    override fun close() = close(SessionStopReason.OWNER_CLOSE)

    /** Typed local controller hook for the owning Shizuku service; it creates no network identity. */
    fun requestScale(requestId: Long, value: Double): ScaleState =
        active.get()?.requestScale(requestId, value) ?: LiveScale.unavailable(requestId, value)

    fun scaleState(): ScaleState = active.get()?.scaleState() ?: LiveScale.unavailable()

    private fun close(reason: SessionStopReason) {
        if (!running.compareAndSet(true, false)) return
        listener.close(); active.getAndSet(null)?.close(reason)
        workers.shutdownNow(); timer.shutdownNow()
        status("HOST_STOPPED")
    }
}

private class Session(
    private val peer: SSLSocket,
    private val input: DataInputStream,
    private val directory: File,
    private val jar: File,
    private val timer: java.util.concurrent.ScheduledExecutorService,
) : Closeable {
    private val running = AtomicBoolean(true)
    private val geometry = AtomicReference<Geometry?>()
    private val writeStarted = AtomicLong()
    private val output = DataOutputStream(peer.outputStream)
    private val controlLock = Any()
    private val scale = LiveScale(
        resize = { size -> writeControl(Scrcpy.resize(size)) },
        publish = { value -> send(Frame(Wire.SCALE_STATE, value.requestId, value.bytes())) },
    )
    private var localListener: LocalServerSocket? = null
    private var video: LocalSocket? = null
    private var control: LocalSocket? = null
    private var server: java.lang.Process? = null
    private var inputThread: Thread? = null
    private var watchdog: java.util.concurrent.ScheduledFuture<*>? = null
    private val frames = AtomicLong()
    private val touches = AtomicLong()
    private val begin = System.nanoTime()
    private val startupStage = AtomicReference(StartupStage.CREATED)
    private val childStartedMs = AtomicLong(-1)
    private val firstVideoFrameMs = AtomicLong(-1)
    private val scid = SecureRandom().nextInt(Int.MAX_VALUE)
    private val tlsProtocol = peer.session.protocol
    private val localAddress = peer.localAddress.hostAddress
    private val localPort = peer.localPort
    private val peerAddress = peer.inetAddress.hostAddress
    private val peerPort = peer.port

    fun requestScale(id: Long, value: Double): ScaleState {
        if (!running.get()) return LiveScale.unavailable(id, value)
        return try { scale.request(id, value) }
        catch (error: Exception) {
            close(sessionFailureReason(error, control = true))
            LiveScale.unavailable(id, value)
        }
    }
    fun scaleState(): ScaleState = if (running.get()) scale.state() else LiveScale.unavailable()

    private fun writeControl(payload: ByteArray) = synchronized(controlLock) {
        check(running.get())
        control!!.outputStream.write(payload)
    }

    @Synchronized private fun send(frame: Frame) {
        check(running.get())
        writeStarted.set(System.nanoTime())
        try { Wire.writeFrame(output, frame) } finally { writeStarted.set(0) }
    }

    fun run() {
        val socketName = "scrcpy_%08x".format(scid)
        // Reverse local sockets avoid a retry race. These AF_UNIX sockets are never network listeners.
        localListener = LocalServerSocket(socketName)
        val startup = timer.schedule({ close(SessionStopReason.STARTUP_DEADLINE) }, 8, TimeUnit.SECONDS)
        val command = ShellMain.videoControlCommand(scid)
        // Stock cleanup unlinks its own CLASSPATH JAR. Keep the pinned master for
        // reconnects and let each stock session consume its own verified copy.
        val sessionJar = File(directory, "scrcpy-%08x.jar".format(scid))
        startupStage.set(StartupStage.COPY_JAR)
        ShellMain.copySessionJar(jar, sessionJar)
        startupStage.set(StartupStage.START_SERVER)
        server = ProcessBuilder(command).apply {
            environment()["CLASSPATH"] = sessionJar.absolutePath
            redirectErrorStream(true); redirectOutput(File(directory, "scrcpy-%08x.log".format(scid)))
        }.start()
        childStartedMs.set((System.nanoTime() - begin) / 1_000_000)
        if (!running.get()) { server!!.destroyForcibly(); error("Session stopped during startup") }
        startupStage.set(StartupStage.ACCEPT_VIDEO)
        video = localListener!!.accept().also { if (!running.get()) { it.close(); error("Session stopped") } }
        startupStage.set(StartupStage.ACCEPT_CONTROL)
        control = localListener!!.accept().also { if (!running.get()) { it.close(); error("Session stopped") } }
        localListener!!.close(); localListener = null
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
                    when (frame.channel) {
                        Wire.PING -> send(Frame(Wire.PONG, frame.stamp))
                        Wire.SCALE_REQUEST -> {
                            val result = requestScale(frame.stamp, ScaleRequest.from(frame.payload).scale)
                            // Rejections are replies, while valid requests publish their pending/observed transitions.
                            if (result.status in listOf(ScaleStatus.REJECTED, ScaleStatus.UNAVAILABLE)) {
                                send(Frame(Wire.SCALE_STATE, result.requestId, result.bytes()))
                            }
                        }
                        Wire.TAP -> {
                            val tap = Tap.from(frame.payload)
                            if (tap.geometry == geometry.get()) {
                                writeControl(Scrcpy.tap(tap)); touches.incrementAndGet()
                            }
                        }
                        Wire.SCROLL -> {
                            val scroll = Scroll.from(frame.payload)
                            if (scroll.target.geometry == geometry.get()) writeControl(Scrcpy.scroll(scroll))
                        }
                        Wire.NAVIGATE -> {
                            val navigate = Navigate.from(frame.payload)
                            if (navigate.geometry == geometry.get()) writeControl(Scrcpy.navigate(navigate))
                        }
                        Wire.END -> break
                        else -> error("Unsupported control")
                    }
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
                        geometry.set(record.geometry)
                        startup.cancel(false)
                        send(Frame(Wire.GEOMETRY, payload = record.geometry.bytes()))
                        scale.observe(record)
                    }
                    is ScrcpyRecord.Packet -> {
                        send(Frame(Wire.VIDEO, record.stamp, record.data))
                        if (!record.config && frames.incrementAndGet() == 1L) {
                            firstVideoFrameMs.set((System.nanoTime() - begin) / 1_000_000)
                        }
                    }
                }
            }
        } catch (error: Exception) {
            reason = sessionFailureReason(error, control = false)
            throw error
        } finally { close(reason) }
    }

    override fun close() = close(SessionStopReason.OWNER_CLOSE)

    fun close(reason: SessionStopReason) {
        if (!running.compareAndSet(true, false)) return
        val stopStartedMs = (System.nanoTime() - begin) / 1_000_000
        val lastStartupStage = startupStage.get()
        watchdog?.cancel(false)
        runCatching { peer.close() }; runCatching { localListener?.close() }
        runCatching { video?.close() }; runCatching { control?.close() }
        server?.let { process ->
            runCatching {
                if (!process.waitFor(2, TimeUnit.SECONDS)) {
                    process.destroy()
                    if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
                }
            }
        }
        if (inputThread !== Thread.currentThread()) runCatching { inputThread?.join(1000) }
        val data = JSONObject().put("audio", "system-bluetooth").put("state", "SESSION_STOPPED").put("frames", frames.get()).put("touches", touches.get())
            .put("elapsedMs", (System.nanoTime() - begin) / 1_000_000).put("serverAlive", server?.isAlive ?: false)
            .put("stopReason", reason.name).put("startupStage", lastStartupStage.name).put("stopStartedMs", stopStartedMs)
            .put("childStartedMs", childStartedMs.get()).put("firstVideoFrameMs", firstVideoFrameMs.get())
            .put("tls", tlsProtocol).put("localAddress", localAddress).put("localPort", localPort)
            .put("peerAddress", peerAddress).put("peerPort", peerPort).put("scid", "%08x".format(scid))
        val scaleState = scale.state()
        data.put("scaleStatus", scaleState.status.name)
            .put("requestedScale", scaleState.requested.takeIf { it.isFinite() } ?: JSONObject.NULL)
            .put("observedWidth", scaleState.observed?.width ?: JSONObject.NULL)
            .put("observedHeight", scaleState.observed?.height ?: JSONObject.NULL)
            .put("appliedScale", scaleState.observed?.let(LayoutScale::applied) ?: JSONObject.NULL)
        File(directory, "last-session.json").writeText(data.toString())
        println(data.toString())
    }
}
