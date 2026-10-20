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
class ShizukuHostService @Keep constructor(private val context: Context) : IHostBootstrap.Stub() {
    private val lifecycle = OwnedHostLifecycle()
    @Volatile private var projectionBridge: Bridge? = null
    private val sessions = OwnedShellSessions(File("/data/local/tmp", "eyeprojector-${context.packageName}-${context.applicationInfo.uid}"))
    @Volatile private var revokeFailed = false
    @Volatile private var attribution = JSONObject()
    @Volatile private var sessionEpoch = ""

    override fun status(): String {
        val snapshot = lifecycle.status()
        return JSONObject().put("state", if (revokeFailed) "STOP_UNVERIFIED" else snapshot.state.name)
            .put("failure", if (revokeFailed) "SESSION_REVOKE_FAILED" else snapshot.failure)
            .put("sessionRevoked", sessions.isRevoked())
            .put("sessionEpoch", sessionEpoch)
            .put("shellUid", Process.myUid()).put("pid", Process.myPid())
            .put("serverSha256", ShellMain.SERVER_SHA256).put("attribution", attribution).toString()
    }

    @Synchronized override fun start(identity: ByteArray, password: ByteArray, legacyPeer: ByteArray?, endpoint: ByteArray, pairedPeer: ByteArray?, sessionEpoch: String): String {
        if (lifecycle.status().state !in setOf(OwnedHostLifecycle.State.STOPPED, OwnedHostLifecycle.State.FAILED)) return status()
        this.sessionEpoch = sessionEpoch
        lifecycle.start { ready ->
            // The existing backend contract requires shell, including on rooted Shizuku hosts.
            require(Process.myUid() == 2000)
            val session = sessions.create { Os.chmod(it.path, 448) }
            revokeFailed = false
            // Mode 0700 shell root/session; the app retains its original private files.
            BootstrapSessionFiles(identity, password, legacyPeer, endpoint, pairedPeer).copyInto(session) {
                Os.chmod(it.path, 384)
            }
            val jar = File(session, "master-server.jar")
            context.assets.open("scrcpy-server-v5.0.1").use { input -> jar.outputStream().use { input.copyTo(it) } }
            Os.chmod(jar.path, 384)
            require(sha256(jar) == ShellMain.SERVER_SHA256)
            val apk = File(context.applicationInfo.sourceDir)
            attribution = JSONObject().put("apkSha256", sha256(apk)).put("apk", apk.path)
                .put("sessionDirectory", session.path).put("sessionOwnerUid", Process.myUid())
            val bridge = Bridge(session, jar, reportStatus = { state ->
                when (state) {
                    "HOST_READY" -> ready()
                    "SESSION_STARTING" -> lifecycle.report(OwnedHostLifecycle.State.SESSION_STARTING)
                    "STREAMING" -> lifecycle.report(OwnedHostLifecycle.State.STREAMING)
                    "SESSION_FAILED" -> lifecycle.report(OwnedHostLifecycle.State.FAILED, "SESSION_FAILED")
                }
            })
            projectionBridge = bridge
            object : OwnedHostLifecycle.Backend {
                override fun run() = bridge.run()
                override fun close() {
                    try { bridge.close() } finally { if (projectionBridge === bridge) projectionBridge = null }
                }
            }
        }
        return status()
    }

    @Synchronized override fun stop(): String {
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
