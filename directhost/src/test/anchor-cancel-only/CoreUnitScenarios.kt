package com.code2hack.eyeprojector.directhost

import com.code2hack.eyeprojector.directhost.anchor.execution.*
import com.code2hack.eyeprojector.directhost.anchor.preferences.*
import com.code2hack.eyeprojector.directhost.anchor.target.*
import com.code2hack.eyeprojector.directhost.input.*
import com.code2hack.eyeprojector.directhost.uiaccess.*
import com.code2hack.eyeprojector.protocol.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.CopyOnWriteArrayList
import com.code2hack.eyeprojector.directrg.PadGestureRecognizer
import com.code2hack.eyeprojector.directrg.PointerPosition

private class CoreRig : AutoCloseable {
    val lock = Any()
    val size = Geometry(480,640)
    val source = ManagedUiSource(ProjectionContext("owner","original"), "display-generation",47,
        "managed","app",null,UiSourceGeometry(size,size,160,0,false,size))
    val target = ResolvedAnchorTarget(source,UiWindowMetadata(47,3,1,0,true,true,UiBounds(0,0,480,640)),
        UiTargetObservation(0,3,"app","app:id/action","Button",UiBounds(300,400,340,440),listOf(16),true,false,true,true))
    val setting = AnchorSettingContext("store","app",1)
    val fact = ReceiverUsability("owner",ReceiverIncarnation("glasses",1),1,1,ReceiverUsabilityMode.PROJECTED)
    var point = HeadPointerState(1,1,true,true,10f,20f,Fit(0,0,480,640),size)
    var time = 1000L
    var serial = 1L
    val events = CopyOnWriteArrayList<Touch>()
    val ordinary = mutableListOf<String>()
    val controls = mutableListOf<ReceiverInput>()
    val functions = mutableListOf<FunctionIntent>()
    val states = CopyOnWriteArrayList<Pair<Long,AnchorHoldState>>()
    val work = FunctionWork()
    var onTouch: (Touch)->Boolean = { true }
    class Task(val at:Long,val action:()->Unit) { var canceled=false }
    val tasks = mutableListOf<Task>()
    val input = AnchorSessionInput(lock) {}
    val merger = PhoneInputMerger(lock,{time},{at,action->
        val task=Task(at,action); tasks.add(task)
        val cancel:()->Unit={task.canceled=true}; cancel
    },{size},input::touch,input::scroll,{_,_->ordinary.add("navigation")},{input.cancel()},{controls.add(it)},
        functionIntent={functions.add(it)},anchorControl=input::consumeHeldControl,cancelPassiveControl=input::cancelOrdinary,captureHeldControl=input.anchor::captureHeldControl,
        receiveControl=input.anchor::receiveControl)
    val state = AnchorHoldOutput(work) { rev,value->
        check(!Thread.holdsLock(lock)); states.add(rev to value)
    }
    init {
        input.bindMotionTarget(object:PhoneTouchDispatcher.MotionTarget {
            override fun geometry(next:Geometry)=Unit
            override fun touch(event:Touch):Boolean {events.add(event);return onTouch(event)}
            override fun tap(event:Tap):Boolean {ordinary.add("tap");return true}
            override fun scroll(event:Scroll):Boolean {ordinary.add("scroll");return true}
        })
        input.geometry(size); input.anchor.reconcile(setting); merger.pointer(point,fact)
    }
    fun intent(kind:FunctionIntentKind=FunctionIntentKind.ACTIVATE):FunctionIntent {
        val id=serial++
        return FunctionIntent(kind,FunctionSource.PHONE_HID,id,id,FunctionIntentContext(fact,point))
    }
    fun admission()=input.anchor.capture(intent())!!
    fun hold():AnchorExecutionStatus=input.hold(admission(),setting,target,source,{true},merger::nativeAction,state)
    fun drain() {val done=CountDownLatch(1);work.submit{done.countDown()};check(done.await(2,TimeUnit.SECONDS))}
    fun down(id:Long,action:PhoneHidAction):PhoneHidEdge {
        val e=PhoneHidEdge(action,PhoneHidPhase.DOWN,7,195,700,time,time)
        merger.edge(id,e); return e
    }
    fun up(id:Long,e:PhoneHidEdge) {time+=10;merger.edge(id,e.copy(phase=PhoneHidPhase.UP,eventTimeMs=time))}
    fun advance(at:Long) {time=at;while(true){val t=tasks.firstOrNull{it.at<=at}?:return;tasks.remove(t);if(!t.canceled)t.action()}}
    override fun close(){input.close();drain();work.close()}
}

