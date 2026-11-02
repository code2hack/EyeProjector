package com.code2hack.eyeprojector.directhost

import com.code2hack.eyeprojector.protocol.*
import java.lang.management.ManagementFactory
import java.util.concurrent.atomic.AtomicReference

private fun awaitBlocked(worker:Thread,lock:Any) {
    val until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(3)
    var matched=false
    while(System.nanoTime()<until&&!matched) {
        val info=ManagementFactory.getThreadMXBean().getThreadInfo(worker.id)
        matched=info?.threadState==Thread.State.BLOCKED&&info.lockOwnerId==Thread.currentThread().id&&
            info.lockInfo?.identityHashCode==System.identityHashCode(lock)
        if(!matched)Thread.yield()
    }
    check(matched){"caller did not reach exact real component monitor"}
}
internal fun bridgeReceiptScenarios(run:(String,(Joined)->Unit)->Unit) {
    for(stop in listOf(false,true)) run("R187-2-actual-Bridge-original-NORMAL-before-admission-wait-stop-$stop") { j ->
        val f=j.phone
        f.directory.resolve("endpoint.properties").writeText("host=192.168.42.1\nport=44378\n")
        val bridge=Bridge(f.directory,f.directory.resolve("unused.jar"),f.timer,Listener(),{},f.ownerEpoch)
        val type=Bridge::class.java
        @Suppress("UNCHECKED_CAST")
        val active=type.getDeclaredField("active").apply{isAccessible=true}.get(bridge) as AtomicReference<Any?>
        active.set(f.session)
        val lock=type.getDeclaredField("admission").apply{isAccessible=true}.get(bridge)
        val answer=AtomicReference<Boolean>();val error=AtomicReference<Throwable>()
        val worker=Thread{try{answer.set(if(stop)bridge.stopFromUser()else bridge.consumeUserControl(f.type.getDeclaredMethod("projectionContext").apply{isAccessible=true}.invoke(f.session) as ProjectionContext))}catch(t:Throwable){error.set(t)}}
        try {
            synchronized(lock) {worker.start();awaitBlocked(worker,lock);j.hold()}
        } finally {worker.join(4000)}
        check(!worker.isAlive&&error.get()==null){"worker failure: ${error.get()}"}
        j.pump()
        check(answer.get()==false&&j.input.anchor.holding&&j.events.map{it.phase}==listOf(Touch.Phase.DOWN)) {"old NORMAL canceled H2: ${j.events}, answer=${answer.get()}"}
        j.press();check(j.events.map{it.phase}==listOf(Touch.Phase.DOWN,Touch.Phase.UP))
        bridge.close()
    }
    for(mode in listOf("normal","empty","held","changed")) for(stop in listOf(false,true))
        run("R187-2-actual-Bridge-positive-$mode-stop-$stop") { j ->
        val f=j.phone
        f.directory.resolve("endpoint.properties").writeText("host=192.168.42.1\nport=44378\n")
        val bridge=Bridge(f.directory,f.directory.resolve("unused.jar"),f.timer,Listener(),{},f.ownerEpoch)
        @Suppress("UNCHECKED_CAST")
        val active=Bridge::class.java.getDeclaredField("active").apply{isAccessible=true}.get(bridge) as AtomicReference<Any?>
        if(mode!="empty")active.set(f.session)
        val context=if(mode=="empty")ProjectionContext()else f.type.getDeclaredMethod("projectionContext").apply{isAccessible=true}.invoke(f.session) as ProjectionContext
        try {
            if(mode=="held")j.hold()
            val result=if(mode=="changed") {
                val lock=Bridge::class.java.getDeclaredField("admission").apply{isAccessible=true}.get(bridge)
                val answer=AtomicReference<Boolean>();val error=AtomicReference<Throwable>()
                val worker=Thread{try{answer.set(if(stop)bridge.stopFromUser()else bridge.consumeUserControl(context))}catch(t:Throwable){error.set(t)}}
                try {synchronized(lock){worker.start();awaitBlocked(worker,lock);active.set(null)}}finally{worker.join(4000)}
                check(!worker.isAlive&&error.get()==null);answer.get()
            }else if(stop)bridge.stopFromUser()else bridge.consumeUserControl(context)
            check(result==(mode=="normal"||mode=="empty"))
            if(mode=="held"){j.pump();j.canceled();j.press();check(j.input.anchor.holding)}
        }finally{bridge.close()}
    }
    for(port in listOf("scale-direct","scale-framed","panel")) run("R187-2-actual-Session-component-wait-keeps-original-$port") { j ->
        val panel=port=="panel"
        val f=j.phone
        if(!panel)(f.get("scale") as LiveScale).observeManagedLayout(Geometry(480,640))
        val lock=if(panel)f.get("controlLock")!!else f.get("scale")!!
        val error=AtomicReference<Throwable>();val answer=AtomicReference<Any?>()
        val worker=Thread{try{answer.set(if(panel)f.type.getDeclaredMethod("requestPanelOff",Long::class.javaPrimitiveType).apply{isAccessible=true}.invoke(f.session,700L)
            else if(port=="scale-framed")f.receive(Frame(Wire.SCALE_REQUEST,700L,ScaleRequest(1.2).bytes()))
            else f.type.getDeclaredMethod("requestScale",Long::class.javaPrimitiveType,Double::class.javaPrimitiveType,Boolean::class.javaPrimitiveType).apply{isAccessible=true}.invoke(f.session,700L,1.2,false))}catch(t:Throwable){error.set(t)}}
        if(panel)(f.get("frames") as java.util.concurrent.atomic.AtomicLong).set(1)
        val bytes=f.control.sink.toString()
        var suspended=false
        try {
            synchronized(lock){worker.start();awaitBlocked(worker,lock);if(panel){worker.suspend();suspended=true}else j.hold()}
            if(panel)j.hold()
        }finally{if(suspended)worker.resume();worker.join(4000)}
        check(!worker.isAlive&&error.get()==null){"worker failure: ${error.get()}"};j.pump()
        check(j.input.anchor.holding&&j.events.map{it.phase}==listOf(Touch.Phase.DOWN)&&f.control.sink.toString()==bytes) {"old request canceled/effected after H2: ${j.events} answer=${answer.get()}"}
        j.press();check(j.events.map{it.phase}==listOf(Touch.Phase.DOWN,Touch.Phase.UP))
    }
    run("R187-2-scale-output-monitor-rechecks-original-before-publishing") { j ->
        val f=j.phone;val scale=f.get("scale") as LiveScale
        scale.observeManagedLayout(Geometry(480,640));val before=scale.state()
        val error=AtomicReference<Throwable>();var suspended=false
        val worker=Thread{try{f.type.getDeclaredMethod("requestScale",Long::class.javaPrimitiveType,Double::class.javaPrimitiveType,Boolean::class.javaPrimitiveType).apply{isAccessible=true}.invoke(f.session,701L,1.2,false)}catch(t:Throwable){error.set(t)}}
        try {
            synchronized(f.session){worker.start();awaitBlocked(worker,f.session);worker.suspend();suspended=true}
            j.wiring.move(20f,30f);j.pump();j.hold()
        }finally{if(suspended)worker.resume();worker.join(4000)}
        check(!worker.isAlive&&error.get()==null);j.pump()
        check(j.input.anchor.holding&&j.events.map{it.phase}==listOf(Touch.Phase.DOWN))
        check(j.fromPhone.none{it.channel==Wire.SCALE_STATE&&it.stamp==701L}&&scale.state()==before)
    }
    for(panel in listOf(false,true)) run("R187-2-current-Session-scale-panel-positive-$panel") {j->
        val f=j.phone
        if(panel){(f.get("frames") as java.util.concurrent.atomic.AtomicLong).set(1)
            val state=f.type.getDeclaredMethod("requestPanelOff",Long::class.javaPrimitiveType).apply{isAccessible=true}.invoke(f.session,702L) as PanelPowerState
            check(state.off==PanelSubmission.SUBMITTED&&f.control.sink.toString().isNotEmpty())
        }else{
            (f.get("scale") as LiveScale).observeManagedLayout(Geometry(480,640))
            val state=f.type.getDeclaredMethod("requestScale",Long::class.javaPrimitiveType,Double::class.javaPrimitiveType,Boolean::class.javaPrimitiveType).apply{isAccessible=true}.invoke(f.session,702L,1.2,false) as ScaleState
            check(state.status==ScaleStatus.PENDING&&f.control.sink.toString().isNotEmpty());j.pump()
            check(j.fromPhone.any{it.channel==Wire.SCALE_STATE&&it.stamp==702L})
        }
    }

    run("R187-2-scale-inflight-publication-cannot-resize-or-cancel-later-Hold") { j ->
        val f=j.phone;val scale=f.get("scale") as LiveScale
        scale.observeManagedLayout(Geometry(480,640))
        val entered=java.util.concurrent.CountDownLatch(1);val release=java.util.concurrent.CountDownLatch(1)
        val error=AtomicReference<Throwable>();val original=f.get("output") as java.io.DataOutputStream
        val sink=f.peer.sink
        f.set("output",java.io.DataOutputStream(object:java.io.OutputStream(){
            var first=true
            override fun write(value:Int)=write(byteArrayOf(value.toByte()))
            override fun write(bytes:ByteArray,offset:Int,length:Int){
                if(first){first=false;entered.countDown();check(release.await(4,java.util.concurrent.TimeUnit.SECONDS))}
                sink.write(bytes,offset,length)
            }
        }))
        val worker=Thread{try{f.type.getDeclaredMethod("requestScale",Long::class.javaPrimitiveType,Double::class.javaPrimitiveType,Boolean::class.javaPrimitiveType).apply{isAccessible=true}.invoke(f.session,703L,1.2,false)}catch(t:Throwable){error.set(t)}}
        val before=f.control.sink.toString()
        worker.start()
        try {
            check(entered.await(2,java.util.concurrent.TimeUnit.SECONDS))
            j.wiring.move(20f,30f);j.glasses.frames().forEach(f::receive)
            j.wiring.short(android.os.SystemClock.uptimeMillis());j.glasses.frames().forEach(f::receive)
            val until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(2)
            while(!j.input.anchor.holding&&System.nanoTime()<until)Thread.yield()
            check(j.input.anchor.holding&&j.events.map{it.phase}==listOf(Touch.Phase.DOWN))
        }finally{release.countDown();worker.join(4000);f.set("output",original)}
        check(!worker.isAlive&&error.get()==null);j.pump()
        check(j.input.anchor.holding&&j.events.map{it.phase}==listOf(Touch.Phase.DOWN)&&f.control.sink.toString()==before)
        check(j.fromPhone.count{it.channel==Wire.SCALE_STATE&&it.stamp==703L}==1) // Already handed-off frame, never undone.
    }

}

