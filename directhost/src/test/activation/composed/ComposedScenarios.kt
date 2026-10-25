package com.code2hack.eyeprojector.directhost.activation

import android.content.Context
import android.os.MainQueue
import android.view.InputDevice
import android.view.KeyEvent
import com.code2hack.eyeprojector.directhost.*
import com.code2hack.eyeprojector.directhost.hid.*
import com.code2hack.eyeprojector.directhost.input.PhoneInputMerger
import com.code2hack.eyeprojector.directhost.widget.*
import com.code2hack.eyeprojector.protocol.*
import java.util.concurrent.*

private fun await(message: String, condition: () -> Boolean) {
    val until=System.nanoTime()+TimeUnit.SECONDS.toNanos(4)
    while (!condition() && System.nanoTime()<until) { MainQueue.drain(); Thread.yield() }
    check(condition()) { message }
}

fun main() {
    startupFacadeScenarios()
    val bootstrap=HostBootstrap.current
    bootstrap.value=HostBootstrap.Status(HostBootstrap.Setup.AVAILABLE,"STOPPED")
    val sessions=PhoneSessionController.get(Context())
    val runtime=PhoneHidRuntime.get(Context())
    val service=PhoneHidKeyService(); service.onServiceConnected()
    val lane=sessions.javaClass.getDeclaredField("featureLane").apply { isAccessible=true }.get(sessions) as ScheduledExecutorService
    val command=sessions.javaClass.getDeclaredMethod("command",SessionControl.Request::class.java).apply { isAccessible=true }
    fun request(r: SessionControl.Request) = command.invoke(sessions,r) as SessionState
    val device=InputDevice(71,"composed-device",1,2,"External fixture")
    val key=195; val scan=700
    fun edge(at:Long,down:Long=at,action:Int=KeyEvent.ACTION_DOWN,k:Int=key,s:Int=scan) =
        service.onKeyEvent(KeyEvent(device,action,k,s,down,at))
    val size=Geometry(480,640); val touches=mutableListOf<Touch>()
    val merger=PhoneInputMerger(Any(),{1000L},{_,_->{}},{size},{touches.add(it);true},{true},{_,_->},{},{})
    bootstrap.merger=merger
    val inc=ReceiverIncarnation("actual-facade-fixture",1)
    fun directive() = sessions.state().activation!!.directive!!
    fun report(revision:Long,presented:Boolean) {
        val d=directive()
        request(SessionControl.Request(SessionControl.Operation.STATUS,incarnation=inc,
            presentation=PresentationReport(d.activationId,d.ownerEpoch,d.wakeRevision,inc,revision,if(presented) ReceiverUsabilityMode.PROJECTED else ReceiverUsabilityMode.NONE)))
        MainQueue.drain()
    }
    fun pointer(sequence:Long,available:Boolean) = merger.pointer(HeadPointerState(1,sequence,available,true,20f,30f,Fit(0,0,480,640),size))
    try {
        await("actual facade resolved STOPPED") { sessions.state().phase==SessionPhase.INACTIVE }
        val stale=sessions.state().epoch
        val resumeInactive=pausedPublication(sessions,bootstrap)
        val h=sessions.requestActivation(ActivationCause.HID_ATTACHMENT)
        val offeredPhases=mutableListOf<ActivationPhase>()
        val offeredObservation=sessions.observeActivation(h){offeredPhases.add(it.phase)}
        MainQueue.drain();check(sessions.isActivationCurrent(h) && ActivationPhase.CANCELED !in offeredPhases)
        resumeInactive()

        await("actual core admitted backend") { bootstrap.starts.size==1 }
        val a=bootstrap.starts.single()
        bootstrap.publish(HostBootstrap.Status(HostBootstrap.Setup.AVAILABLE,"STREAMING",sessionEpoch=a,projectionToken="local-token"))
        await("actual app/core context observed") { sessions.state().epoch==a && directive().ownerEpoch==a && sessions.state().phase==SessionPhase.CONNECTING }
        request(SessionControl.Request(SessionControl.Operation.STATUS,incarnation=inc))
        report(1,true)
        check(!sessions.needsActivation() && ActivationPhase.CANCELED !in offeredPhases)
        offeredObservation.close()
        println("PASS actual inactive publisher cannot remove a concurrently committed offer/pending demand")
        runtime.controller.bindings.assign(PhoneHidAction.TAP,PhoneHidBinding(
            PhoneHidButton(PhoneHidDeviceIdentity(device.descriptor,device.vendorId,device.productId),key,scan),device.name,"key"))
        pointer(1,true)
        check(edge(1000));check(edge(1020,1000,KeyEvent.ACTION_UP));bootstrap.drainInput()
        check(touches.map {it.phase}==listOf(Touch.Phase.DOWN,Touch.Phase.UP))
        println("PASS: actual facade/runtime/service/HID route and merger retain normal ready input")

        val before=directive().wakeRevision
        pointer(2,false) // Actual backend receipt precedes the delayed explicit presentation report.
        val count=touches.size
        check(edge(2000));check(edge(2020,2000,KeyEvent.ACTION_UP));bootstrap.drainInput()
        check(touches.size==count && directive().wakeRevision==before && !sessions.needsActivation())
        println("COUNTEREXAMPLE HID27: stale facade PRESENTED + actual merger unavailable -> bound press consumed/discarded with no wake; NOT WAKE PASS")
        check(!edge(2100,k=196,s=701));check(!edge(2120,2100,KeyEvent.ACTION_UP,k=196,s=701))
        check(directive().wakeRevision==before)
        println("COUNTEREXAMPLE HID27: same ordering -> unassigned press passes with no wake; NOT WAKE PASS")

        report(2,false); check(sessions.needsActivation())
        val blocked=CountDownLatch(1); val release=CountDownLatch(1)
        lane.execute { blocked.countDown(); release.await(3,TimeUnit.SECONDS) }
        check(blocked.await(2,TimeUnit.SECONDS))
        val ingress=Executors.newSingleThreadExecutor()
        try {
            check(ingress.submit<Boolean> { edge(3000,k=197,s=702) }.get(1,TimeUnit.SECONDS))
            check(edge(3020,3000,KeyEvent.ACTION_UP,k=197,s=702))
        } finally { release.countDown(); ingress.shutdownNow() }
        await("new observed-detached press requests one wake") { directive().wakeRevision==before+1 }
        check(touches.size==count)
        report(3,true); check(!sessions.needsActivation())
        println("PASS: observed detach owns whole unassigned wake gesture without waiting for blocked feature writer")

        val journal=directive()
        sessions.start(stale); sessions.stop(stale)
        check(directive()==journal && bootstrap.starts.size==1 && bootstrap.stops==0)
        val action=PhoneWidgetAction(PhoneWidgetCommand.STOP,stale)
        val result=PhoneWidgetActions(PhoneWidgetSessionAdapter(sessions),{}).deliver(action)
        check(result==PhoneWidgetRoute.OPEN_STATUS && directive()==journal && bootstrap.stops==0)
        println("PASS: stale real facade and widget epoch actions have no feature/backend effects")

        var receipt: ActivationReady<ProjectionContext>? = null
        sessions.whenActivationReady(h) { receipt=it }
        MainQueue.drain(); check(receipt?.target==ProjectionContext(a,"local-token"))
        val beforeStop=bootstrap.stops
        val resumeOld=pausedPublication(sessions,bootstrap)
        sessions.stop()
        MainQueue.drain();check(!sessions.isActivationCurrent(h))
        val next=sessions.requestActivation(ActivationCause.NOTIFICATION)
        resumeOld()
        await("whole H cancellation dispatches exact backend stop") { bootstrap.stops>beforeStop }

        MainQueue.drain(); check(next.id!=h.id)
        check(bootstrap.starts.size==1)
        bootstrap.publish(HostBootstrap.Status(HostBootstrap.Setup.AVAILABLE,"STOPPED",sessionEpoch=a))
        await("actual release admits H2") { bootstrap.starts.size==2 }
        val stops=bootstrap.stops
        sessions.cancelActivation(h.id)
        lane.submit {}.get(2,TimeUnit.SECONDS)
        check(bootstrap.stops==stops && sessions.activationId()==next.id)
        val a2=bootstrap.starts.last()
        bootstrap.publish(HostBootstrap.Status(HostBootstrap.Setup.AVAILABLE,"STREAMING",sessionEpoch=a2,projectionToken="second-token"))
        await("H2 actual context observed"){directive().ownerEpoch==a2 && sessions.state().phase==SessionPhase.CONNECTING}
        report(4,true)
        var ready2:ActivationReady<ProjectionContext>?=null
        sessions.whenActivationReady(next){ready2=it};MainQueue.drain()
        check(!sessions.needsActivation() && ready2?.target==ProjectionContext(a2,"second-token")) {
            val observed=sessions.javaClass.getDeclaredField("observedActivation").apply{isAccessible=true}.get(sessions)
            "H2 not Ready: needs=${sessions.needsActivation()}, ready=$ready2, observed=$observed, state=${sessions.state()}, current=${sessions.activationId()}"
        }
        println("PASS actual stale publisher cannot restore stopped H1 or lose H2; H2 reaches Ready after actual old-A release")
        println("PASS: actual facade preserves release barrier and H1 callback cannot cancel H2")
        val remoteGate=PublicationGate(bootstrap)
        val caller=Executors.newSingleThreadExecutor()
        val stopping=caller.submit<SessionState>{request(SessionControl.Request(SessionControl.Operation.STATUS,cancelActivation=CancelActivation(next.id)))}
        remoteGate.entered()
        val overlap=sessions.requestActivation(ActivationCause.HID_PRESS)
        check(overlap.id!=next.id) // Fast Stop now retires original H BEFORE ordered feature effects.
        remoteGate.release();stopping.get(3,TimeUnit.SECONDS);caller.shutdownNow()
        check(!sessions.isActivationCurrent(next))
        val third=sessions.requestActivation(ActivationCause.HID_PRESS)
        check(third.id!=next.id && third.id==overlap.id)
        bootstrap.publish(HostBootstrap.Status(HostBootstrap.Setup.AVAILABLE,"STOPPED",sessionEpoch=a2))
        await("post-ack fresh H3 starts"){bootstrap.starts.size==3}
        val a3=bootstrap.starts.last()
        bootstrap.publish(HostBootstrap.Status(HostBootstrap.Setup.AVAILABLE,"STREAMING",sessionEpoch=a3,projectionToken="third-token"))
        await("H3 directive observed by Receiver"){directive().ownerEpoch==a3 && sessions.state().phase==SessionPhase.CONNECTING}
        report(5,true)
        await("post-ack fresh H3 ready"){!sessions.needsActivation()}
        println("PASS actual remote Stop ack retires overlapping original-H demand; next fresh trigger reaches Ready")

        sessions.stop()
        val fourth=sessions.requestActivation(ActivationCause.NOTIFICATION)
        lane.submit{}.get(2,TimeUnit.SECONDS)
        check(bootstrap.starts.size==3)
        val whole=PhoneWidgetAction(PhoneWidgetCommand.STOP,"deliberately-old-epoch",activationId=fourth.id)
        val adapter=PhoneWidgetSessionAdapter(sessions)
        check(!adapter.snapshot().canStart && adapter.snapshot().activationId==fourth.id)
        // A legacy A3 action remains bound to original H3; whole H4 Stop is a separate API.
        sessions.stop(a3);check(sessions.isActivationCurrent(fourth))
        check(PhoneWidgetActions(adapter,{}).deliver(whole)==PhoneWidgetRoute.COMPLETE)
        lane.submit{}.get(2,TimeUnit.SECONDS)
        check(!sessions.isActivationCurrent(fourth))
        bootstrap.publish(HostBootstrap.Status(HostBootstrap.Setup.AVAILABLE,"STOPPED",sessionEpoch=a3))
        lane.submit{}.get(2,TimeUnit.SECONDS);MainQueue.drain();lane.submit{}.get(2,TimeUnit.SECONDS)
        check(bootstrap.starts.size==3)
        val fifth=sessions.requestActivation(ActivationCause.HID_PRESS)
        await("fresh trigger after pending widget Stop starts"){bootstrap.starts.size==4}
        check(PhoneWidgetActions(adapter,{}).deliver(whole)==PhoneWidgetRoute.OPEN_STATUS && sessions.isActivationCurrent(fifth))
        println("PASS actual widget whole-H Stop cancels pending H4 across old-A release; stale H4 action cannot cancel fresh H5")
        val units=org.junit.runner.JUnitCore.runClasses(
            com.code2hack.eyeprojector.directhost.hid.PhoneHidFunctionCompositionTest::class.java,
            com.code2hack.eyeprojector.directhost.widget.PhoneWidgetTest::class.java)
        units.failures.forEach{println(it.toString())}
        check(units.wasSuccessful())
        println("PASS ${units.runCount} canonical Function/cleanup and widget JUnit cases")
        println("KNOWN_GAP: HID27 ordering reproduced with actual facade/HID/merger; acceptance remains unresolved")
    } finally {
        service.onDestroy()
        lane.shutdownNow();lane.awaitTermination(2,TimeUnit.SECONDS)
        val writer=runtime.javaClass.getDeclaredField("writer").apply {isAccessible=true}.get(runtime) as ExecutorService
        writer.shutdownNow();writer.awaitTermination(2,TimeUnit.SECONDS);MainQueue.drain()
    }
    projectedDemandScenarios()

}