internal fun coreUnitScenarios(run:(String,()->Unit)->Unit) {
    for(mode in listOf("normal","consumed","context-retired","source-retired")) run("native-marker-async-original-decision-$mode-drains-once-and-keeps-fresh-input") {CoreRig().use {r->
        if(mode=="consumed")r.hold()
        var context="original";val pending=mutableListOf<()->Unit>();val actions=mutableListOf<PadGestureRecognizer.Action>()
        val pad=PadGestureRecognizer(300,500,{action->actions.add(action);r.merger.nativeAction{r.input.tap(Tap(10,20,r.size))}},
            admit={complete->val normal=r.input.consumeHeldControl(AnchorControlAdmission())==AnchorControlDisposition.NORMAL;pending.add{complete(normal)}},current={context})
        fun marker(at:Long){pad.accept(PadGestureRecognizer.Event(PadGestureRecognizer.Key.CONTACT,PadGestureRecognizer.Phase.DOWN,at,at,position=PointerPosition(10f,20f,true)),at);pad.accept(PadGestureRecognizer.Event(PadGestureRecognizer.Key.CONTACT,PadGestureRecognizer.Phase.UP,at+20,at),at+20);pad.confirm(at+1020)}
        marker(1000);check(pending.size==1&&actions.isEmpty())
        if(mode=="consumed")r.hold()
        val events=r.events.toList()
        if(mode=="context-retired")context="new-context"
        if(mode=="source-retired")pad.cancel(2500)
        pending[0]();pending[0]()
        check(actions.size==if(mode=="normal")1 else 0)
        if(mode!="normal")check(r.events==events&&r.ordinary.isEmpty())
        if(mode=="consumed") {check(r.input.anchor.holding);r.input.anchor.retire()}
        val baseline=actions.size
        marker(4000);check(actions.size==baseline);pending.last()();pending.last()()
        check(actions.size==baseline+1&&r.ordinary.size==if(mode=="normal")2 else 1)
    }}
    run("fresh-independent-native-Back-is-not-a-consumed-marker-summary") {CoreRig().use {r->
        r.hold();var gates=0;val actions=mutableListOf<PadGestureRecognizer.Action>()
        val pad=PadGestureRecognizer(300,500,{actions.add(it)},admit={complete->gates++;complete(r.input.consumeHeldControl(AnchorControlAdmission())==AnchorControlDisposition.NORMAL)},current={r.fact})
        fun edge(key:PadGestureRecognizer.Key,phase:PadGestureRecognizer.Phase,down:Long,at:Long){r.time=at;pad.accept(PadGestureRecognizer.Event(key,phase,at,down,position=PointerPosition(10f,20f,true)),at)}
        edge(PadGestureRecognizer.Key.CONTACT,PadGestureRecognizer.Phase.DOWN,1000,1000);edge(PadGestureRecognizer.Key.CONTACT,PadGestureRecognizer.Phase.UP,1000,1020)
        check(gates==1&&!r.input.anchor.holding);r.hold()
        edge(PadGestureRecognizer.Key.BACK,PadGestureRecognizer.Phase.DOWN,1100,1100);edge(PadGestureRecognizer.Key.BACK,PadGestureRecognizer.Phase.UP,1100,1120)
        pad.confirm(2500)
        check(gates==2&&!r.input.anchor.holding&&actions.isEmpty()) {"Fresh Back incorrectly inherited old marker control gates=$gates events=${r.events}"}
    }}
    for(tail in listOf("marker","single-summary","swipe-tail","AI-long","double-summary","back","swipe-alias","swipe-before-marker-up","AI-before-marker-up"))
        run("native-pad-original-$tail-consumed-before-classification-and-no-H2-replay") {CoreRig().use {r->
            r.hold();var gates=0;val actions=mutableListOf<PadGestureRecognizer.Action>()
            val pad=PadGestureRecognizer(300,500,{action->actions.add(action)
                r.merger.nativeAction {
                    when(action){
                        is PadGestureRecognizer.Action.Tap->r.input.tap(Tap(10,20,r.size))
                        is PadGestureRecognizer.Action.Scroll->r.input.scroll(Scroll(Tap(10,20,r.size),action.pixels))
                        else->{r.ordinary.add("native-action");true}
                    }
                }
            },admit={complete->gates++;val receipt=AnchorControlAdmission();r.input.anchor.receiveControl(receipt);complete(r.input.consumeHeldControl(receipt)==AnchorControlDisposition.NORMAL)},current={r.fact})
            fun edge(key:PadGestureRecognizer.Key,phase:PadGestureRecognizer.Phase,down:Long,at:Long){
                r.time=at;pad.accept(PadGestureRecognizer.Event(key,phase,at,down,position=PointerPosition(10f,20f,true)),at)
            }
            val key=when(tail){"double-summary"->PadGestureRecognizer.Key.DOUBLE;"back"->PadGestureRecognizer.Key.BACK;"swipe-alias"->PadGestureRecognizer.Key.FORWARD;else->PadGestureRecognizer.Key.CONTACT}
            edge(key,PadGestureRecognizer.Phase.DOWN,1000,1000)
            val firstWasConsumed=gates==1&&!r.input.anchor.holding
            // Before-source raw admission is absent. Retire H1 with a distinct real control so
            // delayed original classification still reaches an actual independent H2 witness.
            if(r.input.anchor.holding)r.input.consumeHeldControl(AnchorControlAdmission())
            r.hold();val successor=r.events.last()
            if(tail !in listOf("swipe-before-marker-up","AI-before-marker-up"))edge(key,PadGestureRecognizer.Phase.UP,1000,1020)
            when(tail){
                "single-summary"->{edge(PadGestureRecognizer.Key.TAP,PadGestureRecognizer.Phase.DOWN,1100,1100);edge(PadGestureRecognizer.Key.TAP,PadGestureRecognizer.Phase.UP,1100,1120)}
                "swipe-tail"->{edge(PadGestureRecognizer.Key.FORWARD,PadGestureRecognizer.Phase.DOWN,1100,1100);edge(PadGestureRecognizer.Key.FORWARD,PadGestureRecognizer.Phase.UP,1100,1120);edge(PadGestureRecognizer.Key.FORWARD_TAIL,PadGestureRecognizer.Phase.DOWN,1130,1130);edge(PadGestureRecognizer.Key.FORWARD_TAIL,PadGestureRecognizer.Phase.UP,1130,1140)}
                "swipe-alias"->{edge(PadGestureRecognizer.Key.FORWARD_ALIAS,PadGestureRecognizer.Phase.DOWN,1100,1100);edge(PadGestureRecognizer.Key.FORWARD_ALIAS,PadGestureRecognizer.Phase.UP,1100,1120)}
                "AI-long"->pad.classifyLongPress(1800)
                "swipe-before-marker-up"->{edge(PadGestureRecognizer.Key.FORWARD,PadGestureRecognizer.Phase.DOWN,1100,1100);edge(PadGestureRecognizer.Key.FORWARD,PadGestureRecognizer.Phase.UP,1100,1120);edge(PadGestureRecognizer.Key.CONTACT,PadGestureRecognizer.Phase.UP,1000,1150)}
                "AI-before-marker-up"->{pad.classifyLongPress(1800);edge(PadGestureRecognizer.Key.CONTACT,PadGestureRecognizer.Phase.UP,1000,1820)}
                else->Unit
            }
            pad.confirm(2500)
            check(firstWasConsumed&&gates==1&&actions.isEmpty()&&r.input.anchor.holding&&r.events.last()===successor&&r.ordinary.isEmpty()) {"old native $tail gates=$gates actions=$actions events=${r.events} ordinary=${r.ordinary}"}
            // Two distinct fresh marker controls: first cancels H2 only, then normal Tap recovers.
            edge(PadGestureRecognizer.Key.CONTACT,PadGestureRecognizer.Phase.DOWN,3000,3000);edge(PadGestureRecognizer.Key.CONTACT,PadGestureRecognizer.Phase.UP,3000,3020);pad.confirm(4020)
            check(!r.input.anchor.holding&&actions.isEmpty())
            edge(PadGestureRecognizer.Key.CONTACT,PadGestureRecognizer.Phase.DOWN,5000,5000);edge(PadGestureRecognizer.Key.CONTACT,PadGestureRecognizer.Phase.UP,5000,5020);pad.confirm(6020)
            check(actions.size==1&&r.ordinary==listOf("tap"))
        }}
    for(action in listOf(PhoneHidAction.TAP,PhoneHidAction.SCROLL_DOWN))
        for(stale in listOf(PhoneHidPhase.UP,PhoneHidPhase.CANCEL))for(consumed in listOf(false,true))
            run("C143-stale-$action-$stale-after-route-reuse-preserves-B-consumed-$consumed") {CoreRig().use {r->
                fun records(name:String)=r.merger.javaClass.getDeclaredField(name).apply{isAccessible=true}.get(r.merger) as Map<*,*>
                r.hold();val a=r.down(143,action);r.up(143,a)
                if(consumed)r.hold()
                r.time+=1000;val b=r.down(143,action)
                val receipt=checkNotNull(records("receivedPresses")[143L])
                val press=checkNotNull(records("presses")[143L])
                val control=press.javaClass.getDeclaredField("control").apply{isAccessible=true}.get(press) as AnchorControlAdmission
                val disposition=control.disposition;val events=r.events.toList()
                r.merger.edge(143,a.copy(phase=stale,eventTimeMs=b.eventTimeMs+1))
                check(records("receivedPresses")[143L]===receipt&&records("presses")[143L]===press&&control.disposition==disposition) {
                    "Stale A end removed or changed B original receipt"
                }
                check(r.events.toList()==events)
                r.up(143,b)
                check(records("receivedPresses").isEmpty()&&records("presses").isEmpty())
            }}
    for(action in listOf(PhoneHidAction.TAP,PhoneHidAction.SCROLL_DOWN))
        for(end in listOf(PhoneHidPhase.UP,PhoneHidPhase.CANCEL)) run("C134-terminal-$action-$end-disposes-exact-original-and-allows-route-reuse") {CoreRig().use {r->
            fun records(name:String)=r.merger.javaClass.getDeclaredField(name).apply{isAccessible=true}.get(r.merger) as Map<*,*>
            r.hold();val original=r.down(136,action)
            check(records("presses").size==1&&records("receivedPresses").size==1)
            r.merger.edge(136,original.copy(repeatCount=1,eventTimeMs=original.eventTimeMs+1))
            r.merger.edge(136,original.copy(phase=end,eventTimeMs=original.eventTimeMs+10))
            check(records("presses").isEmpty()&&records("receivedPresses").isEmpty())
            val before=r.events.toList()
            r.merger.edge(136,original.copy(phase=end,eventTimeMs=original.eventTimeMs+10))
            check(records("presses").isEmpty()&&records("receivedPresses").isEmpty()&&r.events.toList()==before)
            r.time+=1000;val fresh=r.down(136,action);r.up(136,fresh)
            check(records("presses").isEmpty()&&records("receivedPresses").isEmpty())
            when(action){
                PhoneHidAction.TAP->check(r.events.takeLast(2).map{it.phase}==listOf(Touch.Phase.DOWN,Touch.Phase.UP))
                else->check(r.ordinary==listOf("scroll"))
            }
        }}
    run("C134-DOWN-during-CANCELING-retains-discard-until-matching-end-without-record-leak") {CoreRig().use {r->
        val pending=mutableListOf<()->Unit>()
        r.input.cancellation({pending.add(it)},{throw it})
        r.hold();check(!r.merger.nativeAction{error("No action while held")})
        val original=r.down(136,PhoneHidAction.TAP)
        pending.single().invoke()
        r.up(136,original)
        for(name in listOf("presses","receivedPresses"))check((r.merger.javaClass.getDeclaredField(name).apply{isAccessible=true}.get(r.merger) as Map<*,*>).isEmpty())
        check(r.events.map{it.phase}==listOf(Touch.Phase.DOWN,Touch.Phase.CANCEL))
    }}
    for (h2 in listOf(false,true)) for (classification in listOf("timer","up")) {
        run("original-Function-Long-$classification-H1-canceled-H2-$h2-stays-terminal") {CoreRig().use {r->
            r.hold();val original=r.down(1,PhoneHidAction.ANCHOR)
            check(!r.merger.nativeAction {r.input.tap(Tap(20,30,r.size))})
            if(h2) check(r.hold()==AnchorExecutionStatus.HOLD_SUBMITTED)
            val before=r.events.toList()
            if(classification=="timer") r.advance(1800) else {r.time=1800;r.up(1,original)}
            r.up(1,original);r.merger.edge(1,original.copy(repeatCount=1,eventTimeMs=1900))
            check(r.functions.isEmpty()&&r.events.toList()==before&&r.input.anchor.holding==h2) {
                "Old Long changed successor or registered: functions=${r.functions}; events=${r.events}"
            }
            if(h2) r.input.anchor.retire()
            r.time=3000;val fresh=r.down(2,PhoneHidAction.ANCHOR);r.advance(3800);r.up(2,fresh)
            check(r.functions.single().kind==FunctionIntentKind.REGISTER)
        }}
    }
    for(failure in listOf("reject","throw","reenter")) {
        run("provisional-persistent-Hold-DOWN-$failure-never-fabricates-HELD") {CoreRig().use {r->
            val observations=mutableListOf<AnchorControlDisposition>()
            r.onTouch={e->if(e.phase==Touch.Phase.DOWN) {
                observations.add(r.input.consumeHeldControl(AnchorControlAdmission()))
                r.onTouch={true}
                when(failure) {
                    "throw"->error("provisional DOWN failure")
                    "reject"->false
                    else->{r.input.anchor.retire();check(r.hold()==AnchorExecutionStatus.HOLD_SUBMITTED);true}
                }
            }else true}
            val result=runCatching{r.hold()};r.drain()
            check(observations==listOf(AnchorControlDisposition.NORMAL))
            if(failure=="throw") check(result.isFailure)
            else check(result.getOrThrow()==if(failure=="reject") AnchorExecutionStatus.INPUT_REJECTED else AnchorExecutionStatus.RETIRED)
            check(r.events.take(2).map{it.phase}==listOf(Touch.Phase.DOWN,Touch.Phase.CANCEL))
            check(r.states.count{it.second==AnchorHoldState.HELD}==if(failure=="reenter") 1 else 0)
            check(r.input.anchor.holding==(failure=="reenter"))
            if(failure=="reenter") check(r.events.map{it.phase}==listOf(Touch.Phase.DOWN,Touch.Phase.CANCEL,Touch.Phase.DOWN))
        }}
    }
    run("accessor-membership-is-pure-through-first-up-second-and-completion") {
        var now=1000L;var reads=0;var schedules=0;var cancels=0;val delivered=mutableListOf<String>()
        val grammar=HidNavigationGestureRecognizer<String>({now},{reads++;true},{_,_->schedules++;{cancels++}},
            {action,captured->delivered.add("$action:$captured")})
        check(grammar.capturedSequence("a")==null)
        grammar.down("a","source","original");val before=listOf(reads,schedules,cancels)
        repeat(3){check(grammar.capturedSequence("a")=="original");check(grammar.capturedSequence("unknown")==null)}
        check(before==listOf(reads,schedules,cancels)&&delivered.isEmpty())
        grammar.up("a",1010);check(grammar.capturedSequence("a")=="original")
        now=1100;grammar.down("b","source","second")
        check(grammar.capturedSequence("a")=="original"&&grammar.capturedSequence("b")=="original")
        grammar.up("b",1110);check(delivered.isEmpty())
        now=1200;grammar.down("c","source","third")
        check(grammar.capturedSequence("b")=="original")
        grammar.up("c",1210);check(grammar.capturedSequence("a")==null&&grammar.capturedSequence("b")==null)
        check(delivered==listOf("HOME:original"))
    }
    run("accessor-expired-dispatch-cannot-attach-old-press-to-reentrant-successor") {
        var now=1000L
        lateinit var grammar:HidNavigationGestureRecognizer<String>
        grammar=HidNavigationGestureRecognizer({now},{true},{_,_->{}},{_,_->grammar.down("successor","source","new")})
        grammar.down("old","source","original");grammar.up("old",1010)
        now=2000;grammar.down("requested","source","requested capture")
        check(grammar.capturedSequence("requested")==null&&grammar.capturedSequence("old")==null)
        check(grammar.capturedSequence("successor")=="new")
    }
    for (action in listOf(PhoneHidAction.TAP,PhoneHidAction.SCROLL_UP,PhoneHidAction.SCROLL_DOWN)) {
        run("held-HID-$action-whole-press-preserves-successor-and-fresh-control-recovers") {CoreRig().use {r->
            check(r.hold()==AnchorExecutionStatus.HOLD_SUBMITTED)
            val e=r.down(1,action);check(r.events.map{it.phase}==listOf(Touch.Phase.DOWN,Touch.Phase.CANCEL))
            check(r.hold()==AnchorExecutionStatus.HOLD_SUBMITTED);val successor=r.events.last()
            r.merger.edge(1,e.copy(repeatCount=1,eventTimeMs=e.eventTimeMs+1));r.up(1,e)
            check(r.input.anchor.holding&&r.events.last()===successor&&r.ordinary.isEmpty()&&r.controls.isEmpty())
            r.time+=1000;val cancelNext=r.down(2,action);r.up(2,cancelNext)
            check(!r.input.anchor.holding&&r.ordinary.isEmpty()&&r.controls.isEmpty())
            r.time+=1000;val fresh=r.down(3,action);r.up(3,fresh)
            when(action){
                PhoneHidAction.TAP->check(r.events.takeLast(2).map{it.phase}==listOf(Touch.Phase.DOWN,Touch.Phase.UP))
                else->check(r.ordinary==listOf("scroll"))
            }
        }}
    }
    run("held-native-touch-consumes-move-up-and-repeat-without-canceling-H2") {CoreRig().use {r->
        r.hold();val down=Touch(Touch.Phase.DOWN,99,Tap(20,30,r.size))
        check(!r.merger.nativeTouch(down));r.hold();val successor=r.events.last()
        check(!r.merger.nativeTouch(down));check(!r.merger.nativeTouch(down.copy(phase=Touch.Phase.MOVE)))
        check(!r.merger.nativeTouch(down.copy(phase=Touch.Phase.UP)))
        check(r.input.anchor.holding&&r.events.last()===successor)
        check(!r.merger.nativeTouch(down.copy(contactId=100)))
        check(!r.input.anchor.holding)
        check(r.merger.nativeTouch(down.copy(contactId=101)))
        check(r.merger.nativeTouch(down.copy(contactId=101,phase=Touch.Phase.UP)))
    }}
    run("held-backhome-double-consumes-both-presses-and-fresh-sequence-is-normal") {CoreRig().use {r->
        r.hold();val first=r.down(1,PhoneHidAction.NAVIGATION);r.up(1,first)
        r.time+=100;val second=r.down(2,PhoneHidAction.NAVIGATION);r.up(2,second);r.advance(2000)
        check(r.events.map{it.phase}==listOf(Touch.Phase.DOWN,Touch.Phase.CANCEL))
        check(r.ordinary.isEmpty()&&r.controls.isEmpty())
        val fresh=r.down(3,PhoneHidAction.NAVIGATION);r.up(3,fresh);r.advance(2600)
        check(r.ordinary==listOf("navigation"))
    }}
    for(releaseDuring in listOf(false,true)) run("BackHome-first-received-CANCELING-second-after-finish-is-same-consumed-sequence-$releaseDuring") {CoreRig().use {r->
        r.hold()
        val entered=CountDownLatch(1);val finish=CountDownLatch(1)
        r.onTouch={event->if(event.phase==Touch.Phase.CANCEL){entered.countDown();check(finish.await(3,TimeUnit.SECONDS))};true}
        val cancel=Thread{r.input.consumeHeldControl(AnchorControlAdmission())};cancel.start()
        lateinit var first:PhoneHidEdge
        try {check(entered.await(2,TimeUnit.SECONDS));first=r.down(801,PhoneHidAction.NAVIGATION);if(releaseDuring)r.up(801,first)}
        finally {finish.countDown();cancel.join(2000)}
        check(!cancel.isAlive)
        r.onTouch={true};if(!releaseDuring)r.up(801,first)
        r.hold();val successor=r.events.last();r.time+=100
        val second=r.down(802,PhoneHidAction.NAVIGATION);r.up(802,second);r.advance(2000)
        check(r.input.anchor.holding&&r.events.last()===successor&&r.ordinary.isEmpty()) {"CANCELING BackHome tail canceled H2 or navigated: ${r.events} ${r.ordinary}"}
        r.time=3000;r.input.anchor.retire();val fresh=r.down(803,PhoneHidAction.NAVIGATION);r.up(803,fresh);r.advance(4000)
        check(r.ordinary==listOf("navigation"))
    }}
    run("consumed-backhome-second-press-cannot-cancel-successor-Hold") {CoreRig().use {r->
        r.hold();val first=r.down(1,PhoneHidAction.NAVIGATION);r.up(1,first)
        r.hold();val successor=r.events.last();r.time+=100
        val second=r.down(2,PhoneHidAction.NAVIGATION);r.up(2,second);r.advance(2000)
        check(r.input.anchor.holding&&r.events.last()===successor&&r.ordinary.isEmpty())
    }}
    run("passive-head-loss-preserves-stationary-Hold-and-context-qualified-Tap-cancels-only") {CoreRig().use {r->
        r.hold();r.point=r.point.copy(sequence=2,available=false);r.merger.pointer(r.point,r.fact)
        check(r.input.anchor.holding&&r.events.size==1)
        val e=PhoneHidEdge(PhoneHidAction.TAP,PhoneHidPhase.DOWN,7,195,700,1000,1000)
        r.merger.stampedEdge(1,e,{_,_->true},{_,_->false});r.merger.edge(1,e.copy(phase=PhoneHidPhase.UP,eventTimeMs=1010))
        check(r.events.map{it.phase}==listOf(Touch.Phase.DOWN,Touch.Phase.CANCEL)&&r.ordinary.isEmpty())
    }}
    for (failure in listOf("reject","throw","accept")) {
        run("Owner119-consumed-before-$failure-CANCEL-discards-reentrant-activation") {CoreRig().use {r->
            r.hold();val admission=AnchorControlAdmission()
            r.onTouch={event->
                if(event.phase==Touch.Phase.CANCEL){
                    check(admission.disposition==AnchorControlDisposition.CONSUMED)
                    check(r.input.anchor.capture(r.intent())==null)
                    check(r.input.consumeHeldControl(AnchorControlAdmission())==AnchorControlDisposition.CONSUMED)
                    when(failure){"throw"->error("original CANCEL failure");"reject"->false;else->true}
                }else true
            }
            val result=runCatching{r.input.consumeHeldControl(admission)}
            check(result.isFailure==(failure!="accept"))
            check(r.events.map{it.phase}==listOf(Touch.Phase.DOWN,Touch.Phase.CANCEL))
            check(r.input.consumeHeldControl(admission)==AnchorControlDisposition.CONSUMED&&r.events.size==2)
            r.drain();check(r.states.count{it.second==AnchorHoldState.HELD}==1)
            if(failure=="accept") {r.onTouch={true};check(r.hold()==AnchorExecutionStatus.HOLD_SUBMITTED)}
            else check(r.input.anchor.capture(r.intent())==null) // Failed retirement is never normal-ready.
        }}
    }
    run("blocked-CANCEL-is-consumed-before-output-and-no-normal-fallback") {CoreRig().use {r->
        r.hold();val entered=CountDownLatch(1);val release=CountDownLatch(1);val admission=AnchorControlAdmission()
        r.onTouch={event->if(event.phase==Touch.Phase.CANCEL){entered.countDown();check(release.await(2,TimeUnit.SECONDS))};true}
        var result:AnchorControlDisposition?=null
        val thread=Thread{result=r.input.consumeHeldControl(admission)};thread.start()
        try{check(entered.await(2,TimeUnit.SECONDS));check(admission.disposition==AnchorControlDisposition.CONSUMED)}
        finally{release.countDown();thread.join(2000)}
        check(!thread.isAlive&&result==AnchorControlDisposition.CONSUMED&&!r.input.anchor.holding)
        check(r.events.map{it.phase}==listOf(Touch.Phase.DOWN,Touch.Phase.CANCEL))
        r.onTouch={true};r.hold();check(r.input.consumeHeldControl(admission)==AnchorControlDisposition.CONSUMED&&r.events.size==3)
    }}
    run("normal-admission-cannot-be-refreshed-after-successor-Hold") {CoreRig().use {r->
        val control=AnchorControlAdmission();check(r.input.consumeHeldControl(control)==AnchorControlDisposition.NORMAL)
        r.hold();val original=r.events.last()
        check(!r.input.anchor.reconcile(r.setting.copy(revision=2),control))
        check(r.input.consumeHeldControl(control)==AnchorControlDisposition.RETIRED)
        check(r.input.anchor.holding&&r.events.last()===original)
    }}
    run("admitted-original-release-is-terminal-after-H1-UP-and-H2-same-setting") {CoreRig().use {r->
        r.hold();val old=r.admission();val setting=r.input.anchor.heldSetting(old)!!
        val other=r.admission();check(r.input.anchor.releaseHeld(other,setting,true,r.source){true}==AnchorReleaseResult.RELEASE_SUBMITTED)
        r.hold();val successor=r.events.last()
        check(r.input.anchor.releaseHeld(old,setting,true,r.source){true}==AnchorReleaseResult.RETIRED)
        r.input.anchor.retire(old)
        check(r.input.anchor.holding&&r.events.last()===successor)
    }}
    run("admitted-release-missing-contact-cannot-fall-through-to-DOWN") {CoreRig().use {r->
        r.hold();val old=r.admission();val setting=r.input.anchor.heldSetting(old)!!;r.input.anchor.retire()
        check(r.input.anchor.releaseHeld(old,setting,true,r.source){true}==AnchorReleaseResult.RETIRED)
        check(r.input.hold(old,r.setting,r.target,r.source,{true},r.merger::nativeAction,r.state)==AnchorExecutionStatus.RETIRED)
        check(r.events.map{it.phase}==listOf(Touch.Phase.DOWN,Touch.Phase.CANCEL))
    }}
    run("Click-DOWN-is-not-persistent-Hold") {CoreRig().use {r->
        val controls=mutableListOf<AnchorControlDisposition>()
        r.onTouch={e->if(e.phase==Touch.Phase.DOWN)controls.add(r.input.consumeHeldControl(AnchorControlAdmission()));true}
        val result=r.input.click(r.admission(),r.setting,r.target,r.source,{true},r.merger::nativeAction)
        check(result==AnchorExecutionStatus.CLICK_SUBMITTED&&controls==listOf(AnchorControlDisposition.NORMAL))
        check(r.events.map{it.phase}==listOf(Touch.Phase.DOWN,Touch.Phase.UP))
    }}
}
