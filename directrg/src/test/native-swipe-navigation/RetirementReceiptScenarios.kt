package com.code2hack.eyeprojector.directrg

import android.content.Intent as BroadcastIntent
import android.view.GestureDetector
import android.view.MotionEvent
import com.code2hack.eyeprojector.protocol.*

internal fun retirementReceiptScenarios(run:(String,()->Unit)->Unit) {
    for(normal in listOf(false,true)) run("actual-Activity-native-marker-decision-before-handler-and-single-original-admission-$normal") {
        SystemClock.time=500;val a=ActivityNavigationWiring();a.showControlPanel()
        val pending=mutableListOf<(ReceiverControlDisposition)->Unit>();val events=mutableListOf<Int>()
        a.session!!.onLocalControl={_,complete->pending.add(complete)};a.root.onMotion={events.add(it.action)}
        val point=a.pointer.position
        a.gestures.accept(PadGestureRecognizer.Event(PadGestureRecognizer.Key.CONTACT,PadGestureRecognizer.Phase.DOWN,1000,1000,position=point),1000)
        a.gestures.accept(PadGestureRecognizer.Event(PadGestureRecognizer.Key.CONTACT,PadGestureRecognizer.Phase.UP,1020,1000),1020)
        SystemClock.time=2020;a.gestures.confirm(2020)
        check(pending.size==1&&events.isEmpty())
        pending.single()(if(normal)ReceiverControlDisposition.NORMAL else ReceiverControlDisposition.CONSUMED)
        check(pending.size==1&&events==if(normal)listOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP)else emptyList<Int>()) {"marker repeated gate/effects requests=${pending.size} events=$events"}
        check(a.localInput.javaClass.getDeclaredField("held").apply{isAccessible=true}.get(a.localInput)==null&&!a.heldOwner.holding)
        a.session!!.controls.close()
    }
    for(normal in listOf(false,true)) run("actual-Activity-native-Motion-join-has-one-admission-and-no-handler-before-decision-$normal") {
        SystemClock.time=500
        val a=ActivityNavigationWiring();a.showControlPanel()
        val pending=mutableListOf<(ReceiverControlDisposition)->Unit>();val events=mutableListOf<Int>()
        a.session!!.onLocalControl={_,complete->pending.add(complete)}
        a.root.onMotion={events.add(it.action)}
        fun event(phase:Int)=MotionEvent.obtain(1000,if(phase==MotionEvent.ACTION_UP)1010 else 1000,phase,0f,0f,0).also{it.source=android.view.InputDevice.SOURCE_TOUCHPAD}
        fun accept(motion:MotionEvent)=a.javaClass.getDeclaredMethod("acceptNativePadMotion",MotionEvent::class.java).apply{isAccessible=true}.invoke(a,motion) as Boolean
        check(accept(event(MotionEvent.ACTION_DOWN)));check(accept(event(MotionEvent.ACTION_UP)))
        check(pending.size==1&&events.isEmpty()&&!a.padContact.holding&&!a.heldOwner.holding)
        SystemClock.time=1010
        pending.single()(if(normal)ReceiverControlDisposition.NORMAL else ReceiverControlDisposition.CONSUMED)
        check(pending.size==1&&events==if(normal)listOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP)else emptyList<Int>()) {"extra admission/handler events $events requests=${pending.size}"}
        check(!a.padContact.holding&&!a.heldOwner.holding)
        a.session!!.controls.close()
    }
    for(normal in listOf(false,true)) run("actual-native-Motion-source-gates-before-position-loss-and-emission-$normal") {
        val a=ActivityNavigationWiring();var reads=0;var lost=0;val events=mutableListOf<PadGestureRecognizer.Event>()
        val pending=mutableListOf<(Boolean)->Unit>()
        val source=NativePadContact(a,{reads++;a.pointer.position},{events.add(it)},{lost++})
        val gate=NativePadMotionInput({source.accept(it)},{pending.add(it)},{"original-context"})
        fun event(phase:Int,down:Long=1000)=MotionEvent.obtain(down,down+if(phase==MotionEvent.ACTION_UP)10 else 0,phase,0f,0f,0).also{it.source=android.view.InputDevice.SOURCE_TOUCHPAD}
        check(gate.accept(event(MotionEvent.ACTION_DOWN)));check(gate.accept(event(MotionEvent.ACTION_UP)))
        check(pending.size==1&&reads==0&&lost==0&&events.isEmpty()&&!source.holding) {"Motion entered original raw handler/position/loss before central decision"}
        pending.single()(normal)
        check(events.map{it.phase}==if(normal)listOf(PadGestureRecognizer.Phase.DOWN,PadGestureRecognizer.Phase.UP)else emptyList<PadGestureRecognizer.Phase>())
        check(!source.holding&&lost==0&&reads==if(normal)2 else 0)
        // A consumed original does not leave a held source or swallow a distinct fresh contact.
        gate.accept(event(MotionEvent.ACTION_DOWN,2000));gate.accept(event(MotionEvent.ACTION_UP,2000));pending.last()(true)
        check(events.takeLast(2).map{it.phase}==listOf(PadGestureRecognizer.Phase.DOWN,PadGestureRecognizer.Phase.UP)&&!source.holding)
        a.session!!.controls.close()
    }
    run("native-Motion-pending-original-retirement-and-context-replacement-do-not-replay") {
        val a=ActivityNavigationWiring();var reads=0;var context="original";val pending=mutableListOf<(Boolean)->Unit>()
        val source=NativePadContact(a,{reads++;a.pointer.position},{error("retired Motion delivered")},{error("retired Motion lost callback")})
        val gate=NativePadMotionInput({source.accept(it)},{pending.add(it)},{context})
        fun down(at:Long)=MotionEvent.obtain(at,at,MotionEvent.ACTION_DOWN,0f,0f,0).also{it.source=android.view.InputDevice.SOURCE_TOUCHPAD}
        gate.accept(down(1000));gate.retire();pending.single()(true)
        gate.accept(down(2000));context="replacement";pending.last()(true)
        check(reads==0&&!source.holding)
        a.session!!.controls.close()
    }
    for(mode in listOf("absent","sync","async","consumed")) run("C151-complete-nested-tap-retains-own-ending-admission-$mode") {
        val a=ActivityNavigationWiring();val events=mutableListOf<Pair<Int,Long>>()
        val replies=mutableListOf<(Boolean)->Unit>();var requests=0
        val gate:(((Boolean)->Unit)->Unit)?=if(mode=="absent")null else {complete->
            requests++
            if(requests==1||mode=="sync")complete(true)
            else if(mode=="consumed")complete(false)else replies.add(complete)
        }
        val local=LocalPointerDispatcher(a.root,gate);var nested=false
        a.root.onMotion={event->events.add(event.action to event.downTime)
            if(event.action==0&&!nested){nested=true
                local.tap(PadGestureRecognizer.Action.Tap(2000,2010,a.pointer.position,PadGestureRecognizer.Timing.CONTACT_MARKER,2010))
            }}
        val original=HeldPointerDispatcher.Event(Touch.Phase.DOWN,1000,1000,a.pointer.position)
        local.touch(original);local.touch(original.copy(phase=Touch.Phase.UP,timeMs=2020))
        if(mode=="async")replies.single()(true)
        val expected=when(mode){
            "consumed"->listOf(0 to 1000L,1 to 1000L)
            "async"->listOf(0 to 1000L,1 to 1000L,0 to 2000L,1 to 2000L)
            else->listOf(0 to 1000L,3 to 1000L,0 to 2000L,1 to 2000L)
        }
        check(events==expected){"Complete nested tap lost/misrouted ending: $events"}
        check(local.javaClass.getDeclaredField("held").apply{isAccessible=true}.get(local)==null){"Completed nested tap left held residue"}
        a.session!!.controls.close()
    }
    for(normal in listOf(false,true)) run("actual-shell-raw-contact-before-prepareDown-effects-normal-$normal") {
        val a=ActivityNavigationWiring();a.showControlPanel();val replies=mutableListOf<(Boolean)->Unit>()
        var prepares=0;var changes=0;val events=mutableListOf<Int>()
        a.root.onMotion={events.add(it.action)}
        val shell=NativePadShellContact(a.heldOwner,{"original"},{a.pointer.position},{prepares++},{changes++},{replies.add(it)})
        val sourceClass=Class.forName(shell.javaClass.name+"\$Source")
        val source=sourceClass.declaredConstructors.single().apply{isAccessible=true}.newInstance(android.net.LocalSocket())
        shell.javaClass.getDeclaredField("source").apply{isAccessible=true}.set(shell,source)
        val accept=shell.javaClass.declaredMethods.single{it.name=="accept"}.apply{isAccessible=true}
        accept.invoke(shell,source,1L,PadGestureRecognizer.Phase.DOWN,0L)
        accept.invoke(shell,source,1L,PadGestureRecognizer.Phase.UP,0L)
        check(replies.size==1&&prepares==0&&changes==0&&events.isEmpty()) {"Shell entered handler before central gate: replies=${replies.size},prepare=$prepares,events=$events"}
        replies.single()(normal)
        check(prepares==if(normal)1 else 0)
        check(changes==if(normal)2 else 0)
        check(events==if(normal)listOf(0,1)else emptyList<Int>())
        shell.stop();a.session!!.controls.close()
    }
    for (normal in listOf(false,true)) run("actual-local-pointer-gates-whole-contact-before-first-widget-effect-normal-$normal") {
        val a=ActivityNavigationWiring();val replies=mutableListOf<(Boolean)->Unit>();val events=mutableListOf<Int>()
        a.root.onMotion={events.add(it.action)}
        val local=LocalPointerDispatcher(a.root){replies.add(it)}
        val down=HeldPointerDispatcher.Event(Touch.Phase.DOWN,1000,1000,a.pointer.position)
        check(local.touch(down));check(local.touch(down.copy(phase=Touch.Phase.MOVE,timeMs=1010)))
        check(local.touch(down.copy(phase=Touch.Phase.UP,timeMs=1020)))
        check(events.isEmpty()&&replies.size==1){"Local effects before central decision: $events; replies=${replies.size}"}
        replies.single()(normal)
        check(events==if(normal)listOf(0,2,1)else emptyList<Int>())
        a.session!!.controls.close()
    }
    run("actual-local-pointer-retirement-detaches-only-original-pending-contact") {
        val a=ActivityNavigationWiring();val replies=mutableListOf<(Boolean)->Unit>();val events=mutableListOf<Long>()
        a.root.onMotion={events.add(it.downTime)}
        val local=LocalPointerDispatcher(a.root){replies.add(it)}
        fun contact(id:Long){val down=HeldPointerDispatcher.Event(Touch.Phase.DOWN,id,id,a.pointer.position)
            check(local.touch(down));check(local.touch(down.copy(phase=Touch.Phase.UP,timeMs=id+10)))}
        contact(1000);val old=local.captureCancellation();contact(2000)
        old.finishOriginal();replies[0](true);replies[1](true)
        check(events==listOf(2000L,2000L)&&old.superseded()) {"Old local reply replayed or hit successor: $events"}
        a.session!!.controls.close()
    }
    run("actual-local-pointer-tap-reentry-does-not-UP-successor-contact") {
        val a=ActivityNavigationWiring();val local=LocalPointerDispatcher(a.root);val events=mutableListOf<Pair<Int,Long>>()
        var nested=false
        a.root.onMotion={event->events.add(event.action to event.downTime);if(event.action==0&&!nested){nested=true
            local.touch(HeldPointerDispatcher.Event(Touch.Phase.DOWN,2000,2000,a.pointer.position))
        }}
        local.tap(PadGestureRecognizer.Action.Tap(1000,1010,a.pointer.position,PadGestureRecognizer.Timing.CONTACT_MARKER,1010))
        check(events==listOf(0 to 1000L,3 to 1000L,0 to 2000L)){"Old tap sent UP into new contact: $events"}
        check(local.touch(HeldPointerDispatcher.Event(Touch.Phase.UP,2000,2010,a.pointer.position)))
        check(events.last()==1 to 2000L)
        a.session!!.controls.close()
    }
    fun motion(phase:Int,down:Long=1000)=MotionEvent.obtain(down,2000,phase,20f,30f,0)
    val tap=HidBinding(HidButton(HidDeviceIdentity("retire-original",1,2),66,30),"Host","Tap")
    val other=tap.copy(button=tap.button.copy(keyCode=67))
    fun local():ActivityNavigationWiring {
        SystemClock.time=1000;android.os.Handler.pending.clear();android.os.Handler.onRemove=null
        GestureDetector.onCancel=null;GestureDetector.failNext=null
        return ActivityNavigationWiring().apply{showControlPanel();check(hidBindings.assign(HidAction.TAP,tap))}
    }
    @Suppress("UNCHECKED_CAST")
    fun prepared(a:ActivityNavigationWiring):Pair<Long,InputRetirement> = a.javaClass.declaredMethods.single{it.name=="prepareInputCancellation"}.apply{isAccessible=true}.invoke(a,a.session,a.owner,true) as Pair<Long,InputRetirement>
    run("receipt-completes-once-reentrantly-and-aggregates-failures") {
        var attempts=0;lateinit var old:InputRetirement
        old=InputRetirement({attempts++;old.finishOriginal();error("first")},{false})
        var later=0
        val failure=runCatching{InputRetirement.finishAll(listOf(old.finishOriginal,{later++;error("second")}))}.exceptionOrNull()!!
        old.finishOriginal();check(attempts==1&&later==1&&failure.message=="first"&&failure.suppressed.single().message=="second")
    }
    run("overlapping-local-receipts-release-old-once-and-preserve-same-uptime-completed-successor") {
        val a=local();val events=mutableListOf<Pair<Int,Long>>()
        a.root.onMotion={events.add(it.action to it.downTime)}
        a.localInput.touch(HeldPointerDispatcher.Event(Touch.Phase.DOWN,1000,1000,a.pointer.position))
        val first=a.localInput.captureCancellation();val second=a.localInput.captureCancellation()
        var nested=false
        a.root.onMotion={e->events.add(e.action to e.downTime);if(e.action==MotionEvent.ACTION_CANCEL&&!nested){nested=true
            a.localInput.touch(HeldPointerDispatcher.Event(Touch.Phase.DOWN,1000,2000,a.pointer.position))
            a.localInput.touch(HeldPointerDispatcher.Event(Touch.Phase.UP,1000,2010,a.pointer.position))
        }}
        first.finishOriginal();second.finishOriginal();first.finishOriginal()
        check(events.map{it.first}==listOf(0,3,0,1)&&first.superseded()&&second.superseded())
        a.session!!.controls.close()
    }
    run("actual-held-owner-nested-DOWN-deferred-CANCEL-drains-new-same-uptime-owner") {
        val a=local();val events=mutableListOf<Pair<Int,Long>>();var nested=false
        a.root.onMotion={e->events.add(e.action to e.downTime);if(e.action==MotionEvent.ACTION_DOWN&&!nested){nested=true
            a.retireInput();check(a.hidBindings.assign(HidAction.TAP,other));a.hidEdge(other,HidKeyInput.Phase.DOWN,1000)
        }}
        a.hidEdge(tap,HidKeyInput.Phase.DOWN,1000)
        check(a.heldOwner.holding&&events.map{it.first}==listOf(0,3,0)) {"deferred order $events"}
        a.hidEdge(other,HidKeyInput.Phase.UP,1000,1100)
        check(events.map{it.first}==listOf(0,3,0,1))
        a.session!!.controls.close()
    }
    run("first-queue-effect-new-direct-local-contact-is-not-hit-by-old-pointer-ID") {
        val a=local();val events=mutableListOf<Pair<Int,Long>>()
        a.root.onMotion={events.add(it.action to it.downTime)};a.hidEdge(tap,HidKeyInput.Phase.DOWN,1000)
        val (_,old)=prepared(a)
        a.session!!.onPendingCancel={a.session!!.onPendingCancel=null
            a.localInput.touch(HeldPointerDispatcher.Event(Touch.Phase.DOWN,1000,2000,a.pointer.position))
            a.localInput.touch(HeldPointerDispatcher.Event(Touch.Phase.UP,1000,2010,a.pointer.position))
        }
        old.finishOriginal();old.finishOriginal()
        check(events.map{it.first}==listOf(0,3,0,1)&&old.superseded()) {"reused backend $events"}
        a.session!!.controls.close()
    }
    run("both-original-root-handles-detached-before-scheduler-admits-new-native-Function") {
        val a=local();a.functionInput.start();val oldConfirm=a.confirmTap;val oldEdge=a.edgeTick
        a.root.postDelayed(oldConfirm,1);a.root.postDelayed(oldEdge,1)
        a.root.onRemove={task->if(task===oldConfirm){a.root.onRemove=null
            a.receivers.last().onReceive(a,BroadcastIntent("com.android.action.ACTION_SPRITE_BUTTON_DOWN"))
            a.root.postDelayed(a.confirmTap,1);a.root.postDelayed(a.edgeTick,1)
        }}
        val (_,receipt)=prepared(a);receipt.finishOriginal()
        check(receipt.superseded()&&a.confirmTap!==oldConfirm&&a.edgeTick!==oldEdge)
        check(a.confirmTap in a.root.pending&&a.edgeTick in a.root.pending)
        val pending=a.root.pending.toSet();oldConfirm.run();oldEdge.run();check(a.root.pending==pending)
        a.functionInput.stop();a.session!!.controls.close()
    }
    run("current-Function-original-scheduler-cancel-preserves-later-Function-timer") {
        val a=local();a.functionInput.start();val receiver=a.receivers.last()
        receiver.onReceive(a,BroadcastIntent("com.android.action.ACTION_SPRITE_BUTTON_DOWN"))
        val oldTasks=android.os.Handler.pending.keys.toSet();val receipt=a.functionInput.prepareCancel()
        check(android.os.Handler.pending.keys.containsAll(oldTasks)) // Preparation did not call scheduler.
        android.os.Handler.onRemove={task->if(task in oldTasks){android.os.Handler.onRemove=null;SystemClock.time=2000
            receiver.onReceive(a,BroadcastIntent("com.android.action.ACTION_SPRITE_BUTTON_DOWN"))
        }}
        receipt.finishOriginal();check(receipt.superseded())
        check(android.os.Handler.pending.isNotEmpty()&&android.os.Handler.pending.keys.none{it in oldTasks})
        a.functionInput.stop();a.session!!.controls.close()
    }
    run("surface-old-detector-CANCEL-and-late-listeners-cannot-clear-or-click-new-detector") {
        val a=local();var taps=0;val surface=ProjectorSurface(a,{_,_->taps++},{_,_,_->})
        surface.onTouchEvent(motion(MotionEvent.ACTION_DOWN));val old=GestureDetector.instances.last()
        val receipt=surface.prepareCancel();val next=GestureDetector.instances.last()
        check(next!==old)
        GestureDetector.onCancel={which->if(which===old){GestureDetector.onCancel=null
            surface.onTouchEvent(motion(MotionEvent.ACTION_DOWN,2000));surface.onTouchEvent(motion(MotionEvent.ACTION_UP,2000))
        }}
        val before=taps;receipt.finishOriginal();check(receipt.superseded()&&old.cancels==1&&next.cancels==0)
        val after=taps;check(after==before+1)
        check(!old.listener.onSingleTapUp(motion(MotionEvent.ACTION_UP)))
        check(!old.listener.onScroll(null,motion(MotionEvent.ACTION_MOVE),0f,10f))
        receipt.finishOriginal();check(taps==after&&old.cancels==1)
        a.session!!.controls.close()
    }
    run("surface-replacement-constructor-failure-retains-old-CANCEL-and-other-cleanup") {
        val a=local();val events=mutableListOf<Int>();a.root.onMotion={events.add(it.action)}
        a.hidEdge(tap,HidKeyInput.Phase.DOWN,1000);a.screen.onTouchEvent(motion(MotionEvent.ACTION_DOWN));val detector=GestureDetector.instances.last()
        GestureDetector.failNext=IllegalStateException("detector allocation failed")
        val (_,receipt)=prepared(a)
        val failure=runCatching{receipt.finishOriginal()}.exceptionOrNull()!!
        check(failure.message=="detector allocation failed"&&detector.cancels==1&&events==listOf(0,3))
        check(!a.heldOwner.holding)
        a.screen.onTouchEvent(motion(MotionEvent.ACTION_DOWN,2000));a.screen.onTouchEvent(motion(MotionEvent.ACTION_UP,2000))
        a.session!!.controls.close()
    }
    run("finish-scheduler-failures-still-retire-original-contact-and-all-other-receipts") {
        val a=local();val events=mutableListOf<Int>();a.root.onMotion={events.add(it.action)};a.hidEdge(tap,HidKeyInput.Phase.DOWN,1000)
        val oldConfirm=a.confirmTap;val oldEdge=a.edgeTick
        a.root.onRemove={task->if(task===oldConfirm)error("confirm removal failed")else if(task===oldEdge)error("edge removal failed")}
        val (_,receipt)=prepared(a);val error=runCatching{receipt.finishOriginal()}.exceptionOrNull()!!
        check(error.message=="confirm removal failed"&&error.suppressed.any{it.message=="edge removal failed"})
        check(events==listOf(0,3)&&!a.heldOwner.holding)
        a.root.onRemove=null;receipt.finishOriginal();check(events==listOf(0,3));a.session!!.controls.close()
    }
    run("indicator-old-render-obligation-survives-bind-false-and-overlap") {
        val a=local();val fact=a.session!!.fact.copy(mode=ReceiverUsabilityMode.PROJECTED);val intent=FunctionIntent(FunctionIntentKind.ACTIVATE,FunctionSource.GLASSES_NATIVE,1,1,
            FunctionIntentContext(fact,HeadPointerState(fact.inputRevision,1,true,true,10f,20f,Fit(0,0,500,400),Geometry(500,400))))
        val indicator=a.anchorHeldIndicator
        indicator.bind(a.session,fact);indicator.accept(a.session!!,intent,1,true)
        val first=indicator.prepareClear();val second=indicator.prepareClear();indicator.bind(a.session,fact)
        first.finishOriginal();second.finishOriginal();check(!indicator.visible&&a.indicatorRenders.last()==false)
        indicator.bind(a.session,fact);indicator.accept(a.session!!,intent,2,true)
        val old=indicator.prepareClear();indicator.bind(a.session,fact);indicator.accept(a.session!!,intent,3,true)
        old.finishOriginal();check(indicator.visible&&a.indicatorRenders.last()&&old.superseded())
        a.session!!.controls.close()
    }
    run("overlay-old-hide-cannot-clear-reused-highlight-or-reentrant-new-show") {
        val a=local();val overlay=a.anchorOverlay;val bounds=AnchorRegistrationHighlight(1.0,2.0,3.0,4.0)
        overlay.show(bounds);val old=overlay.prepareDismiss();overlay.show(bounds);overlay.waiting();old.finishOriginal()
        check(overlay.visibility==View.VISIBLE&&old.superseded())
        val next=overlay.prepareDismiss();overlay.onVisibility={value->if(value==View.GONE){overlay.onVisibility=null;overlay.show(bounds)}}
        next.finishOriginal();check(overlay.visibility==View.VISIBLE&&next.superseded())
        a.session!!.controls.close()
    }
    run("actual-shell-original-raw-press-cancellation-preserves-new-HID-contact") {
        val a=local();val shell=a.shellPadContact;val sourceClass=Class.forName(shell.javaClass.name+"\$Source")
        val source=sourceClass.declaredConstructors.single().apply{isAccessible=true}.newInstance(android.net.LocalSocket())
        shell.javaClass.getDeclaredField("source").apply{isAccessible=true}.set(shell,source)
        val accept=shell.javaClass.declaredMethods.single{it.name=="accept"}.apply{isAccessible=true}
        val revision=shell.javaClass.getDeclaredField("contextRevision").apply{isAccessible=true}.getLong(shell)
        accept.invoke(shell,source,1L,PadGestureRecognizer.Phase.DOWN,revision)
        var fresh=false
        a.root.onMotion={e->if(e.action==MotionEvent.ACTION_CANCEL&&!fresh){fresh=true;SystemClock.time=2000;check(a.hidBindings.assign(HidAction.TAP,other));a.hidEdge(other,HidKeyInput.Phase.DOWN,2000)}}
        val (_,receipt)=prepared(a);receipt.finishOriginal();check(fresh&&receipt.superseded()&&a.heldOwner.holding)
        accept.invoke(shell,source,1L,PadGestureRecognizer.Phase.UP,revision)
        check(a.heldOwner.holding);a.session!!.controls.close()
    }
}