/** The panel's active predicate is a named field boundary. It executes under the real writer
 * monitor AFTER Session's first NORMAL returned. H2 inputs are resolved before this callback. */
internal fun panelFinalReceiptScenario(j:Joined) {
    val f=j.phone
    (f.get("frames") as java.util.concurrent.atomic.AtomicLong).set(1)
    val captured=f.session as CapturedProjection
    val source=captured.uiTargetContext()!!;val fact=captured.receiverUsability().fact!!
    val snapshot=com.code2hack.eyeprojector.directhost.uiaccess.UiTargetSnapshotProvider(
        com.code2hack.eyeprojector.directhost.uiaccess.ManagedUiSourceProvider{source},{j.windows.reader},{0}).read(source.context,"app")
    val target=(com.code2hack.eyeprojector.directhost.anchor.target.AnchorTargetResolver.resolve(savedHold.descriptor,snapshot) as
        com.code2hack.eyeprojector.directhost.anchor.target.AnchorTargetResolution.Unique).current
    val intent=FunctionIntent(FunctionIntentKind.ACTIVATE,FunctionSource.PHONE_HID,991,991,
        FunctionIntentContext(fact,HeadPointerState(fact.inputRevision,1,true,true,10f,20f,Fit(0,0,480,640),Geometry(480,640))))
    val setting=j.runtime.store.snapshot("app").context
    check(j.input.anchor.reconcile(setting))
    val admission=j.input.anchor.capture(intent)!!
    val panel=f.get("panelPower")!!
    val activeField=panel.javaClass.getDeclaredField("active").apply{isAccessible=true}
    @Suppress("UNCHECKED_CAST")val original=activeField.get(panel) as ()->Boolean
    var phase=false
    activeField.set(panel,{
        if(!phase){
            phase=true
            check(Thread.holdsLock(f.get("controlLock")))
            check(Thread.currentThread().stackTrace.any{it.className.endsWith("SessionPanelPower")&&it.methodName=="requestOff"})
            println("PHASE panel first NORMAL returned; inside real final writer before admit/write; install independently resolved H2")
            val merger=f.get("phoneInput") as com.code2hack.eyeprojector.directhost.input.PhoneInputMerger
            val work=f.get("functionWork") as com.code2hack.eyeprojector.directhost.input.FunctionWork
            check(j.input.hold(admission,setting,target,source,{true},merger::nativeAction,
                com.code2hack.eyeprojector.directhost.anchor.execution.AnchorHoldOutput(work){_,_->})==
                com.code2hack.eyeprojector.directhost.anchor.execution.AnchorExecutionStatus.HOLD_SUBMITTED)
        }
        original()
    } as ()->Boolean)
    val before=f.control.sink.toString()
    try {
        val result=f.type.getDeclaredMethod("requestPanelOff",Long::class.javaPrimitiveType).apply{isAccessible=true}.invoke(f.session,704L) as PanelPowerState
        check(phase&&j.input.anchor.holding&&j.events.map{it.phase}==listOf(Touch.Phase.DOWN))
        check(f.control.sink.toString()==before&&result.off==PanelSubmission.UNAVAILABLE){"stale final panel write after initial NORMAL/H2: $result"}
    }finally{activeField.set(panel,original)}
}

