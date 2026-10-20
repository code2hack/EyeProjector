package com.code2hack.eyeprojector.directhost

import android.content.Context
import android.os.Process
import android.system.Os
import androidx.annotation.Keep
import java.io.File
import java.security.MessageDigest
import org.json.JSONObject

/** Loaded by Shizuku from the installed APK, never an ordinary Android Service. */
@Keep
class ShizukuHostService internal constructor(
    private val context: Context,
    private val sessions: OwnedShellSessions,
    private val prepareBridge: ((BootstrapSessionFiles, String, (String) -> Unit) -> Bridge)?,
) : IHostBootstrap.Stub() {
    @Keep constructor(context: Context) : this(context,
        OwnedShellSessions(File("/data/local/tmp", "eyeprojector-${context.packageName}-${context.applicationInfo.uid}")), null)
    private val lifecycle = OwnedHostLifecycle()
    @Volatile private var projectionBridge: Bridge? = null
    private val launchRoutes = OwnedProjectionRoutes<Bridge>()
    @Volatile private var revokeFailed = false
    @Volatile private var attribution = JSONObject()
    @Volatile private var sessionEpoch = ""

    override fun status(): String {
        val snapshot = lifecycle.status()
        val route = launchRoutes.snapshot()
        val projection = route?.route?.target?.projectionContext() ?: ProjectionContext()
        return JSONObject().put("state", if (revokeFailed) "STOP_UNVERIFIED" else snapshot.state.name)
            .put("failure", if (revokeFailed) "SESSION_REVOKE_FAILED" else snapshot.failure)
            .put("sessionRevoked", sessions.isRevoked())
            .put("sessionEpoch", route?.owner?.epoch ?: sessionEpoch)
            .put("projectionToken", projection.projectionToken.takeIf { projection.ownerEpoch == route?.owner?.epoch }.orEmpty())
            .put("shellUid", Process.myUid()).put("pid", Process.myPid())
            .put("serverSha256", ShellMain.SERVER_SHA256).put("attribution", attribution).toString()
    }

    @Synchronized override fun start(identity: ByteArray, password: ByteArray, legacyPeer: ByteArray?, endpoint: ByteArray, pairedPeer: ByteArray?, sessionEpoch: String): String {
        if (lifecycle.status().state !in setOf(OwnedHostLifecycle.State.STOPPED, OwnedHostLifecycle.State.FAILED)) return status()
        this.sessionEpoch = sessionEpoch
        val launchOwner = launchRoutes.begin(sessionEpoch)
        lifecycle.start { ready ->
            // The existing backend contract requires shell, including on rooted Shizuku hosts.
            require(Process.myUid() == 2000)
            val report: (String) -> Unit = { state ->
                when (state) {
                    "HOST_READY" -> ready()
                    "SESSION_STARTING" -> lifecycle.report(OwnedHostLifecycle.State.SESSION_STARTING)
                    "STREAMING" -> lifecycle.report(OwnedHostLifecycle.State.STREAMING)
                    "SESSION_FAILED" -> lifecycle.report(OwnedHostLifecycle.State.FAILED, "SESSION_FAILED")
                }
            }
            val files = BootstrapSessionFiles(identity, password, legacyPeer, endpoint, pairedPeer)
            val bridge = prepareBridge?.invoke(files, sessionEpoch, report) ?: prepareOwnedBridge(files, sessionEpoch, report)
            projectionBridge = bridge
            launchRoutes.publish(launchOwner, bridge)
            object : OwnedHostLifecycle.Backend {
                override fun run() = bridge.run()
                override fun close() {
                    try { bridge.close() } finally {
                        launchRoutes.retire(launchOwner)
                        if (projectionBridge === bridge) projectionBridge = null
                    }
                }
            }
        }
        return status()
    }

    /** The public Shizuku constructor always uses this original preparation path. */
    private fun prepareOwnedBridge(files: BootstrapSessionFiles, ownerEpoch: String, report: (String) -> Unit): Bridge {
        val session = sessions.create { Os.chmod(it.path, 448) }
        revokeFailed = false
        files.copyInto(session) { Os.chmod(it.path, 384) }
        val jar = File(session, "master-server.jar")
        context.assets.open("scrcpy-server-v5.0.1").use { input -> jar.outputStream().use { input.copyTo(it) } }
        Os.chmod(jar.path, 384)
        require(sha256(jar) == ShellMain.SERVER_SHA256)
        val apk = File(context.applicationInfo.sourceDir)
        attribution = JSONObject().put("apkSha256", sha256(apk)).put("apk", apk.path)
            .put("sessionDirectory", session.path).put("sessionOwnerUid", Process.myUid())
        return Bridge(session, jar, reportStatus = report, ownerEpoch = ownerEpoch)
    }

    @Synchronized override fun stop(): String {
        launchRoutes.invalidate()?.target?.invalidateLaunchOperations()
        val stopped = lifecycle.stop().state == OwnedHostLifecycle.State.STOPPED
        if (stopped) {
            val removed = sessions.revoke()
            revokeFailed = !removed
        }
        return status()
    }

    override fun requestScale(requestId: Long, scale: Double): ByteArray =
        (projectionBridge?.requestScale(requestId, scale) ?: LiveScale.unavailable(requestId, scale)).bytes()

    override fun scaleState(): ByteArray = (projectionBridge?.scaleState() ?: LiveScale.unavailable()).bytes()

    override fun launchApp(requestId: Long, packageName: String, expectedOwnerEpoch: String, expectedProjectionToken: String): ByteArray {
        val expected = ProjectionContext(expectedOwnerEpoch, expectedProjectionToken)
        val target = captureLaunchTarget(expected)
        return (target?.launchApp(requestId, packageName) ?: ProjectionDisplayState(
            requestId = requestId, requestedPackage = packageName, context = expected)).bytes()
    }

    override fun projectionDisplayState(expectedOwnerEpoch: String, expectedProjectionToken: String): ByteArray {
        val expected = ProjectionContext(expectedOwnerEpoch, expectedProjectionToken)
        return (captureLaunchTarget(expected)?.projectionDisplayState() ?: ProjectionDisplayState(context = expected)).bytes()
    }

    private fun captureLaunchTarget(expected: ProjectionContext): CapturedProjection? =
        launchRoutes.capture(expected.ownerEpoch)?.target?.captureProjection(expected)

    override fun destroy() {
        stop()
        // Shizuku's reserved destroy transaction ends this exact owned process.
        kotlin.system.exitProcess(0)
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
