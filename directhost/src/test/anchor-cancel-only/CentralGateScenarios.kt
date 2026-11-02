package com.code2hack.eyeprojector.directhost

import com.code2hack.eyeprojector.directhost.anchor.execution.*
import com.code2hack.eyeprojector.protocol.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.CopyOnWriteArrayList

internal fun centralGateScenarios(run:(String,(Joined)->Unit)->Unit) {
    run("actual-read-loop-does-not-wait-on-consumed-control-reply-and-replay-CANCELING-input") { j ->
        j.hold()
        val cancelEntered=CountDownLatch(1);val cancelRelease=CountDownLatch(1)
        val replyEntered=CountDownLatch(1);val replyRelease=CountDownLatch(1)
        val blocked=java.util.concurrent.atomic.AtomicBoolean()
        j.cancelBehavior={cancelEntered.countDown();check(cancelRelease.await(5,TimeUnit.SECONDS));true}
        j.phone.set("output",java.io.DataOutputStream(object:java.io.OutputStream(){
            override fun write(value:Int)=error("Expected complete frame write")
            override fun write(bytes:ByteArray,offset:Int,length:Int){
                val frame=Wire.readFrame(java.io.DataInputStream(bytes.copyOfRange(offset,offset+length).inputStream()))
                if(frame.channel==Wire.CONTROL_RESULT&&blocked.compareAndSet(false,true)){
                    replyEntered.countDown();check(replyRelease.await(5,TimeUnit.SECONDS))
                }
                j.phone.peer.sink.write(bytes,offset,length)
            }
        }))
        val pipe=java.io.PipedInputStream(8192);val source=java.io.PipedOutputStream(pipe);val writer=java.io.DataOutputStream(source)
        j.phone.set("input",java.io.DataInputStream(pipe))
        val failures=java.util.concurrent.CopyOnWriteArrayList<Throwable>()
        val reader=Thread{try{j.phone.type.getDeclaredMethod("readControls").apply{isAccessible=true}.invoke(j.phone.session)}catch(error:Throwable){failures.add(error)}}
        val ping=j.phone.get("lastPingNs") as java.util.concurrent.atomic.AtomicLong
        var progressed=false
        reader.start()
        try{
            val fact=j.glasses.session.observationUsability()!!
            Wire.writeFrame(writer,Frame(Wire.CONTROL_INTENT,payload=ReceiverControlIntent(501,ReceiverControlSource.LOCAL_UI,fact).bytes()))
            check(replyEntered.await(2,TimeUnit.SECONDS)&&cancelEntered.await(2,TimeUnit.SECONDS))
            val before=ping.get()
            Wire.writeFrame(writer,Frame(Wire.TAP,payload=Tap(20,30,Geometry(480,640)).bytes()))
            Wire.writeFrame(writer,Frame(Wire.PING,stamp=501))
            val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(1)
            while(ping.get()==before&&System.nanoTime()<deadline)Thread.yield()
            progressed=ping.get()!=before
            cancelRelease.countDown()
            val finished=System.nanoTime()+TimeUnit.SECONDS.toNanos(1)
            while(j.input.anchor.discardingControls&&System.nanoTime()<finished)Thread.yield()
            check(!j.input.anchor.discardingControls)
            replyRelease.countDown()
            val after=ping.get()
            Wire.writeFrame(writer,Frame(Wire.TAP,payload=Tap(20,30,Geometry(480,640)).bytes()))
            Wire.writeFrame(writer,Frame(Wire.PING,stamp=502))
            val read=System.nanoTime()+TimeUnit.SECONDS.toNanos(2)
            while(ping.get()==after&&System.nanoTime()<read)Thread.yield()
            Wire.writeFrame(writer,Frame(Wire.END));reader.join(3000)
            check(progressed){"Consumed reply stalled actual reader before next CANCELING control/PING"}
            check(j.ordinary==listOf("tap")){"CANCELING control replayed behind reply: ${j.ordinary}"}
        }finally{
            cancelRelease.countDown();replyRelease.countDown();source.close();pipe.close();reader.join(3000)
        }
        check(!reader.isAlive&&failures.isEmpty())
    }
    for (kind in listOf("Tap", "Touch", "HID", "Function", "Home", "App")) run("C128-first-receipt-$kind-cannot-refresh-after-control-lock-wait") { j ->
        j.hold();val entered=CountDownLatch(1);val release=CountDownLatch(1)
        j.cancelBehavior={entered.countDown();check(release.await(5,TimeUnit.SECONDS));true}
        j.phone.receive(Frame(Wire.TAP,payload=Tap(20,30,Geometry(480,640)).bytes()))
        check(entered.await(2,TimeUnit.SECONDS))
        val lock=j.phone.get("controlLock")!!
        val geometry=Geometry(480,640)
        val worker=Thread {
            when(kind){
                "Tap"->j.phone.receive(Frame(Wire.TAP,payload=Tap(20,30,geometry).bytes()))
                "Touch"->j.phone.receive(Frame(Wire.TOUCH,payload=Touch(Touch.Phase.DOWN,130,Tap(20,30,geometry)).bytes()))
                "Home"->j.phone.receive(Frame(Wire.NAVIGATE,payload=Navigate(Navigation.CONTEXTUAL_HOME,geometry).bytes()))
                "App"->(j.phone.session as CapturedProjection).launchApp(130,"app")
                else->{val at=android.os.SystemClock.uptimeMillis();(j.phone.session as CapturedProjection).phoneHidEdge(130,
                    PhoneHidEdge(if(kind=="HID")PhoneHidAction.TAP else PhoneHidAction.ANCHOR,PhoneHidPhase.DOWN,7,195,700,at,at))}
            }
        }
        var paused=false
        var observedState="UNOBSERVED"
        var observedMonitor="none"
        var observedMonitorId=-1
        try {
            synchronized(lock){
                worker.start()
                val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2)
                while(worker.isAlive&&worker.state!=Thread.State.BLOCKED&&System.nanoTime()<deadline)Thread.yield()
                observedState=worker.state.name
                val info=java.lang.management.ManagementFactory.getThreadMXBean().getThreadInfo(worker.id)
                observedMonitor=info?.lockName?:"none"
                observedMonitorId=info?.lockInfo?.identityHashCode?:-1
                if(worker.state==Thread.State.BLOCKED){
                    // JDK17 test scheduler only: hold the actual pre-admission waiter so original
                    // completion wins the monitor deterministically. No production hook/delay.
                    @Suppress("DEPRECATION", "removal") worker.suspend();paused=true
                }
                release.countDown()
            }
            val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2)
            while(j.input.anchor.discardingControls&&System.nanoTime()<deadline)Thread.yield()
            check(!j.input.anchor.discardingControls)
        } finally {
            release.countDown()
            if(paused){@Suppress("DEPRECATION", "removal") worker.resume()}
            worker.join(3000)
            java.io.File(System.getenv("EYEPROJECTOR_HOLD_EVIDENCE"),"C128-$kind-scheduling.txt").writeText(
                "receivedDuringCancellation=true\nobservedState=$observedState\nobservedMonitor=$observedMonitor\n"+
                "expectedControlMonitorIdentity=${System.identityHashCode(lock)}\nobservedMonitorIdentity=$observedMonitorId\n"+
                "didSuspend=$paused\nresumeAttempted=$paused\nfinalState=${worker.state}\nworkerAlive=${worker.isAlive}\n")
        }
        check(!paused||observedMonitorId==System.identityHashCode(lock))
        j.pump()
        check(!worker.isAlive&&j.ordinary.isEmpty()&&j.appLaunches.isEmpty()&&
            j.events.map{it.phase}==listOf(Touch.Phase.DOWN,Touch.Phase.CANCEL)) {
            "Control received during CANCELING refreshed after first wait: ${j.events}; ${j.ordinary}; ${j.appLaunches}"
        }
        j.phone.receive(Frame(Wire.TAP,payload=Tap(20,30,geometry).bytes()));j.pump();check(j.ordinary==listOf("tap"))
    }
    run("Owner119-blocked-original-Short-UP-discards-without-reader-wait-or-replay") { j ->
        j.hold(); val entered=CountDownLatch(1); val release=CountDownLatch(1)
        j.upBehavior={entered.countDown(); check(release.await(5,TimeUnit.SECONDS)); true}
        val short=Thread { j.press() }; short.start(); check(entered.await(2,TimeUnit.SECONDS))
        val done=CountDownLatch(1)
        val tap=Thread { j.phone.receive(Frame(Wire.TAP,payload=Tap(20,30,Geometry(480,640)).bytes())); done.countDown() }
        tap.start();val returned=done.await(1,TimeUnit.SECONDS)
        release.countDown();short.join(3000);tap.join(3000);j.pump()
        check(returned&&j.ordinary.isEmpty()) { "A control waited behind original UP and replayed" }
        check(j.events.map{it.phase}==listOf(Touch.Phase.DOWN,Touch.Phase.UP))
    }
    run("Owner119-blocked-original-CANCEL-discards-controls-without-wait-or-replay") { j ->
        j.hold();val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val failures=CopyOnWriteArrayList<Throwable>();val returned=CountDownLatch(7)
        j.cancelBehavior={entered.countDown();check(release.await(5,TimeUnit.SECONDS));true}
        val old=Thread{try{j.phone.receive(Frame(Wire.TAP,payload=Tap(20,30,Geometry(480,640)).bytes()))}catch(t:Throwable){failures.add(t)}}
        old.start();check(entered.await(2,TimeUnit.SECONDS))
        val geometry=Geometry(480,640);val fact=(j.phone.session as CapturedProjection).receiverUsability().fact!!
        val workers=(0 until 7).map { kind->Thread {
            try {
                when(kind){
                    0->j.phone.receive(Frame(Wire.TAP,payload=Tap(20,30,geometry).bytes()))
                    1->j.phone.receive(Frame(Wire.SCROLL,payload=Scroll(Tap(20,30,geometry),100).bytes()))
                    2->j.phone.receive(Frame(Wire.NAVIGATE,payload=Navigate(Navigation.BACK,geometry).bytes()))
                    3->j.phone.receive(Frame(Wire.TOUCH,payload=Touch(Touch.Phase.DOWN,119,Tap(20,30,geometry)).bytes()))
                    4,5->{
                        val intent=FunctionIntent(if(kind==4)FunctionIntentKind.ACTIVATE else FunctionIntentKind.REGISTER,FunctionSource.GLASSES_NATIVE,119+kind.toLong(),119+kind.toLong(),
                            FunctionIntentContext(fact,HeadPointerState(fact.inputRevision,1,true,true,10f,20f,Fit(0,0,480,640),geometry)))
                        j.phone.receive(Frame(Wire.FUNCTION_INTENT,payload=intent.bytes()))
                    }
                    else->{val at=android.os.SystemClock.uptimeMillis();(j.phone.session as CapturedProjection).phoneHidEdge(119,
                        PhoneHidEdge(PhoneHidAction.ANCHOR,PhoneHidPhase.DOWN,7,195,700,at,at))}
                }
            }catch(t:Throwable){failures.add(t)}finally{returned.countDown()}
        }.also{it.start()} }
        val didNotQueue=returned.await(1,TimeUnit.SECONDS)
        val during=j.events.toList()
        release.countDown();old.join(3000);workers.forEach{it.join(3000)}
        j.pump()
        check(failures.isEmpty()){failures.toString()}
        check(didNotQueue){"Controls waited behind Anchor cancellation and replayed"}
        check(during.map{it.phase}==listOf(Touch.Phase.DOWN,Touch.Phase.CANCEL))
        check(j.events.map{it.phase}==listOf(Touch.Phase.DOWN,Touch.Phase.CANCEL)&&j.ordinary.isEmpty()) {"Replayed controls: ${j.events}; ${j.ordinary}"}
        check(j.messages.none{it is AnchorMessage.Offer})
        // Discarded touch's tail after cancellation cannot become a new operation.
        j.phone.receive(Frame(Wire.TOUCH,payload=Touch(Touch.Phase.UP,119,Tap(20,30,geometry)).bytes()));j.pump()
        check(j.events.size==2)
        j.phone.receive(Frame(Wire.TAP,payload=Tap(20,30,geometry).bytes()));j.pump()
        check(j.ordinary==listOf("tap"))
    }
    run("Owner119-real-control-read-loop-keeps-housekeeping-and-discards-during-CANCEL") { j ->
        j.hold();val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val pipe=java.io.PipedInputStream(8192);val source=java.io.PipedOutputStream(pipe);val writer=java.io.DataOutputStream(source)
        j.phone.set("input",java.io.DataInputStream(pipe))
        j.cancelBehavior={entered.countDown();check(release.await(5,TimeUnit.SECONDS));true}
        val reader=Thread{j.phone.type.getDeclaredMethod("readControls").apply{isAccessible=true}.invoke(j.phone.session)}
        reader.start()
        val geometry=Geometry(480,640)
        try {
            Wire.writeFrame(writer,Frame(Wire.TAP,payload=Tap(20,30,geometry).bytes()))
            check(entered.await(2,TimeUnit.SECONDS))
            for(frame in listOf(Frame(Wire.TAP,payload=Tap(20,30,geometry).bytes()),
                Frame(Wire.SCROLL,payload=Scroll(Tap(20,30,geometry),100).bytes()),
                Frame(Wire.NAVIGATE,payload=Navigate(Navigation.BACK,geometry).bytes()),
                Frame(Wire.TOUCH,payload=Touch(Touch.Phase.DOWN,812,Tap(20,30,geometry)).bytes()))) Wire.writeFrame(writer,frame)
            Wire.writeFrame(writer,Frame(Wire.PING,stamp=1190121))
            val until=System.nanoTime()+TimeUnit.SECONDS.toNanos(2)
            var pong=false
            while(!pong&&System.nanoTime()<until){
                val input=java.io.DataInputStream(j.phone.peer.sink.toByteArray().inputStream())
                while(input.available()>0){val frame=Wire.readFrame(input);if(frame.channel==Wire.PONG&&frame.stamp==1190121L)pong=true}
                if(!pong)Thread.yield()
            }
            check(pong){"Original CANCEL blocked the actual control read loop/housekeeping"}
            check(j.events.map{it.phase}==listOf(Touch.Phase.DOWN,Touch.Phase.CANCEL)&&j.ordinary.isEmpty())
            release.countDown();j.phone.holdFunctions()
            Wire.writeFrame(writer,Frame(Wire.END));reader.join(3000);check(!reader.isAlive)
            check(j.events.map{it.phase}==listOf(Touch.Phase.DOWN,Touch.Phase.CANCEL)&&j.ordinary.isEmpty())
        } finally {release.countDown();source.close();pipe.close();reader.join(3000)}
    }
    run("Owner119-app-user-port-gates-before-store-mutation") { j ->
        j.hold();val saved=j.runtime.store.snapshot("app");val reads=j.windows.reads
        val normal=j.runtime.userControl()
        if(normal)j.runtime.store.clear("app")
        j.pump()
        check(!normal&&j.runtime.store.snapshot("app")==saved&&j.windows.reads==reads)
        check(j.events.map{it.phase}==listOf(Touch.Phase.DOWN,Touch.Phase.CANCEL))
        check(j.runtime.userControl())
    }
    run("Owner119-direct-root-user-Stop-does-not-stop-held-Session") { j ->
        val reports=mutableListOf<String>()
        java.io.File(j.phone.directory,"endpoint.properties").writeText("host=192.0.2.1\nport=44378\n")
        val listener=javax.net.ssl.SSLContext.getDefault().serverSocketFactory.createServerSocket() as javax.net.ssl.SSLServerSocket
        val timer=java.util.concurrent.Executors.newSingleThreadScheduledExecutor()
        val root=Bridge(j.phone.directory,java.io.File(j.phone.directory,"unused.jar"),timer,listener,{reports.add(it)},"session-owner")
        val field=root.javaClass.getDeclaredField("active").apply{isAccessible=true}
        @Suppress("UNCHECKED_CAST") val active=field.get(root) as java.util.concurrent.atomic.AtomicReference<Any?>
        active.set(j.phone.session)
        try {
            j.hold();check(!root.stopFromUser());j.pump()
            check(reports.none{it=="HOST_STOPPING"}&&(j.phone.get("running") as java.util.concurrent.atomic.AtomicBoolean).get())
            check(j.events.map{it.phase}==listOf(Touch.Phase.DOWN,Touch.Phase.CANCEL))
            check(root.stopFromUser());check(reports.count{it=="HOST_STOPPING"}==1)
        } finally {root.close();timer.shutdownNow();listener.close()}
    }
    run("Owner119-scale-and-panel-before-handler-entry") { j ->
        j.hold()
        j.phone.receive(Frame(Wire.SCALE_REQUEST,120,ScaleRequest(0.75).bytes()));j.pump()
        check(j.events.map{it.phase}==listOf(Touch.Phase.DOWN,Touch.Phase.CANCEL))
        check(j.fromPhone.none{it.channel==Wire.SCALE_STATE&&ScaleState.from(it.payload).status==ScaleStatus.PENDING})
        j.press();check(j.input.anchor.holding)
        val result=j.phone.type.getDeclaredMethod("requestPanelOff",Long::class.javaPrimitiveType).apply{isAccessible=true}.invoke(j.phone.session,121L) as PanelPowerState
        j.pump();check(result.off==PanelSubmission.UNAVAILABLE)
        check(j.events.map{it.phase}==listOf(Touch.Phase.DOWN,Touch.Phase.CANCEL,Touch.Phase.DOWN,Touch.Phase.CANCEL))
    }
    run("Owner119-reentrant-Function-is-discarded-during-original-retirement") { j ->
        j.hold();var attempted=false
        j.cancelBehavior={attempted=true;check(j.input.anchor.capture(FunctionIntent(FunctionIntentKind.ACTIVATE,FunctionSource.PHONE_HID,991,991,
            FunctionIntentContext((j.phone.session as CapturedProjection).receiverUsability().fact!!,
                HeadPointerState(1,1,true,true,10f,20f,Fit(0,0,480,640),Geometry(480,640)))))==null);true}
        j.phone.receive(Frame(Wire.TAP,payload=Tap(20,30,Geometry(480,640)).bytes()));j.pump()
        check(attempted&&j.events.map{it.phase}==listOf(Touch.Phase.DOWN,Touch.Phase.CANCEL)&&j.ordinary.isEmpty())
        j.cancelBehavior={true};j.press();check(j.input.anchor.holding)
    }
}

internal fun awaitOriginalStopped(j:Joined) {
    val until=System.nanoTime()+TimeUnit.SECONDS.toNanos(3)
    while((j.phone.get("running") as java.util.concurrent.atomic.AtomicBoolean).get()&&System.nanoTime()<until) Thread.yield()
}
