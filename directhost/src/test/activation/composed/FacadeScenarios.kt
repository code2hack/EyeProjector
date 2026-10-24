package com.code2hack.eyeprojector.directhost.activation

import android.content.Context
import android.content.Environment
import android.os.MainQueue
import com.code2hack.eyeprojector.directhost.*
import com.code2hack.eyeprojector.protocol.*
import java.util.concurrent.*
import java.util.concurrent.atomic.*

private fun until(message:String, condition:()->Boolean) {
    val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(4)
    while(!condition() && System.nanoTime()<end){MainQueue.drain();Thread.yield()};check(condition()){message}
}
internal fun lane(s:PhoneSessionController)=s.javaClass.getDeclaredField("featureLane").apply{isAccessible=true}.get(s) as ScheduledExecutorService
/** Gate an actual source publication at its explicit Bootstrap boundary, outside the fast lock. */
internal class PublicationGate(b:HostBootstrap) {
    private val entered=CountDownLatch(1);private val release=CountDownLatch(1);private val once=AtomicBoolean()
    private val bootstrap=b
    init { b.statusRead={if(Thread.currentThread().stackTrace.any{it.methodName=="publishActivation"} && once.compareAndSet(false,true)){
        entered.countDown();check(release.await(3,TimeUnit.SECONDS))
    }} }
    fun entered(){check(entered.await(2,TimeUnit.SECONDS))}
    fun release(){bootstrap.statusRead=null;release.countDown()}
}
internal fun pausedPublication(s:PhoneSessionController,b:HostBootstrap):()->Unit {
    val gate=PublicationGate(b)
    val revision=(s.javaClass.getDeclaredField("localRevision").apply{isAccessible=true}.get(s) as AtomicLong).get()
    lane(s).execute { s.javaClass.getDeclaredMethod("publishActivation",java.lang.Long.TYPE).apply{isAccessible=true}.invoke(s,revision) }
    gate.entered()
    return gate::release
}

internal fun startupFacadeScenarios() {
    for(stopBeforeRestore in listOf(false,true)) {
        Environment.filesDir=java.nio.file.Files.createTempDirectory("facade-startup-").toFile()
        val saved=ActivationStore(Environment.filesDir.resolve("activation.properties"))
        saved.write(ActivationJournal(current=DesiredActivation("restored-H")))
        val b=HostBootstrap().also{it.value=HostBootstrap.Status(HostBootstrap.Setup.AVAILABLE,"STOPPED")}
        HostBootstrap.current=b
        val gate=CountDownLatch(1);val entered=CountDownLatch(1)
        lateinit var offered:ActivationHandle
        var replacement:ActivationHandle?=null
        b.beforeObserve={observer ->
            val ownerField=observer.javaClass.declaredFields.first{it.type==PhoneSessionController::class.java}.apply{isAccessible=true}
            val owner=ownerField.get(observer) as PhoneSessionController
            lane(owner).execute{entered.countDown();check(gate.await(3,TimeUnit.SECONDS))};check(entered.await(1,TimeUnit.SECONDS))
            offered=owner.requestActivation(ActivationCause.NOTIFICATION)
            if(stopBeforeRestore){owner.stop();replacement=owner.requestActivation(ActivationCause.HID_PRESS)}
        }
        val constructor=PhoneSessionController::class.java.getDeclaredConstructor(Context::class.java).apply{isAccessible=true}
        val s=constructor.newInstance(Context())
        b.beforeObserve=null
        try {
            val selected=replacement?:offered
            val seen=mutableListOf<ActivationPhase>()
            val observing=s.observeActivation(selected){seen.add(it.phase)}
            MainQueue.drain();check(ActivationPhase.CANCELED !in seen)
            if(stopBeforeRestore)check(!s.isActivationCurrent(offered))
            gate.countDown()
            until("initial request admitted once"){b.starts.size==1}
            val a=b.starts.single()
            b.publish(HostBootstrap.Status(HostBootstrap.Setup.AVAILABLE,"STREAMING",sessionEpoch=a,projectionToken="startup-token"))
            until("backend owner published"){s.state().activation?.directive?.ownerEpoch==a && s.state().phase==SessionPhase.CONNECTING}
            val d=s.state().activation!!.directive!!
            val inc=ReceiverIncarnation("startup",1)
            val command=s.javaClass.getDeclaredMethod("command",SessionControl.Request::class.java).apply{isAccessible=true}
            command.invoke(s,SessionControl.Request(SessionControl.Operation.STATUS,incarnation=inc,
                presentation=PresentationReport(d.activationId,a,d.wakeRevision,inc,1,ReceiverUsabilityMode.PROJECTED)))
            MainQueue.drain()
            check(!s.needsActivation() && s.isActivationCurrent(selected) && ActivationPhase.CANCELED !in seen)
            var ready:ActivationReady<ProjectionContext>?=null
            s.whenActivationReady(selected){ready=it};MainQueue.drain();check(ready?.target==ProjectionContext(a,"startup-token"))
            val journal=saved.read()
            if(stopBeforeRestore){
                check(journal.aliases[offered.id]=="restored-H" && "restored-H" in journal.canceled)
                check(d.activationId==replacement!!.id && d.activationId!="restored-H")
                println("PASS actual facade pre-load Stop durably cancels original restored H before retry; H2 reaches Ready")
            } else {
                check(journal.aliases[offered.id]=="restored-H" && d.activationId=="restored-H")
                println("PASS actual facade offered startup handle survives restored canonical alias and delivers original-handle Ready")
            }
            observing.close()
        } finally { gate.countDown();MainQueue.drain();lane(s).shutdownNow();lane(s).awaitTermination(2,TimeUnit.SECONDS) }
    }
    Environment.filesDir=java.nio.file.Files.createTempDirectory("facade-composed-").toFile()
    HostBootstrap.current=HostBootstrap()
}
