package com.code2hack.eyeprojector.directrg

import android.content.Intent as BroadcastIntent
import android.view.MotionEvent
import com.code2hack.eyeprojector.protocol.*

internal fun retirementBehaviorScenarios(run:(String,()->Unit)->Unit) {
    for(kind in listOf("Contact","Tap","Scroll","Long")) run("actual-pad-$kind-central-before-old-local-cleanup-and-whole-action-discard") {
        SystemClock.time=1000
        val a=ActivityNavigationWiring();a.showControlPanel();val source=a.session!!
        val events=mutableListOf<Int>();a.root.onMotion={events.add(it.action)}
        a.localInput.touch(HeldPointerDispatcher.Event(Touch.Phase.DOWN,900,1000,a.pointer.position))
        val replies=mutableListOf<(ReceiverControlDisposition)->Unit>()
        source.onLocalControl={_,complete->replies.add(complete)}
        fun dispatch(action:PadGestureRecognizer.Action){a.javaClass.getDeclaredMethod("dispatchPadAction",PadGestureRecognizer.Action::class.java).apply{isAccessible=true}.invoke(a,action)}
        SystemClock.time=2000
        when(kind){
            "Contact"->{val down=PadGestureRecognizer.Event(PadGestureRecognizer.Key.HELD_CONTACT,PadGestureRecognizer.Phase.DOWN,2000,2000,position=a.pointer.position)
                dispatch(PadGestureRecognizer.Action.Contact(down));dispatch(PadGestureRecognizer.Action.Contact(down.copy(phase=PadGestureRecognizer.Phase.UP,timeMs=2010)))}
            "Tap"->dispatch(PadGestureRecognizer.Action.Tap(2000,2010,a.pointer.position,PadGestureRecognizer.Timing.CONTACT_MARKER,2010))
            "Scroll"->dispatch(PadGestureRecognizer.Action.Scroll(160,a.pointer.position,2000))
            else->dispatch(PadGestureRecognizer.Action.LongPress(2000,2000,a.pointer.position))
        }
        check(replies.size==1&&events==listOf(0)){"Pad $kind entered handler/cleanup before gate: replies=${replies.size}, effects=$events"}
        replies.single()(ReceiverControlDisposition.CONSUMED)
        check(events==listOf(0)&&!a.heldOwner.holding)
        source.controls.close()
    }
    for (retirement in listOf("none", "cancel", "stop")) run("actual-native-sleep-last-socket-submission-retirement-$retirement") {
        val a=ActivityNavigationWiring()
        val entered=java.util.concurrent.CountDownLatch(1); val release=java.util.concurrent.CountDownLatch(1)
        val closed=java.util.concurrent.CountDownLatch(1)
        val writes=java.util.concurrent.CopyOnWriteArrayList<String>()
        android.net.LocalSocket.onConnect={entered.countDown();check(release.await(5,java.util.concurrent.TimeUnit.SECONDS))}
        android.net.LocalSocket.onWrite={writes.add(it.toString(Charsets.US_ASCII))}
        android.net.LocalSocket.onClose={closed.countDown()}
        try {
            check(a.shellPadContact.sleep())
            check(entered.await(2,java.util.concurrent.TimeUnit.SECONDS))
            when(retirement){"cancel"->a.shellPadContact.prepareCancel().finishOriginal();"stop"->a.shellPadContact.stop()}
            release.countDown()
            check(closed.await(3,java.util.concurrent.TimeUnit.SECONDS))
            check(writes==if(retirement=="none") listOf("SLEEP\n") else emptyList<String>()) {"Stale sleep submitted: $writes"}
        } finally {
            release.countDown()
            check(closed.await(3,java.util.concurrent.TimeUnit.SECONDS))
            android.net.LocalSocket.onConnect={};android.net.LocalSocket.onWrite={};android.net.LocalSocket.onClose={}
            a.session!!.controls.close()
        }
    }
    for (origin in listOf("HID","NATIVE")) for (phase in listOf("queued", "written", "inflight")) run("actual-$origin-projected-HeldTouchTransport-$phase-original-cleanup-preserves-reused-contact") {
        SystemClock.time=1000
        val a=ActivityNavigationWiring(); val source=a.session!!
        val entered=java.util.concurrent.CountDownLatch(1);val release=java.util.concurrent.CountDownLatch(1)
        val done=java.util.concurrent.CountDownLatch(1)
        if(phase=="queued") {
            source.controls.submit(0,{true},"test-TLS-blocker",preserveTiming=false){entered.countDown();check(release.await(5,java.util.concurrent.TimeUnit.SECONDS))}
            check(entered.await(2,java.util.concurrent.TimeUnit.SECONDS))
        }
        if(phase=="inflight") source.onTouchWrite={event->if(event.phase==Touch.Phase.DOWN&&event.target.x!=10){
            entered.countDown();check(release.await(5,java.util.concurrent.TimeUnit.SECONDS))
        }}
        val binding=HidBinding(HidButton(HidDeviceIdentity("projected",1,2),66,30),"Host","Tap")
        a.connectionView.visibility=View.GONE
        if(origin=="HID") {check(a.hidBindings.assign(HidAction.TAP,binding));check(a.hidEdge(binding,HidKeyInput.Phase.DOWN,1000))}
        else {
            val event=MotionEvent.obtain(1000,1000,MotionEvent.ACTION_DOWN,0f,0f,0)
            event.source=android.view.InputDevice.SOURCE_TOUCHPAD
            check(a.padContact.accept(event))
        }
        if(phase=="written") {
            val until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(2)
            while(source.writtenTouches.isEmpty()&&System.nanoTime()<until)Thread.yield()
            check(source.writtenTouches.single().phase==Touch.Phase.DOWN)
        }
        if(phase=="inflight")check(entered.await(2,java.util.concurrent.TimeUnit.SECONDS))
        var admitted=false
        source.onPendingCancel={if(!admitted){admitted=true
            check(source.touch(Touch(Touch.Phase.DOWN,1000,Tap(10,10,a.currentGeometry!!)),2000))
            check(source.controls.submit(2010,{true},"later-work",preserveTiming=false){done.countDown()})
        }}
        try {a.retireInput()} finally {release.countDown()}
        check(done.await(3,java.util.concurrent.TimeUnit.SECONDS))
        check(admitted&&source.touch(Touch(Touch.Phase.UP,1000,Tap(10,10,a.currentGeometry!!)),2010))
        val until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(3)
        while(source.controls.snapshot().let{it.pending>0||it.inFlight>0}&&System.nanoTime()<until)Thread.yield()
        val events=source.writtenTouches.toList()
        val expected=if(phase=="queued")listOf(Touch.Phase.DOWN,Touch.Phase.UP)
            else listOf(Touch.Phase.DOWN,Touch.Phase.CANCEL,Touch.Phase.DOWN,Touch.Phase.UP)
        check(events.map{it.phase}==expected&&events.takeLast(2).all{it.target.x==10}) {"Original cleanup hit later contact: $events"}
        source.controls.close()
    }
    run("actual-native-pad-local-ingress-new-contact-survives-original-retirement-and-tail") {
        SystemClock.time=500
        val a=ActivityNavigationWiring();a.showControlPanel();SystemClock.time=1000
        fun event(down:Long,phase:Int):MotionEvent {
            SystemClock.time=maxOf(SystemClock.time,down)+(if(phase==MotionEvent.ACTION_UP)10 else 0)
            return MotionEvent.obtain(down,SystemClock.time,phase,0f,0f,0).also{it.source=android.view.InputDevice.SOURCE_TOUCHPAD}
        }
        val events=mutableListOf<Pair<Int,Long>>()
        var admitted=false
        a.root.onMotion={motion->events.add(motion.action to motion.downTime)
            if(motion.action==MotionEvent.ACTION_CANCEL&&!admitted){
                admitted=true;SystemClock.time=2000;check(a.padContact.accept(event(2000,MotionEvent.ACTION_DOWN)))
            }
        }
        check(a.padContact.accept(event(1000,MotionEvent.ACTION_DOWN)))
        a.retireInput()
        check(admitted&&a.padContact.holding&&a.heldOwner.holding) { "admitted=$admitted raw=${a.padContact.holding} held=${a.heldOwner.holding} events=$events" }
        check(a.padContact.accept(event(1000,MotionEvent.ACTION_UP)))
        check(a.padContact.holding&&events==listOf(MotionEvent.ACTION_DOWN to 1000L,MotionEvent.ACTION_CANCEL to 1000L,MotionEvent.ACTION_DOWN to 2000L))
        check(a.padContact.accept(event(2000,MotionEvent.ACTION_UP)))
        check(!a.padContact.holding&&!a.heldOwner.holding&&events.last()==MotionEvent.ACTION_UP to 2000L)
        a.session!!.controls.close()
    }
    for (destination in listOf("showControlPanel", "showHidBindings")) run("actual-$destination-aborts-after-new-local-admission") {
        SystemClock.time=1000
        val a=ActivityNavigationWiring()
        a.showControlPanel()
        if (destination=="showControlPanel") { a.presentation.back(); a.connectionView.visibility=View.GONE }
        val panel=a.presentation.panelVisible
        val connection=a.connectionView.visibility
        val settings=a.hidPanel.visibility
        a.localInput.touch(HeldPointerDispatcher.Event(Touch.Phase.DOWN,900,1000,a.pointer.position))
        var admitted=false
        a.root.onMotion={event->if(event.action==MotionEvent.ACTION_CANCEL&&!admitted){
            admitted=true
            a.localInput.touch(HeldPointerDispatcher.Event(Touch.Phase.DOWN,901,2000,a.pointer.position))
        }}
        if (destination=="showControlPanel") a.showControlPanel()
        else a.javaClass.getDeclaredMethod(destination).apply{isAccessible=true}.invoke(a)
        check(a.presentation.panelVisible==panel&&a.connectionView.visibility==connection&&a.hidPanel.visibility==settings) {
            "Original $destination continued into successor UI"
        }
        check(admitted&&a.localInput.touch(HeldPointerDispatcher.Event(Touch.Phase.UP,901,2010,a.pointer.position)))
        a.session!!.controls.close()
    }
    val binding=HidBinding(HidButton(HidDeviceIdentity("retirement-input",1,2),66,30),"Host","Tap")
    fun baseline():ActivityNavigationWiring {
        SystemClock.time=1000
        return ActivityNavigationWiring().apply {
            showControlPanel();check(hidBindings.assign(HidAction.TAP,binding));check(hidEdge(binding,HidKeyInput.Phase.DOWN,1000))
        }
    }
    for(kind in listOf("LOCAL-COMPLETE","HID-SCROLL-COMPLETE","HID-FUNCTION-COMPLETE","PAD-COMPLETE","NATIVE-FUNCTION-COMPLETE")) {
        run("same-context-$kind-during-original-CANCEL-fences-old-navigation") {
            val a=baseline();val old=a.session!!;var admitted=false;var expectedPublications=-1;val actions=mutableListOf<Int>()
            val follow=HidBinding(HidButton(HidDeviceIdentity("other",3,4),67,31),"Host","Other")
            if(kind=="HID-SCROLL-COMPLETE")check(a.hidBindings.assign(HidAction.SCROLL_DOWN,follow))
            if(kind=="HID-FUNCTION-COMPLETE")check(a.hidBindings.assign(HidAction.FUNCTION,follow))
            if(kind=="NATIVE-FUNCTION-COMPLETE")a.functionInput.start()
            a.root.onMotion={event->
                actions.add(event.action)
                if(event.action==MotionEvent.ACTION_CANCEL&&!admitted){
                    admitted=true;SystemClock.time=2000;old.fact=old.fact.copy(inputRevision=a.inputGeneration)
                    when(kind){
                        "LOCAL-COMPLETE"->{a.localInput.touch(HeldPointerDispatcher.Event(Touch.Phase.DOWN,1000,2000,a.pointer.position));a.localInput.touch(HeldPointerDispatcher.Event(Touch.Phase.UP,1000,2010,a.pointer.position))}
                        "HID-SCROLL-COMPLETE"->{a.hidEdge(follow,HidKeyInput.Phase.DOWN,2000);a.hidEdge(follow,HidKeyInput.Phase.UP,2000,2010)}
                        "HID-FUNCTION-COMPLETE"->{a.hidEdge(follow,HidKeyInput.Phase.DOWN,2000);a.hidEdge(follow,HidKeyInput.Phase.UP,2000,2010)}
                        "PAD-COMPLETE"->{
                            val event=PadGestureRecognizer.Event(PadGestureRecognizer.Key.CONTACT,PadGestureRecognizer.Phase.DOWN,2000,2000,position=a.pointer.position)
                            a.gestures.accept(event,2000);a.gestures.accept(event.copy(phase=PadGestureRecognizer.Phase.UP,timeMs=2010),2010);a.gestures.confirm(4000)
                        }
                        else->{
                            val receiver=a.receivers.last();receiver.onReceive(a,BroadcastIntent("com.android.action.ACTION_SPRITE_BUTTON_DOWN"))
                            receiver.onReceive(a,BroadcastIntent("com.android.action.ACTION_SPRITE_BUTTON_UP"))
                            android.os.Handler.pending.toList().forEach{(task,_)->android.os.Handler.pending.remove(task);SystemClock.time=3000;task.run()}
                        }
                    }
                    expectedPublications=a.publishedSessions.size
                }
            }
            a.receivers.first().onReceive(a,BroadcastIntent("com.android.action.ACTION_TWO_FINGER_SWIPE_BACK"))
            check(admitted&&old.navigations.isEmpty()&&a.session===old&&!a.finished&&a.launch==null) {"Old continuation after $kind navigated: ${old.navigations}"}
            check(a.presentation.panelVisible&&a.connectionView.visibility==View.VISIBLE)
            check(a.publishedSessions.size==expectedPublications)
            old.controls.close()
        }
    }
    run("ignored-unbound-and-duplicate-HID-report-does-not-fabricate-new-admission") {
        val a=baseline();var once=false
        a.root.onMotion={event->if(event.action==MotionEvent.ACTION_CANCEL&&!once){once=true
            val unbound=binding.copy(button=binding.button.copy(keyCode=200))
            check(!a.hidEdge(unbound,HidKeyInput.Phase.DOWN,2000))
            a.hidEdge(binding,HidKeyInput.Phase.DOWN,1000)
        }}
        a.receivers.first().onReceive(a,BroadcastIntent("com.android.action.ACTION_TWO_FINGER_SWIPE_BACK"))
        check(once&&!a.presentation.panelVisible)
        a.session!!.controls.close()
    }
}
