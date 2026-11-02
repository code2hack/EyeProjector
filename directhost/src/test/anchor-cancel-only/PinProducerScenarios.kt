package com.code2hack.eyeprojector.directhost

import com.code2hack.eyeprojector.directhost.hid.*
import com.code2hack.eyeprojector.directhost.activation.*
import com.code2hack.eyeprojector.directhost.input.PhoneHidRoute
import com.code2hack.eyeprojector.directhost.input.PhoneHidOriginal
import com.code2hack.eyeprojector.protocol.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicLong
import org.json.*

/** Full actual native listener/connect/queue/controller/route -> real original Phone Binder/merger.
 * Android service transport and JSON registry are named boundaries; publication uses actual region/word. */
internal fun pinProducerScenarios(run:(String,(Joined)->Unit)->Unit) {
    val modes=if(System.getenv("EYEPROJECTOR_RELEASE_ORDER_ONLY")=="1")listOf("held-menu-delayed-Click","held-menu-delayed-Hold")else if(System.getenv("EYEPROJECTOR_C206_NATIVE_ONLY")=="1")listOf("first-observation")else listOf("H1-Long-H2","first-observation","canceling-first-result","empty-Long-H2","normal-Short","normal-Long","held-menu-Click","held-menu-Hold","held-menu-Cancel","held-menu-delayed-Click","held-menu-delayed-Hold","held-menu-physical-Cancel","two-Shorts","learning-H1-H2","learning-UP-H2","learning-persist-H2","learning-normal","binding-retired","state-before-DOWN","routed-binding-retired","routed-CANCELING-UP","routed-CANCELING-CANCEL","routed-CANCELING-REPEAT")
    for(queue in listOf("lane","main")) for(mode in modes)
        run("R187-3-actual-native-$queue-$mode") { j ->
        var listener:IHidCaptureListener?=null
        val state=JSONObject().put("generation",1).put("deviceKey","pin").put("intercepted",true).put("phase","ACTIVE").retain("state")
        val inventory=JSONObject().put("devices",JSONArray()).retain("inventory")
        val remote=object:IHidCapture.Stub(){override fun attach(l:IHidCaptureListener){listener=l};override fun state()=state;override fun inventory()=inventory;override fun pin(key:String)=state;override fun unpin()=state}
        rikka.shizuku.Shizuku.capture=remote
        val binding=PhoneHidBinding(PhoneHidButton(PhoneHidDeviceIdentity(PhoneHidPinRuntime.NATIVE_PREFIX+JSONArray().put("pin").put("keys").toString(),2,3),195,0),"Pin","Function")
        var writes=0
        val bindings=PhoneHidBindings({null},{writes++;true});check(bindings.assign(PhoneHidAction.ANCHOR,binding))
        val usability=j.phone.get("receiverUsability") as SessionUsability
        usability.prepareRegion();val snapshot=usability.snapshot();check(snapshot.regionId!=null)
        val ids=AtomicLong(700)
        val routes=object:PhoneHidRoutes {
            override fun capture(down:PhoneHidEdge):PhoneHidRoute?=error("native path lost original")
            override fun capture(down:PhoneHidEdge,admission:PhoneHidCapture,original:PhoneHidOriginal?):PhoneHidRoute? =
                checkNotNull(original).route(down,checkNotNull(snapshot.regionId),snapshot.word,1,ids.incrementAndGet())
            override fun submit(route:PhoneHidRoute,edge:PhoneHidEdge)=route.submit(edge)
            override fun cancel(route:PhoneHidRoute,eventTimeMs:Long)=route.cancel(eventTimeMs)
        }
        val pendingPersist=mutableListOf<Pair<()->Unit,()->Unit>>()
        val controller=PhoneHidController(bindings,routes,{operation,complete->
            if(mode=="learning-persist-H2")pendingPersist.add(operation to complete)else {operation();complete()}
        },{android.os.SystemClock.uptimeMillis()},
            userControl={j.runtime.userControl()})
        controller.connection(true)
        if(mode.startsWith("learning")){controller.openPage();controller.select(PhoneHidAction.ANCHOR)}
        val firstObserved=CountDownLatch(1);val finishObservation=CountDownLatch(1)
        val firstPhase=java.util.concurrent.atomic.AtomicReference<String>()
        fun observeFirst(phase:String) {
            if(mode in listOf("first-observation","canceling-first-result")&&firstPhase.compareAndSet(null,phase)) {
                firstObserved.countDown();check(finishObservation.await(4,TimeUnit.SECONDS))
            }
        }
        val pin=PhoneHidPinRuntime(j.runtime.appContext,controller,{}, {}, {j.runtime.userControl()},
            {val normal=j.runtime.userControl(recognitionOnly=true);observeFirst("recognition-result");normal},
            {val original=j.runtime.capturePhoneHidOriginal();observeFirst("original-result");original})
        fun field(name:String)=pin.javaClass.getDeclaredField(name).apply{isAccessible=true}
        field("desiredKey").set(pin,"pin");field("ownerActive").set(pin,true)
        val lane=field("lane").get(pin) as ExecutorService
        fun flush(){val done=CountDownLatch(1);lane.execute{done.countDown()};check(done.await(3,TimeUnit.SECONDS))}
        val main=LinkedBlockingQueue<Runnable>()
        fun drain(){flush();while(true)(main.poll()?:break).run()}
        try {
            pin.javaClass.getDeclaredMethod("connect").apply{isAccessible=true}.invoke(pin);flush();flush();check(listener!=null)
            field("main").set(pin,android.os.Handler(android.os.Looper(main)))
            if(mode.startsWith("routed-CANCELING-")) {
                val merger=j.phone.get("phoneInput")!!
                fun rootCount(name:String)=(merger.javaClass.getDeclaredField(name).apply{isAccessible=true}.get(merger) as Map<*,*>).size
                fun appCount()=(controller.javaClass.getDeclaredField("presses").apply{isAccessible=true}.get(controller) as Map<*,*>).size
                fun nativeCount()=(field("originals").get(pin) as Map<*,*>).size
                repeat(3) { cycle ->
                    val before=j.events.size
                    j.press();check(j.input.anchor.holding)
                    val down=android.os.SystemClock.uptimeMillis();val press=100L+cycle
                    fun raw(phase:String,at:Long=down,time:Long=at,physical:Long=press,repeats:Int=0)=JSONObject()
                        .put("deviceKey","pin").put("generation",1).put("sourceId",3).put("collectionKey","keys")
                        .put("vendorId",2).put("productId",3).put("code",195).put("deviceName","Pin").put("label","Function")
                        .put("phase",phase).put("downTimeMs",at).put("eventTimeMs",time).put("repeatCount",repeats).put("pressId",physical)
                        .retain("r215-$queue-$mode-$cycle-$phase-$physical-$repeats")
                    listener!!.onInput(raw("DOWN"));drain()
                    check(rootCount("receivedPresses")==1&&rootCount("presses")==1&&appCount()==1&&nativeCount()==1)
                    val canceled=CountDownLatch(1);val finish=CountDownLatch(1)
                    j.cancelBehavior={canceled.countDown();check(finish.await(4,TimeUnit.SECONDS));true}
                    j.phone.receive(Frame(Wire.TAP,payload=Tap(20,30,Geometry(480,640)).bytes()))
                    try {
                        check(canceled.await(2,TimeUnit.SECONDS))
                        when(mode.substringAfterLast('-')) {
                            "REPEAT"->{listener!!.onInput(raw("DOWN",time=down+10,repeats=1));drain();listener!!.onInput(raw("UP",time=down+100))}
                            "CANCEL"->listener!!.onInput(raw("CANCEL",time=down+100))
                            else->listener!!.onInput(raw("UP",time=down+100))
                        }
                        drain()
                        check(j.input.anchor.discardingControls&&finish.count==1L)
                        check(rootCount("receivedPresses")==0&&rootCount("presses")==0&&appCount()==0&&nativeCount()==0) {
                            "discarded routed original leaked before CANCEL completion: received=${rootCount("receivedPresses")} presses=${rootCount("presses")} app=${appCount()} native=${nativeCount()}"
                        }
                        check(j.events.drop(before).map{it.phase}==listOf(Touch.Phase.DOWN,Touch.Phase.CANCEL))
                    }finally{finish.countDown();j.pump()}
                    check(!j.input.anchor.holding)
                    // Admit B and deliver A's stale ending while B still owns all three records.
                    val fresh=android.os.SystemClock.uptimeMillis();val next=press+10
                    listener!!.onInput(raw("DOWN",at=fresh,physical=next));drain()
                    check(rootCount("receivedPresses")==1&&rootCount("presses")==1&&appCount()==1&&nativeCount()==1)
                    listener!!.onInput(raw("UP",time=down+200));drain()
                    check(rootCount("receivedPresses")==1&&rootCount("presses")==1&&appCount()==1&&nativeCount()==1)
                    listener!!.onInput(raw("UP",at=fresh,time=fresh+100,physical=next));drain();j.pump()
                    check(j.input.anchor.holding&&rootCount("receivedPresses")==0&&rootCount("presses")==0&&appCount()==0&&nativeCount()==0)
                    check(j.events.drop(before).map{it.phase}==listOf(Touch.Phase.DOWN,Touch.Phase.CANCEL,Touch.Phase.DOWN))
                    j.press();check(j.events.drop(before).map{it.phase}==listOf(Touch.Phase.DOWN,Touch.Phase.CANCEL,Touch.Phase.DOWN,Touch.Phase.UP))
                }
            } else {
            if(mode.startsWith("H1")||mode in listOf("first-observation","canceling-first-result")||mode=="learning-H1-H2"||mode=="binding-retired")j.hold()
            val entered=CountDownLatch(1);val release=CountDownLatch(1)
            if(queue=="lane"){lane.execute{entered.countDown();check(release.await(4,TimeUnit.SECONDS))};check(entered.await(2,TimeUnit.SECONDS))}
            if(mode=="normal-Long"||mode.startsWith("held-menu")){j.wiring.move(320f,420f);j.pump()}
            var inputGeneration=1
            val at=android.os.SystemClock.uptimeMillis()
            fun event(phase:String,down:Long=at,time:Long=down,press:Long=81)=JSONObject().put("deviceKey","pin").put("generation",inputGeneration).put("sourceId",3).put("collectionKey","keys").put("vendorId",2).put("productId",3).put("code",195).put("deviceName","Pin").put("label","Function").put("phase",phase).put("downTimeMs",down).put("eventTimeMs",time).put("repeatCount",0).put("pressId",press).retain("event-$queue-$mode-$phase-$press")
            val saved=writes
            try {
                if(mode=="state-before-DOWN"){
                    inputGeneration=2
                    listener!!.onState(JSONObject().put("generation",2).put("deviceKey","pin").put("intercepted",true).put("phase","ACTIVE").retain("state-2"))
                }
                if(mode in listOf("first-observation","canceling-first-result")) {
                    val cancelEntered=CountDownLatch(1);val finishCancel=CountDownLatch(1)
                    if(mode=="canceling-first-result") {
                        j.cancelBehavior={cancelEntered.countDown();check(finishCancel.await(4,TimeUnit.SECONDS));true}
                        j.phone.receive(Frame(Wire.TAP,payload=Tap(20,30,Geometry(480,640)).bytes()))
                        check(cancelEntered.await(2,TimeUnit.SECONDS))
                    }
                    val error=java.util.concurrent.atomic.AtomicReference<Throwable>()
                    val ingress=Thread{try{listener!!.onInput(event("DOWN"))}catch(t:Throwable){error.set(t)}}
                    ingress.start()
                    try {
                        check(firstObserved.await(2,TimeUnit.SECONDS))
                        if(mode=="canceling-first-result"){finishCancel.countDown();j.pump();j.press()}
                        else {j.press();j.press()}
                    }finally {finishCancel.countDown();finishObservation.countDown();ingress.join(3000)}
                    check(!ingress.isAlive&&error.get()==null)
                    println("PHASE native first authoritative ${firstPhase.get()} retained before independent H1-UP/H2-DOWN")
                }else listener!!.onInput(event("DOWN"))
                if(queue=="main")flush()
                when(mode){
                    "H1-Long-H2","learning-H1-H2"->{j.press();j.press()}
                    "empty-Long-H2"->j.hold()
                    "binding-retired"->{j.phone.holdInstall(j.runtime.bind());j.pump();j.press()}
                    "normal-Short"->listener!!.onInput(event("UP",time=at+100))
                    "two-Shorts"->{listener!!.onInput(event("UP",time=at+100));listener!!.onInput(event("DOWN",down=at+200,press=82));listener!!.onInput(event("UP",down=at+200,time=at+300,press=82))}
                }
            }finally{release.countDown()}
            drain()
            if(mode in listOf("learning-UP-H2","learning-persist-H2","learning-normal")) {
                check(controller.capturing)
                if(mode=="learning-UP-H2")j.hold()
                listener!!.onInput(event("UP",time=at+100));drain()
                if(mode=="learning-persist-H2") {
                    check(pendingPersist.size==1&&controller.saving==1);j.hold()
                    pendingPersist.single().let{it.first();it.second()}
                }
            }
            if(mode=="state-before-DOWN") {
                check((field("originals").get(pin) as Map<*,*>).size==1){"queued state erased successor DOWN original"}
                listener!!.onInput(event("UP",time=at+100));drain()
            }
            if(mode.startsWith("held-menu")) {
                val until=System.nanoTime()+TimeUnit.SECONDS.toNanos(3)
                while(!j.wiring.active()&&System.nanoTime()<until){j.pump(holdReverse=mode.contains("delayed"));Thread.yield()}
                check(j.wiring.active()&&j.messages.any{it is AnchorMessage.Offer}) {"Held original did not show menu before UP"}
                val choice=when(mode.substringAfterLast('-')){"Click"->com.code2hack.eyeprojector.directrg.AnchorRegistrationAction.CLICK
                    "Hold"->com.code2hack.eyeprojector.directrg.AnchorRegistrationAction.HOLD
                    else->com.code2hack.eyeprojector.directrg.AnchorRegistrationAction.CANCEL}
                j.wiring.highlight(choice)
                if(mode.contains("delayed")) {
                    val delegate=j.phone.get("anchorSession$"+"delegate") as Lazy<*>
                    val shell=delegate.value!!
                    val op=(shell.javaClass.getDeclaredField("registration").apply{isAccessible=true}.get(shell) as java.util.concurrent.atomic.AtomicReference<*>).get()!!
                    check(!op.javaClass.getDeclaredField("chooserObserved").apply{isAccessible=true}.getBoolean(op))
                    check(j.wiring.anchorRegistration.offer!!.ready)
                    println("PHASE visible highlighted original menu; reverse OPEN/usability held before Phone; physical UP follows")
                }
                val before=j.runtime.store.snapshot("app")
                listener!!.onInput(event(if(mode.contains("physical"))"CANCEL" else "UP",time=at+900));drain()
                if(mode.contains("delayed")) {
                    j.pump(holdReverse=true)
                    val terminal=j.messages.filterIsInstance<AnchorMessage.Release>().last()
                    check(!terminal.canceled) {"Valid original physical UP changed to CANCEL solely because reverse chooser fact was in flight"}
                    check(j.wiring.active()&&j.wiring.anchorRegistration.offer!!.choosing)
                    check(j.runtime.store.snapshot("app")==before)
                }
                j.pump()
                check(!j.wiring.active()&&j.events.isEmpty()&&j.ordinary.isEmpty()) {"Registration activated target or failed to finish"}
                val after=j.runtime.store.snapshot("app")
                if(choice==com.code2hack.eyeprojector.directrg.AnchorRegistrationAction.CANCEL)check(before==after)
                else check(after.anchor?.mode==if(choice==com.code2hack.eyeprojector.directrg.AnchorRegistrationAction.CLICK)
                    com.code2hack.eyeprojector.directhost.anchor.preferences.AnchorMode.CLICK else com.code2hack.eyeprojector.directhost.anchor.preferences.AnchorMode.HOLD)
            } else if(mode=="routed-binding-retired"){
                val merger=j.phone.get("phoneInput")!!
                fun count()=(merger.javaClass.getDeclaredField("receivedPresses").apply{isAccessible=true}.get(merger) as Map<*,*>).size
                check(count()==1)
                j.phone.holdInstall(j.runtime.bind());j.pump();j.press()
                listener!!.onInput(event("UP",time=at+100));drain();j.pump()
                check(count()==0){"retired native original lost terminal metadata disposal"}
            }else if(mode !in listOf("normal-Short","two-Shorts","state-before-DOWN","learning-UP-H2","learning-persist-H2","learning-normal")){listener!!.onInput(event("UP",time=at+900));drain()}
            j.pump()
            when(mode){
                "normal-Long"->check(!j.wiring.active()&&!j.input.anchor.holding&&j.events.isEmpty()&&j.messages.any{it is AnchorMessage.Offer}) {"Already-released Long must cancel before a ready menu"}
                "held-menu-Click","held-menu-Hold","held-menu-Cancel","held-menu-delayed-Click","held-menu-delayed-Hold","held-menu-physical-Cancel"->Unit
                "normal-Short","state-before-DOWN"->{check(j.input.anchor.holding&&j.events.map{it.phase}==listOf(Touch.Phase.DOWN));j.press();check(j.events.last().phase==Touch.Phase.UP)}
                "two-Shorts"->check(!j.input.anchor.holding&&j.events.map{it.phase}==listOf(Touch.Phase.DOWN,Touch.Phase.UP)){"raw distinct Shorts lost FIFO toggle: ${j.events}"}
                "empty-Long-H2"->check(j.input.anchor.holding&&j.events.map{it.phase}==listOf(Touch.Phase.DOWN)){"empty-born Long adopted H2: ${j.events}"}
                "canceling-first-result"->check(j.input.anchor.holding&&j.events.map{it.phase}==listOf(Touch.Phase.DOWN,Touch.Phase.CANCEL,Touch.Phase.DOWN)){"CANCELING result refreshed after retirement: ${j.events}"}
                "learning-normal"->check(writes==saved+1&&!controller.capturing&&controller.saving==0&&!j.input.anchor.holding)
                "learning-UP-H2","learning-persist-H2"->check(j.input.anchor.holding&&writes==saved&&!controller.capturing&&controller.saving==0&&j.events.map{it.phase}==listOf(Touch.Phase.DOWN)){"old learning command saved or canceled successor: ${j.events}"}
                "routed-binding-retired"->check(j.input.anchor.holding&&j.events.map{it.phase}==listOf(Touch.Phase.DOWN)){"old routed ending killed H2: ${j.events}"}
                "binding-retired"->check(j.input.anchor.holding&&j.events.map{it.phase}==listOf(Touch.Phase.DOWN,Touch.Phase.CANCEL,Touch.Phase.DOWN)){"old binding adopted new context: ${j.events}"}
                else->check(j.input.anchor.holding&&j.events.map{it.phase}==listOf(Touch.Phase.DOWN,Touch.Phase.UP,Touch.Phase.DOWN)){"native original adopted H2 after $queue/$mode: ${j.events}"}
            }
            if(mode=="learning-H1-H2")check(writes==saved&&!controller.capturing)
            check((field("originals").get(pin) as Map<*,*>).isEmpty()){"raw metadata survived matching original end"}
            }
        }finally{lane.shutdownNow();check(lane.awaitTermination(3,TimeUnit.SECONDS));rikka.shizuku.Shizuku.capture=null}
    }
}
