package com.code2hack.eyeprojector.directhost

import android.content.Context
import android.os.Process
import android.system.Os
import androidx.annotation.Keep
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import org.json.JSONObject

/** Loaded by Shizuku from the installed APK, never an ordinary Android Service. */
@Keep
class ShizukuHostService @Keep constructor(private val context: Context) : IHostBootstrap.Stub() {
    private val lifecycle = OwnedHostLifecycle()
    private var directory: File? = null
    @Volatile private var attribution = JSONObject()

    override fun status(): String {
        val snapshot = lifecycle.status()
        return JSONObject().put("state", snapshot.state.name).put("failure", snapshot.failure)
            .put("shellUid", Process.myUid()).put("pid", Process.myPid())
            .put("serverSha256", ShellMain.SERVER_SHA256).put("attribution", attribution).toString()
    }

    @Synchronized override fun start(identity: ByteArray, password: ByteArray, peer: ByteArray, endpoint: ByteArray): String {
        lifecycle.start { ready ->
            // The existing backend contract requires shell, including on rooted Shizuku hosts.
            require(Process.myUid() == 2000)
            directory?.deleteRecursively()
            val session = Files.createTempDirectory(File("/data/local/tmp").toPath(), "eyeprojector-host-").toFile()
            directory = session
            Os.chmod(session.path, 448) // 0700; shell owns the copied session, app owns the originals.
            listOf("identity.p12" to identity, "password" to password, "peer.crt" to peer, "endpoint.properties" to endpoint)
                .forEach { (name, bytes) ->
                    File(session, name).apply { writeBytes(bytes); Os.chmod(path, 384) }
                }
            val jar = File(session, "master-server.jar")
            context.assets.open("scrcpy-server-v5.0.1").use { input -> jar.outputStream().use { input.copyTo(it) } }
            Os.chmod(jar.path, 384)
            require(sha256(jar) == ShellMain.SERVER_SHA256)
            val apk = File(context.applicationInfo.sourceDir)
            attribution = JSONObject().put("apkSha256", sha256(apk)).put("apk", apk.path)
                .put("sessionDirectory", session.path).put("sessionOwnerUid", Process.myUid())
            val bridge = Bridge(session, jar, reportStatus = { state ->
                if (state == "HOST_READY") ready()
            })
            object : OwnedHostLifecycle.Backend {
                override fun run() = bridge.run()
                override fun close() = bridge.close()
            }
        }
        return status()
    }

    override fun stop(): String { lifecycle.stop(); return status() }

    override fun destroy() {
        lifecycle.stop()
        // Shizuku's reserved destroy transaction ends this exact owned process.
        directory?.deleteRecursively()
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
