package com.code2hack.eyeprojector.directhost

import android.net.LocalSocket
import com.code2hack.eyeprojector.protocol.*
import java.io.*
import java.net.InetAddress
import java.nio.file.Files
import java.util.concurrent.*
import javax.net.ssl.*

/** Only external Android socket/JSON APIs are doubled; Session and its release remain production code. */
private class Peer : SSLSocket() {
    var released = false
    val sink = ByteArrayOutputStream()
    override fun getOutputStream(): OutputStream = sink
    override fun getInputStream(): InputStream = ByteArrayInputStream(byteArrayOf())
    override fun getInetAddress(): InetAddress = InetAddress.getLoopbackAddress()
    override fun getLocalAddress(): InetAddress = InetAddress.getLoopbackAddress()
    override fun getPort() = 41
    override fun getLocalPort() = 42
    override fun close() { released = true }
    override fun getSession(): SSLSession = SSLContext.getDefault().createSSLEngine().session
    override fun getSupportedCipherSuites() = emptyArray<String>()
    override fun getEnabledCipherSuites() = emptyArray<String>()
    override fun setEnabledCipherSuites(v: Array<String>) {}
    override fun getSupportedProtocols() = arrayOf("TLSv1.3")
    override fun getEnabledProtocols() = supportedProtocols
    override fun setEnabledProtocols(v: Array<String>) {}
    override fun addHandshakeCompletedListener(v: HandshakeCompletedListener) {}
    override fun removeHandshakeCompletedListener(v: HandshakeCompletedListener) {}
    override fun startHandshake() {}
    override fun setUseClientMode(v: Boolean) {}
    override fun getUseClientMode() = false
    override fun setNeedClientAuth(v: Boolean) {}
    override fun getNeedClientAuth() = false
    override fun setWantClientAuth(v: Boolean) {}
    override fun getWantClientAuth() = false
    override fun setEnableSessionCreation(v: Boolean) {}
    override fun getEnableSessionCreation() = true
}
private class Child : Process() {
    var alive = true
    override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
    override fun getInputStream(): InputStream = ByteArrayInputStream(byteArrayOf())
    override fun getErrorStream(): InputStream = ByteArrayInputStream(byteArrayOf())
    override fun waitFor() = 0
    override fun waitFor(t: Long, u: TimeUnit) = !alive
    override fun exitValue() = 0
    override fun destroy() { alive = false }
    override fun destroyForcibly(): Process { alive = false; return this }
    override fun isAlive() = alive
}
private class Fixture : AutoCloseable {
    val directory = Files.createTempDirectory("eyeprojector-launch-composition-").toFile()
    val peer = Peer()
    val timer = Executors.newSingleThreadScheduledExecutor()
    val type = Class.forName("com.code2hack.eyeprojector.directhost.Session")
    val session = type.declaredConstructors.single().apply { isAccessible = true }.newInstance(
        peer, DataInputStream(peer.inputStream), directory, File(directory,"unused.jar"), timer, {} as () -> Unit)
    val control = LocalSocket()
    val video = LocalSocket()
    val child = Child()
    init { set("control",control); set("video",video); set("server",child) }
    fun get(name: String): Any? = type.getDeclaredField(name).apply { isAccessible = true }.get(session)
    fun set(name: String, value: Any?) = type.getDeclaredField(name).apply { isAccessible = true }.set(session,value)
    fun adapter(value: ProjectionLaunchAdapter): ProjectedAppLaunch {
        val launcher = get("launches") as ProjectedAppLaunch
        ProjectedAppLaunch::class.java.getDeclaredField("adapter").apply { isAccessible = true }.set(launcher,value)
        return launcher
    }
    fun closeSession() { try { type.getDeclaredMethod("close").apply { isAccessible = true }.invoke(session) }
        catch(e: java.lang.reflect.InvocationTargetException) { throw e.targetException } }
    fun state(): ProjectionDisplayState = type.getDeclaredMethod("projectionDisplayState").apply { isAccessible = true }.invoke(session) as ProjectionDisplayState
    fun launch(id: Long, pkg: String): ProjectionDisplayState =
        type.getDeclaredMethod("launchApp",Long::class.javaPrimitiveType,String::class.java).apply { isAccessible = true }.invoke(session,id,pkg) as ProjectionDisplayState
    override fun close() { runCatching { closeSession() }; timer.shutdownNow(); directory.deleteRecursively() }
}
private open class Adapter : ProjectionLaunchAdapter {
    val calls = mutableListOf<Int>()
    var releaseAttempts = 0
    override fun launch(displayId: Int, packageName: String, home: Boolean): ProjectionCommandResult {
        calls.add(displayId); return ProjectionCommandResult(0,"Status: ok\nActivity: $packageName/.Main\n")
    }
    override fun foreground(displayId: Int) = "example.app"
    override fun close() { releaseAttempts++ }
}
fun main() {
    Fixture().use { f ->
        val a = Adapter()
        val launches = f.adapter(a)
        launches.initialize(47); launches.snapshot(true)
        val touch = f.get("inputControls") as PhoneTouchDispatcher
        val g = Geometry(480,640); touch.geometry(g); touch.touch(Touch(Touch.Phase.DOWN,1,Tap(50,60,g)))
        check(f.launch(8,"example.app").foregroundPackage=="example.app")
        check(!touch.holding && f.control.sink.toString().isNotEmpty())
        f.closeSession()
        check(f.peer.released && f.video.released && f.control.released && !f.child.alive)
        check(f.state().status==ProjectionLaunchStatus.CLOSED && f.launch(9,"example.late").requestId==9L)
        check(a.calls.all { it==47 })
        check(File(f.directory,"last-session.json").readText().contains("\"launchHelperReleased\":true"))
        println("PASS actual Session launch/cancel/close closes fixed helper and existing transports/child")
    }
    Fixture().use { f ->
        val a=object: Adapter() { override fun close() { super.close(); if(releaseAttempts==1) error("synthetic reap failure") } }
        f.adapter(a)
        check(runCatching { f.closeSession() }.isFailure)
        check(f.peer.released && f.video.released && f.control.released && !f.child.alive)
        check(File(f.directory,"last-session.json").readText().contains("\"launchHelperReleased\":false"))
        f.closeSession()
        check(a.releaseAttempts==2)
        check(File(f.directory,"last-session.json").readText().contains("\"launchHelperReleased\":true"))
        println("PASS actual Session retains helper release failure after all existing cleanup, then retries")
    }
    Fixture().use { f ->
        val entered=CountDownLatch(1); val finish=CountDownLatch(1)
        val a=object: Adapter() {
            override fun launch(d:Int,p:String,h:Boolean):ProjectionCommandResult {
                if(!h){ entered.countDown(); while(finish.count>0)try{finish.await()}catch(_:InterruptedException){} }
                return super.launch(d,p,h)
            }
            override fun close(){super.close();finish.countDown()}
        }
        val launches=f.adapter(a)
        launches.initialize(47);launches.snapshot(true)
        val calls=Executors.newSingleThreadExecutor()
        try {
            val pending=calls.submit<ProjectionDisplayState>{ f.launch(22,"example.slow") }
            check(entered.await(2,TimeUnit.SECONDS));f.closeSession()
            val reply=pending.get(2,TimeUnit.SECONDS)
            check(reply.requestId==22L && reply.requestedPackage=="example.slow" && reply.status==ProjectionLaunchStatus.CLOSED)
            check(f.peer.released && f.control.released && !f.child.alive)
            Fixture().use { fresh ->
                val b=Adapter();val new=fresh.adapter(b)
                new.initialize(88);new.snapshot(true)
                check(b.calls==listOf(88) && a.calls.all{it==47})
                check(new.snapshot().generation!=launches.snapshot().generation)
            }
            println("PASS actual Session Stop reaps pending helper; old completion cannot reach fresh Session/display")
        }finally{finish.countDown();calls.shutdownNow()}
    }
    Fixture().use { f ->
        val a=Adapter();val launches=f.adapter(a)
        val completed=CountDownLatch(1)
        val failed=java.util.concurrent.atomic.AtomicReference<Throwable?>()
        synchronized(f.get("controlLock")!!) {
            launches.initialize(47)
            Thread { try{f.closeSession()}catch(e:Throwable){failed.set(e)}finally{completed.countDown()} }.start()
            check(completed.await(2,TimeUnit.SECONDS))
            check(f.control.released && f.video.released && !f.child.alive)
        }
        check(failed.get()==null && launches.snapshot().status==ProjectionLaunchStatus.CLOSED)
        check(a.calls.isEmpty())
        println("PASS actual Session close reaches transports/child while initial Home waits on old control lock")
    }
    println("4 actual Session composition scenarios passed; modeled external APIs, no Android/device proof")
}
