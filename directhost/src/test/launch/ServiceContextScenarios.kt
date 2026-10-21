package com.code2hack.eyeprojector.directhost

import android.content.Context
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLServerSocket

/** External listener is modeled; service, route registry, Bridge and Session selection are production. */
private class Listener:SSLServerSocket() {
    val entered=CountDownLatch(1);val ended=CountDownLatch(1)
    override fun bind(a:SocketAddress,b:Int){}
    override fun getLocalPort()=44378
    override fun setReuseAddress(b:Boolean){}
    override fun setSoTimeout(t:Int){}
    override fun accept():Socket{
        entered.countDown()
        if(!ended.await(50,TimeUnit.MILLISECONDS))throw SocketTimeoutException()
        throw SocketException("closed")
    }
    override fun close(){ended.countDown()}
    override fun getSupportedProtocols()=arrayOf("TLSv1.3")
    override fun getEnabledProtocols()=supportedProtocols
    override fun setEnabledProtocols(p:Array<String>){}
    override fun getSupportedCipherSuites()=emptyArray<String>()
    override fun getEnabledCipherSuites()=emptyArray<String>()
    override fun setEnabledCipherSuites(v:Array<String>){}
    override fun setNeedClientAuth(b:Boolean){}
    override fun getNeedClientAuth()=true
    override fun setWantClientAuth(b:Boolean){}
    override fun getWantClientAuth()=false
    override fun setUseClientMode(b:Boolean){}
    override fun getUseClientMode()=false
    override fun setEnableSessionCreation(b:Boolean){}
    override fun getEnableSessionCreation()=true
}
private fun setBridgeSession(bridge:Bridge,f:Fixture) {
    val t=Bridge::class.java
    val lock=t.getDeclaredField("admission").apply{isAccessible=true}.get(bridge)
    @Suppress("UNCHECKED_CAST")
    val active=t.getDeclaredField("active").apply{isAccessible=true}.get(bridge) as AtomicReference<Any?>
    synchronized(lock){active.set(f.session)}
}
private fun bridge(f:Fixture,report:(String)->Unit,listener:Listener):Bridge {
    f.directory.resolve("endpoint.properties").writeText("host=192.168.42.1\nport=44378\n")
    return Bridge(f.directory,f.directory.resolve("unused.jar"),f.timer,listener,report,f.ownerEpoch).also{setBridgeSession(it,f)}
}
private fun context(f:Fixture)=f.type.getDeclaredMethod("projectionContext").apply{isAccessible=true}.invoke(f.session) as ProjectionContext
private fun result(bytes:ByteArray)=ProjectionDisplayState.from(bytes)

