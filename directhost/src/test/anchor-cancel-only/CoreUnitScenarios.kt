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
        functionIntent={functions.add(it)},anchorControl=input::consumeHeldControl,cancelPassiveControl=input::cancelOrdinary)
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
    run("accessor-membership-is-pure-through-first-up-second-and-completion") {
        var now=1000L;var reads=0;var schedules=0;var cancels=0;val delivered=mutableListOf<String>()
        val grammar=BackHomeGestureRecognizer<String>({now},{reads++;true},{_,_->schedules++;{cancels++}},
            {action,captured->delivered.add("$action:$captured")})
        check(grammar.capturedSequence("a")==null)
        grammar.down("a","source","original");val before=listOf(reads,schedules,cancels)
        repeat(3){check(grammar.capturedSequence("a")=="original");check(grammar.capturedSequence("unknown")==null)}
        check(before==listOf(reads,schedules,cancels)&&delivered.isEmpty())
        grammar.up("a",1010);check(grammar.capturedSequence("a")=="original")
        now=1100;grammar.down("b","source","second")
        check(grammar.capturedSequence("a")=="original"&&grammar.capturedSequence("b")=="original")
        grammar.up("b",1110);check(grammar.capturedSequence("a")==null&&grammar.capturedSequence("b")==null)
        check(delivered==listOf("HOME:original"))
    }
    run("accessor-expired-dispatch-cannot-attach-old-press-to-reentrant-successor") {
        var now=1000L
        lateinit var grammar:BackHomeGestureRecognizer<String>
        grammar=BackHomeGestureRecognizer({now},{true},{_,_->{}},{_,_->grammar.down("successor","source","new")})
        grammar.down("old","source","original");grammar.up("old",1010)
        now=2000;grammar.down("requested","source","requested capture")
        check(grammar.capturedSequence("requested")==null&&grammar.capturedSequence("old")==null)
        check(grammar.capturedSequence("successor")=="new")
    }
    for (action in listOf(PhoneHidAction.TAP,PhoneHidAction.SCROLL_UP,PhoneHidAction.SCROLL_DOWN,PhoneHidAction.LOCK)) {
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
                PhoneHidAction.LOCK->check(r.controls.single().kind==ReceiverInputKind.LOCK)
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
        r.hold();val first=r.down(1,PhoneHidAction.BACK_HOME);r.up(1,first)
        r.time+=100;val second=r.down(2,PhoneHidAction.BACK_HOME);r.up(2,second);r.advance(2000)
        check(r.events.map{it.phase}==listOf(Touch.Phase.DOWN,Touch.Phase.CANCEL))
        check(r.ordinary.isEmpty()&&r.controls.isEmpty())
        val fresh=r.down(3,PhoneHidAction.BACK_HOME);r.up(3,fresh);r.advance(2600)
        check(r.ordinary==listOf("navigation"))
    }}
    run("consumed-backhome-second-press-cannot-cancel-successor-Hold") {CoreRig().use {r->
        r.hold();val first=r.down(1,PhoneHidAction.BACK_HOME);r.up(1,first)
        r.hold();val successor=r.events.last();r.time+=100
        val second=r.down(2,PhoneHidAction.BACK_HOME);r.up(2,second);r.advance(2000)
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
        run("consumed-before-$failure-CANCEL-reentrancy-does-not-touch-H2") {CoreRig().use {r->
            r.hold();val admission=AnchorControlAdmission()
            r.onTouch={event->
                if(event.phase==Touch.Phase.CANCEL){
                    check(admission.disposition==AnchorControlDisposition.CONSUMED)
                    r.onTouch={true};check(r.hold()==AnchorExecutionStatus.HOLD_SUBMITTED)
                    check(r.input.consumeHeldControl(admission)==AnchorControlDisposition.CONSUMED)
                    when(failure){"throw"->error("original CANCEL failure");"reject"->false;else->true}
                }else true
            }
            val result=runCatching{r.input.consumeHeldControl(admission)}
            check(result.isFailure==(failure=="throw"))
            check(r.input.anchor.holding&&r.events.map{it.phase}==listOf(Touch.Phase.DOWN,Touch.Phase.CANCEL,Touch.Phase.DOWN))
            check(r.input.consumeHeldControl(admission)==AnchorControlDisposition.CONSUMED&&r.events.size==3)
            r.drain();val oldCanceled=r.states.single{it.second==AnchorHoldState.CANCELED}.first
            check(r.states.filter{it.second==AnchorHoldState.HELD}.last().first>oldCanceled)
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
    run("Click-and-unaccepted-provisional-DOWN-are-not-persistent-Hold") {CoreRig().use {r->
        val controls=mutableListOf<AnchorControlDisposition>()
        r.onTouch={e->if(e.phase==Touch.Phase.DOWN)controls.add(r.input.consumeHeldControl(AnchorControlAdmission()));true}
        val result=r.input.click(r.admission(),r.setting,r.target,r.source,{true},r.merger::nativeAction)
        check(result==AnchorExecutionStatus.CLICK_SUBMITTED&&controls==listOf(AnchorControlDisposition.NORMAL))
        check(r.events.map{it.phase}==listOf(Touch.Phase.DOWN,Touch.Phase.UP))
    }}
}
