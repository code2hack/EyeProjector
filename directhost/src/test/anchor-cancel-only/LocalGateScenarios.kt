package com.code2hack.eyeprojector.directhost

import com.code2hack.eyeprojector.protocol.*
import com.code2hack.eyeprojector.directhost.anchor.execution.*
import java.io.DataInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal fun localGateScenarios(run:(String,(Joined)->Unit)->Unit) {
    for(change in listOf("Hold","Binding")) run("queued-original-NORMAL-control-reply-revalidates-before-write-after-$change") { j ->
        val entered=CountDownLatch(1);val release=CountDownLatch(1);val blocked=java.util.concurrent.atomic.AtomicBoolean()
        j.phone.set("output",java.io.DataOutputStream(object:java.io.OutputStream(){
            override fun write(value:Int)=error("Expected encoded frame")
            override fun write(bytes:ByteArray,offset:Int,length:Int){
                val frame=Wire.readFrame(java.io.DataInputStream(bytes.copyOfRange(offset,offset+length).inputStream()))
                if(frame.channel==Wire.CONTROL_RESULT&&blocked.compareAndSet(false,true)){
                    entered.countDown();check(release.await(5,TimeUnit.SECONDS))
                }
                j.phone.peer.sink.write(bytes,offset,length)
            }
        }))
        val fact=j.glasses.session.observationUsability()!!
        fun control(id:Long){j.phone.receive(Frame(Wire.CONTROL_INTENT,payload=ReceiverControlIntent(id,ReceiverControlSource.LOCAL_UI,fact).bytes()))}
        control(650);check(entered.await(2,TimeUnit.SECONDS))
        try {
            control(651);j.phone.holdFlush(j.runtime)
            if(change=="Binding"){
                val shell=(j.phone.get("anchorSession\$delegate") as Lazy<*>).value as AnchorShellSession
                shell.install(object:IAnchorAppBridge.Stub(){
                    override fun onIntent(transactionId:Long,functionIntent:ByteArray,original:IAnchorSession)=Unit
                    override fun onRegistration(message:ByteArray,original:IAnchorSession)=Unit
                })
            }else{
                j.wiring.short(android.os.SystemClock.uptimeMillis())
                val frames=j.glasses.frames();j.fromGlasses.addAll(frames);frames.forEach(j.phone::receive)
                val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2)
                while(j.events.isEmpty()&&System.nanoTime()<deadline)Thread.yield()
                check(j.events.map{it.phase}==listOf(Touch.Phase.DOWN)&&j.input.anchor.holding){"Actual successor Hold did not submit"}
            }
        }finally{release.countDown()}
        j.pump()
        val result=j.fromPhone.filter{it.channel==Wire.CONTROL_RESULT}.map{ReceiverControlResult.from(it.payload)}.single{it.id==651L}
        check(result.disposition==ReceiverControlDisposition.RETIRED){"Queued stale NORMAL survived $change: $result"}
        if(change=="Hold")check(j.events.map{it.phase}==listOf(Touch.Phase.DOWN)&&j.input.anchor.holding)
    }
    for(ending in listOf("replace","death")) run("C151-active-chooser-$ending-does-not-wait-for-original-Result-peer-write") { j ->
        j.register();check(j.wiring.active())
        val offer=j.messages.filterIsInstance<AnchorMessage.Offer>().single()
        val shell=(j.phone.get("anchorSession\$delegate") as Lazy<*>).value as AnchorShellSession
        @Suppress("UNCHECKED_CAST") val bindings=shell.javaClass.getDeclaredField("binding").apply{isAccessible=true}.get(shell) as java.util.concurrent.atomic.AtomicReference<Any?>
        val old=bindings.get()!!
        val originalClient=old.javaClass.getDeclaredField("client").apply{isAccessible=true}.get(old) as IAnchorAppBridge
        val fresh=object:IAnchorAppBridge.Stub(){
            override fun onIntent(transactionId:Long,functionIntent:ByteArray,original:IAnchorSession)=Unit
            override fun onRegistration(message:ByteArray,original:IAnchorSession)=Unit
        }
        val entered=CountDownLatch(1);val release=CountDownLatch(1);val returned=CountDownLatch(1)
        val sent=java.util.concurrent.CopyOnWriteArrayList<AnchorMessage>()
        val failures=java.util.concurrent.CopyOnWriteArrayList<Throwable>()
        j.phone.set("output",java.io.DataOutputStream(object:java.io.OutputStream(){
            override fun write(value:Int)=error("Wire should write a complete encoded frame")
            override fun write(bytes:ByteArray,offset:Int,length:Int){
                val frame=Wire.readFrame(java.io.DataInputStream(bytes.copyOfRange(offset,offset+length).inputStream()))
                if(frame.channel==Wire.ANCHOR){val message=AnchorMessage.from(frame.payload);if(message is AnchorMessage.Result){
                    entered.countDown();check(release.await(5,TimeUnit.SECONDS));sent.add(message)
                }}
                j.phone.peer.sink.write(bytes,offset,length)
            }
        }))
        val before=AnchorControlAdmission();j.input.anchor.receiveControl(before,true)
        val worker=Thread{try{if(ending=="replace")shell.install(fresh)else (originalClient.asBinder() as android.os.Binder).die()}
            catch(error:Throwable){failures.add(error)}finally{returned.countDown()}}
        worker.start()
        var prompt=false;var successor:AnchorControlAdmission?=null
        try{
            check(entered.await(2,TimeUnit.SECONDS)){"No actual original Result write"}
            prompt=returned.await(300,TimeUnit.MILLISECONDS)
            if(prompt){
                check(j.input.anchor.capture(offer.intent,before)==null){"Original source admission remained current"}
                successor=AnchorControlAdmission().also{j.input.anchor.receiveControl(it,true)}
                check(j.input.anchor.capture(offer.intent,successor!!)!=null)
                if(ending=="replace")check(bindings.get()!==old&&bindings.get()!=null)else check(bindings.get()==null)
            }
        }finally{release.countDown();worker.join(3000)}
        j.phone.holdFunctions()
        check(!worker.isAlive&&failures.isEmpty()){failures.toString()}
        check(prompt){"Chooser cancellation blocked $ending teardown caller"}
        check(sent.filterIsInstance<AnchorMessage.Result>().let{it.size==1&&it.single().transactionId==offer.transactionId&&it.single().result==AnchorRegistrationResult.CANCELED})
        check(j.input.anchor.capture(offer.intent,successor!!)!=null){"Old Result tail retired successor source"}
    }
    for (destination in listOf("panel","settings","home","lock","exit")) run("actual-Glasses-$destination-before-first-HELD-feedback-and-own-fresh-recovery") { j ->
        // Deliver original Function to Phone while withholding every Phone feedback frame.
        j.wiring.short(android.os.SystemClock.uptimeMillis())
        val raw=j.glasses.frames();j.fromGlasses.addAll(raw);raw.forEach(j.phone::receive)
        j.phone.holdFlush(j.runtime)
        check(j.input.anchor.holding&&!j.wiring.heldVisible&&j.events.map{it.phase}==listOf(Touch.Phase.DOWN))
        fun action(){when(destination){"panel"->j.wiring.showControlPanel();"settings"->j.wiring.settings();
            "home"->j.wiring.home();"lock"->j.wiring.lock();else->j.wiring.exitToLauncher()}}
        val originalRevision=j.wiring.inputGeneration
        action()
        check(j.wiring.inputGeneration==originalRevision&&!j.wiring.presentation.panelVisible&&j.wiring.shellPadContact.sleeps==0&&j.wiring.launcherExits==0)
        j.pump()
        check(j.events.map{it.phase}==listOf(Touch.Phase.DOWN,Touch.Phase.CANCEL)&&!j.wiring.heldVisible)
        check(!j.wiring.presentation.panelVisible&&j.wiring.hidPanel.visibility!=0&&j.wiring.shellPadContact.sleeps==0&&j.wiring.launcherExits==0&&j.appLaunches.isEmpty())
        action();j.pump()
        when(destination){"panel"->check(j.wiring.presentation.panelVisible);"settings"->check(j.wiring.hidPanel.visibility==0);
            "home"->check(j.appLaunches.isNotEmpty());"lock"->check(j.wiring.shellPadContact.sleeps==1);
            else->check(j.wiring.launcherExits==1&&j.wiring.finishCount==1)}
    }
    for (ending in listOf("throw", "replace", "close")) run("actual-local-control-original-Binder-$ending-retires-without-normal-fallback") { j ->
        val shell=(j.phone.get("anchorSession\$delegate") as Lazy<*>).value as AnchorShellSession
        var pending:IAnchorUserControl?=null
        val fake=object:IAnchorAppBridge.Stub(){
            override fun onIntent(transactionId:Long,functionIntent:ByteArray,original:IAnchorSession)=Unit
            override fun onRegistration(message:ByteArray,original:IAnchorSession)=Unit
            override fun onControl(original:IAnchorUserControl){
                if(ending=="throw") throw IllegalStateException("original Binder forwarding failed")
                pending=original
            }
        }
        shell.install(fake)
        val results=java.util.concurrent.CopyOnWriteArrayList<AnchorControlDisposition>()
        val done=CountDownLatch(1)
        val dispatch=shell.localControl(AnchorControlAdmission(),{true}){results.add(it);done.countDown()}
        dispatch()
        if(ending=="replace")shell.install(null)
        if(ending=="close")shell.close()
        check(done.await(2,TimeUnit.SECONDS)){"Original callback left pending after $ending"}
        pending?.apply()
        check(results==listOf(AnchorControlDisposition.RETIRED)){"Stale NORMAL/fallback: $results"}
    }
    for (destination in listOf("panel","settings","home","lock","exit")) run("actual-Glasses-$destination-forwards-before-cleanup-and-discards-with-delayed-held-label") { j ->
        j.hold();j.wiring.label.visibility=8
        val fact=j.glasses.session.observationUsability()
        val revision=j.wiring.inputGeneration
        val saved=j.runtime.store.snapshot("app")
        when(destination){
            "panel"->j.wiring.showControlPanel()
            "settings"->j.wiring.settings()
            "home"->j.wiring.home()
            "lock"->j.wiring.lock()
            else->j.wiring.exitToLauncher()
        }
        check(j.wiring.inputGeneration==revision&&j.glasses.session.observationUsability()==fact)
        j.pump()
        check(j.events.map{it.phase}==listOf(Touch.Phase.DOWN,Touch.Phase.CANCEL))
        check(!j.wiring.presentation.panelVisible&&j.wiring.launcherExits==0&&j.wiring.finishCount==0)
        check(j.wiring.shellPadContact.sleeps==0&&j.appLaunches.isEmpty()&&j.runtime.store.snapshot("app")==saved)
        check(j.fromGlasses.any{it.channel==Wire.CONTROL_INTENT})
        check(j.fromPhone.filter{it.channel==Wire.CONTROL_RESULT}.map{ReceiverControlResult.from(it.payload).disposition}==listOf(ReceiverControlDisposition.CONSUMED))
        j.wiring.showControlPanel();j.pump();check(j.wiring.presentation.panelVisible)
    }
    run("actual-Glasses-local-intent-replies-discarded-while-original-CANCEL-blocks") { j ->
        j.hold();val entered=CountDownLatch(1);val release=CountDownLatch(1)
        j.cancelBehavior={entered.countDown();check(release.await(5,TimeUnit.SECONDS));true}
        j.phone.receive(Frame(Wire.TAP,payload=Tap(20,30,Geometry(480,640)).bytes()))
        check(entered.await(2,TimeUnit.SECONDS))
        try {
            j.wiring.settings()
            val frames=j.glasses.frames();j.fromGlasses.addAll(frames);frames.forEach(j.phone::receive)
            val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2)
            var replies=listOf<Frame>()
            while(replies.isEmpty()&&System.nanoTime()<deadline){
                val input=DataInputStream(j.phone.peer.sink.toByteArray().inputStream())
                replies=buildList{while(input.available()>0){val f=Wire.readFrame(input);if(f.channel==Wire.CONTROL_RESULT)add(f)}}
                if(replies.isEmpty())Thread.yield()
            }
            check(replies.single().let{ReceiverControlResult.from(it.payload).disposition}==ReceiverControlDisposition.CONSUMED)
            replies.forEach(j.glasses::receive)
            check(!j.wiring.presentation.panelVisible&&j.appLaunches.isEmpty()&&j.wiring.shellPadContact.sleeps==0)
        }finally{release.countDown()}
        j.pump();check(!j.wiring.presentation.panelVisible)
    }
    run("actual-passive-head-scroll-does-not-cancel-or-move-held-Anchor") { j ->
        j.hold();j.phone.receive(Frame(Wire.PASSIVE_SCROLL,payload=Scroll(Tap(20,30,Geometry(480,640)),100).bytes()));j.pump()
        check(j.events.size==1&&j.input.anchor.holding&&j.ordinary.isEmpty())
    }
}