fun serviceContextScenarios(){
    Fixture("owner-a").use { a -> Fixture("owner-b").use { b ->
        val adapterA=Adapter();a.adapter(adapterA).initialize(47);a.state()
        val adapterB=Adapter();b.adapter(adapterB).initialize(88);b.state()
        val ca=context(a);val cb=context(b)
        val listenerA=Listener();val listenerB=Listener()
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        var prepared=0
        val service=ShizukuHostService(Context(),OwnedShellSessions(a.directory.resolve("private-session-root"))) { _,owner,report->
            prepared++
            if(prepared==1){entered.countDown();check(release.await(4,TimeUnit.SECONDS));bridge(a,report,listenerA)}
            else {check(owner==b.ownerEpoch);bridge(b,report,listenerB)}
        }
        fun start(owner:String)=service.start(byteArrayOf(),byteArrayOf(),byteArrayOf(),byteArrayOf(),null,owner)
        val caller=Executors.newSingleThreadExecutor()
        try {
            start(a.ownerEpoch);check(entered.await(2,TimeUnit.SECONDS))
            @Suppress("UNCHECKED_CAST")
            val routes=ShizukuHostService::class.java.getDeclaredField("panelRoutes").apply{isAccessible=true}.get(service) as PanelSessionRoutes<Bridge>
            val oldOwner=PanelSessionRoutes::class.java.getDeclaredField("owner").apply{isAccessible=true}.get(routes) as PanelSessionRoutes.Owner
            fun accepting()=PanelSessionRoutes::class.java.getDeclaredField("accepting").apply{isAccessible=true}.getBoolean(routes)
            val stopping=caller.submit<String>{service.stop()}
            val until=System.nanoTime()+TimeUnit.SECONDS.toNanos(2)
            while(accepting() && System.nanoTime()<until)Thread.yield()
            check(!accepting() && routes.current()==null && !stopping.isDone)
            check(result(service.launchApp(1,"example.late.creation",ca.ownerEpoch,ca.projectionToken)).status==ProjectionLaunchStatus.UNAVAILABLE)
            release.countDown();stopping.get(3,TimeUnit.SECONDS)
            check(routes.current()==null && service.status().contains("\"projectionToken\":\"\""))
            println("PASS actual service Stop invalidates admission before blocked creation and rejects late Bridge publication")
            start(b.ownerEpoch);check(listenerB.entered.await(2,TimeUnit.SECONDS))
            val count=adapterB.calls.size;val queries=adapterB.foregroundReads
            val stale=result(service.launchApp(2,"example.stale.owner",ca.ownerEpoch,ca.projectionToken))
            check(stale.context==ca && stale.requestId==2L && stale.commandExit==null && stale.foregroundPackage==null)
            check(result(service.projectionDisplayState(ca.ownerEpoch,ca.projectionToken)).context==ca)
            check(adapterB.calls.size==count && adapterB.foregroundReads==queries)
            routes.complete(oldOwner, routes.capture(b.ownerEpoch)!!.target, PanelPowerState.unavailable(context = PanelContext(ca.ownerEpoch, ca.projectionToken)))
            check(routes.capture(b.ownerEpoch)!=null)
            val positive=result(service.launchApp(3,"example.valid.b",cb.ownerEpoch,cb.projectionToken))
            check(positive.context==cb && positive.displayId==88 && positive.status==ProjectionLaunchStatus.STARTED)
            check(service.status().contains("\"sessionEpoch\":\"owner-b\"") && service.status().contains("\"projectionToken\":\""+cb.projectionToken+"\""))
            println("PASS actual same service rejects old owner launch/query without replacement commands/observations and accepts fresh context")
            val current=routes.capture(b.ownerEpoch)!!.target
            Fixture(b.ownerEpoch).use { replacement ->
                val adapter=Adapter();replacement.adapter(adapter).initialize(99);replacement.state()
                val fresh=context(replacement)
                setBridgeSession(current,replacement)
                val before=adapter.calls.size;val readBefore=adapter.foregroundReads
                check(result(service.launchApp(4,"example.stale.session",cb.ownerEpoch,cb.projectionToken)).status==ProjectionLaunchStatus.UNAVAILABLE)
                check(result(service.projectionDisplayState(cb.ownerEpoch,cb.projectionToken)).foregroundPackage==null)
                check(adapter.calls.size==before && adapter.foregroundReads==readBefore)
                check(result(service.projectionDisplayState(fresh.ownerEpoch,fresh.projectionToken)).context==fresh)
                println("PASS actual Bridge/service reject same-owner old actual Session token while fresh query succeeds")
                val captured=ShizukuHostService::class.java.getDeclaredMethod("captureLaunchTarget",ProjectionContext::class.java)
                    .apply{isAccessible=true}.invoke(service,fresh) as CapturedProjection
                setBridgeSession(current,b)
                replacement.closeSession()
                val after=result(captured.launchApp(5,"example.captured.old").bytes())
                check(after.status==ProjectionLaunchStatus.CLOSED && after.context==fresh)
                check(adapterB.calls.size==count+1)
                println("PASS production captured Session handle cannot re-resolve the replacement after close/swap")
            }
            val captureBeforeStop=current.captureProjection(cb)!!
            current.invalidateLaunchOperations()
            check(current.captureProjection(cb)==null && !current.projectionContext().available)
            check(captureBeforeStop.projectionDisplayState().context==cb)
            service.stop()
            println("PASS typed-operation invalidation closes new capture while existing handle retains original Session")
        }finally{release.countDown();runCatching{service.stop()};caller.shutdownNow()}
    }}
    val invalidated=mutableListOf<Any>()
    val routes=PanelSessionRoutes<Any> { invalidated.add(it) }
    val old=routes.begin("same-epoch");val oldTarget=Any();routes.publish(old,oldTarget)
    routes.stop()
    val fresh=routes.begin("same-epoch");val target=Any();routes.publish(fresh,target)
    routes.complete(old,oldTarget,PanelPowerState.unavailable())
    val lateTarget=Any();routes.publish(old,lateTarget)
    check(routes.capture("same-epoch")!!.target===target && invalidated.containsAll(listOf(oldTarget,lateTarget)))
    println("PASS actual route publication/retirement uses Owner object identity even when epoch text repeats")
}