/** Inject a host-only wait in the existing pure active predicate. Object.wait releases the
 * actual shared control/writer monitor, so H2 follows the real Function/app FIFO, not a mock gate. */
internal fun panelFinalWaitScenario(j:Joined) {
    val f=j.phone;(f.get("frames") as java.util.concurrent.atomic.AtomicLong).set(1)
    val panel=f.get("panelPower")!!;val lock=f.get("controlLock")!!
    val field=panel.javaClass.getDeclaredField("active").apply{isAccessible=true}
    @Suppress("UNCHECKED_CAST")val original=field.get(panel) as ()->Boolean
    val entered=java.util.concurrent.CountDownLatch(1);val release=java.util.concurrent.atomic.AtomicBoolean()
    var phase=false
    field.set(panel,{
        if(!phase){
            phase=true
            check(Thread.holdsLock(lock))
            check(Thread.currentThread().stackTrace.any{it.className.endsWith("SessionPanelPower")&&it.methodName=="requestOff"})
            println("PHASE panel first NORMAL returned; later actual PanelPower writer predicate waits and releases actual controlLock")
            entered.countDown()
            @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
            while(!release.get())(lock as java.lang.Object).wait(4000)
        }
        original()
    } as ()->Boolean)
    val error=AtomicReference<Throwable>();val answer=AtomicReference<PanelPowerState>()
    val before=f.control.sink.toString()
    val worker=Thread{try{answer.set(f.type.getDeclaredMethod("requestPanelOff",Long::class.javaPrimitiveType).apply{isAccessible=true}.invoke(f.session,705L) as PanelPowerState)}catch(t:Throwable){error.set(t)}}
    worker.start()
    try {check(entered.await(2,java.util.concurrent.TimeUnit.SECONDS));j.hold()}
    finally {
        synchronized(lock){release.set(true);@Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")(lock as java.lang.Object).notifyAll()}
        worker.join(4000);field.set(panel,original)
    }
    check(!worker.isAlive&&error.get()==null);j.pump()
    check(phase&&j.input.anchor.holding&&j.events.map{it.phase}==listOf(Touch.Phase.DOWN))
    check(f.control.sink.toString()==before&&answer.get().off==PanelSubmission.UNAVAILABLE){"old panel command crossed final writer after FIFO H2: ${answer.get()}"}
    j.press();check(j.events.map{it.phase}==listOf(Touch.Phase.DOWN,Touch.Phase.UP))
}
