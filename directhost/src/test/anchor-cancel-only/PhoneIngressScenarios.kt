package com.code2hack.eyeprojector.directhost

import com.code2hack.eyeprojector.directhost.hid.*
import com.code2hack.eyeprojector.directhost.input.PhoneHidRoute
import com.code2hack.eyeprojector.protocol.*

internal fun phoneIngressScenarios(run:(String,(Joined)->Unit)->Unit) {
    run("actual-Phone-HID-lifecycle-page-restore-preserves-Anchor-and-explicit-open-cancels-only") { j ->
        val input=PhoneHidController(PhoneHidBindings({null},{true}),object:PhoneHidRoutes{
            override fun capture(down:PhoneHidEdge):PhoneHidRoute?=null
            override fun submit(route:PhoneHidRoute,edge:PhoneHidEdge)=Unit
            override fun cancel(route:PhoneHidRoute,eventTimeMs:Long)=Unit
        },{operation,complete->operation();complete()},{1000},
            userControl={j.runtime.userControl()},recognizeInput={j.runtime.userControl(recognitionOnly=true)})
        j.hold();input.resumePage();j.pump()
        check(input.pageVisible&&j.input.anchor.holding&&j.events.size==1)
        input.closePage();input.openPage();j.pump()
        check(!input.pageVisible&&!j.input.anchor.holding&&j.events.map{it.phase}==listOf(Touch.Phase.DOWN,Touch.Phase.CANCEL))
        input.openPage();check(input.pageVisible)
    }
    run("actual-Phone-HID-learning-cancels-only-before-capture-save-or-feedback") { j ->
        var saved:String?=null;var writes=0;var changed=0
        val bindings=PhoneHidBindings({saved},{saved=it;writes++;true})
        val input=PhoneHidController(bindings,object:PhoneHidRoutes{
            override fun capture(down:PhoneHidEdge):PhoneHidRoute?=error("Learning must not enter route handler")
            override fun submit(route:PhoneHidRoute,edge:PhoneHidEdge)=error("No route")
            override fun cancel(route:PhoneHidRoute,eventTimeMs:Long)=error("No action-owned cancellation")
        },{operation,complete->operation();complete()},{1000},{changed++},
            userControl={j.runtime.userControl()},recognizeInput={j.runtime.userControl(recognitionOnly=true)})
        input.openPage();input.select(PhoneHidAction.TAP)
        val binding=PhoneHidBinding(PhoneHidButton(PhoneHidDeviceIdentity("source",1,2),66,30),"Host","Tap")
        val down=PhoneHidController.Event(7,binding,PhoneHidPhase.DOWN,1000,1000)
        j.hold();val before=changed
        check(input.accept(down));j.pump()
        check(!input.capturing&&writes==0&&changed==before)
        check(input.accept(down.copy(phase=PhoneHidPhase.UP,eventTimeMs=1010)))
        check(writes==0&&j.events.map{it.phase}==listOf(Touch.Phase.DOWN,Touch.Phase.CANCEL))
        val fresh=down.copy(downTimeMs=2000,eventTimeMs=2000)
        check(input.accept(fresh)&&input.capturing)
        check(input.accept(fresh.copy(phase=PhoneHidPhase.UP,eventTimeMs=2010)))
        check(writes==1&&bindings.binding(PhoneHidAction.TAP)?.button==binding.button)
    }
    run("actual-Phone-HID-discard-receipt-survives-before-app-queue-and-late-UP") { j ->
        var writes=0
        val input=PhoneHidController(PhoneHidBindings({null},{writes++;true}),object:PhoneHidRoutes{
            override fun capture(down:PhoneHidEdge):PhoneHidRoute?=error("Discarded control reached handler")
            override fun submit(route:PhoneHidRoute,edge:PhoneHidEdge)=error("No route")
            override fun cancel(route:PhoneHidRoute,eventTimeMs:Long)=error("No per-action cancellation")
        },{operation,complete->operation();complete()},{1000},
            userControl={j.runtime.userControl()},recognizeInput={j.runtime.userControl(recognitionOnly=true)})
        input.openPage();input.select(PhoneHidAction.FUNCTION)
        val binding=PhoneHidBinding(PhoneHidButton(PhoneHidDeviceIdentity("source",1,2),66,30),"Host","Function")
        val down=PhoneHidController.Event(7,binding,PhoneHidPhase.DOWN,1000,1000,discarded=true)
        // This immutable receipt represents the Binder listener's pre-queue CANCELING observation.
        // Deliver it after the original retirement, then its unmarked physical tail.
        j.hold();j.phone.receive(Frame(Wire.TAP,payload=Tap(20,30,Geometry(480,640)).bytes()));j.pump()
        check(input.accept(down)&&!input.capturing)
        check(input.accept(down.copy(phase=PhoneHidPhase.UP,eventTimeMs=1010,discarded=false)))
        check(writes==0&&j.events.size==2)
    }
}
