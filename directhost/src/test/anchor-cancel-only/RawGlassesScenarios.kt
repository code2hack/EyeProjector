package com.code2hack.eyeprojector.directhost

import com.code2hack.eyeprojector.protocol.*
import com.code2hack.eyeprojector.directrg.FunctionGestureRecognizer.Report

internal fun rawGlassesScenarios(run:(String,(Joined)->Unit)->Unit) {
    fun records(j:Joined,name:String):Int = when(val value=j.phone.get(name)){
        is Map<*,*>->value.size;is Set<*>->value.size;else->error("Unexpected original metadata")
    }
    run("actual-raw-original-cancellation-disposes-pending-Phone-receipt-without-retiring-held-contact") { j ->
        j.hold();val at=android.os.SystemClock.uptimeMillis()+2000
        val before=j.events.toList()
        repeat(12){index->
            j.wiring.rawFunction(Report.DOWN,at+index*2000)
            val frames=j.glasses.frames();j.fromGlasses.addAll(frames);frames.forEach(j.phone::receive)
            check(records(j,"remoteFunctionOriginals")==1)
            j.wiring.cancelFunction();j.pump()
            check(records(j,"remoteFunctionOriginals")==0&&records(j,"remoteFunctionRetiredBeforeBirth")==0)
        }
        check(j.events.toList()==before&&j.input.anchor.holding)
    }
    run("actual-original-retirement-before-blocked-birth-does-not-resurrect-or-retain-record") { j ->
        val entered=java.util.concurrent.CountDownLatch(1);val release=java.util.concurrent.CountDownLatch(1)
        val lane=j.glasses.session.javaClass.getDeclaredField("controls").apply{isAccessible=true}.get(j.glasses.session) as com.code2hack.eyeprojector.directrg.OrderedControlDispatcher
        lane.submit(0,{true},"test-original-writer",preserveTiming=false){entered.countDown();check(release.await(5,java.util.concurrent.TimeUnit.SECONDS))}
        check(entered.await(2,java.util.concurrent.TimeUnit.SECONDS))
        val at=android.os.SystemClock.uptimeMillis()+2000
        try {
            j.wiring.rawFunction(Report.DOWN,at);j.wiring.cancelFunction()
            val until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(2)
            var retired:Frame?=null
            val lock=j.glasses.session.javaClass.getDeclaredField("outputLock").apply{isAccessible=true}.get(j.glasses.session)
            while(retired==null&&System.nanoTime()<until){
                val frames=synchronized(lock){val input=java.io.DataInputStream(j.glasses.output.toByteArray().inputStream());buildList{while(input.available()>0)add(Wire.readFrame(input))}}
                retired=frames.firstOrNull{it.channel==Wire.FUNCTION_ORIGINAL&&FunctionOriginal.from(it.payload).retired}
                if(retired==null)Thread.yield()
            }
            j.phone.receive(checkNotNull(retired));check(records(j,"remoteFunctionOriginals")==0&&records(j,"remoteFunctionRetiredBeforeBirth")==1)
        }finally{release.countDown()}
        j.pump()
        check(records(j,"remoteFunctionOriginals")==0&&records(j,"remoteFunctionRetiredBeforeBirth")==0&&j.events.isEmpty())
        j.wiring.short(at+2000);j.pump();check(j.input.anchor.holding)
    }
    run("actual-replayed-original-and-final-cannot-reopen-completed-Function") { j ->
        j.hold();j.press();val before=j.events.toList();val reads=j.windows.reads
        val begin=j.fromGlasses.first{it.channel==Wire.FUNCTION_ORIGINAL&&!FunctionOriginal.from(it.payload).retired}
        val intent=j.fromGlasses.first{it.channel==Wire.FUNCTION_INTENT}
        j.phone.receive(begin);j.phone.receive(intent);j.pump()
        check(j.events.toList()==before&&j.windows.reads==reads&&records(j,"remoteFunctionOriginals")==0)
        j.press();check(j.input.anchor.holding)
    }
    run("actual-fresh-raw-DOWN-in-same-summary-sequence-keeps-own-post-cancel-lifetime") { j ->
        j.hold();val at=android.os.SystemClock.uptimeMillis()+2000
        j.wiring.rawFunction(Report.DOWN,at);j.wiring.rawFunction(Report.UP,at+40)
        val begin=j.glasses.frames();j.fromGlasses.addAll(begin);begin.forEach(j.phone::receive)
        j.phone.receive(Frame(Wire.TAP,payload=Tap(20,30,Geometry(480,640)).bytes()));j.pump()
        j.wiring.rawFunction(Report.DOWN,at+100);j.wiring.rawFunction(Report.UP,at+140)
        j.wiring.rawFunction(Report.DOUBLE,at+150);j.wiring.confirmFunction(at+650);j.pump()
        check(j.events.map{it.phase}==listOf(Touch.Phase.DOWN,Touch.Phase.CANCEL,Touch.Phase.DOWN)&&j.input.anchor.holding)
    }
    for(h2 in listOf(false,true))for(summary in listOf("Short","Long","Double","Triple"))
        run("actual-Glasses-raw-$summary-original-H1-canceled-H2-$h2-never-recaptures") { j ->
            j.hold()
            val at=android.os.SystemClock.uptimeMillis()+2000
            j.wiring.rawFunction(Report.DOWN,at)
            val begin=j.glasses.frames();j.fromGlasses.addAll(begin);begin.forEach(j.phone::receive)
            check(begin.any{it.channel==Wire.FUNCTION_ORIGINAL})
            j.phone.receive(Frame(Wire.TAP,payload=Tap(20,30,Geometry(480,640)).bytes()));j.pump()
            if(h2){startResolvedHold(j);j.pump()}
            val before=j.events.toList();val saved=j.runtime.store.snapshot("app");val reads=j.windows.reads
            if(summary=="Long"){j.wiring.rawFunction(Report.LONG,at+800);j.wiring.rawFunction(Report.UP,at+810);j.wiring.rawFunction(Report.CLICK,at+820)}
            else {
                j.wiring.rawFunction(Report.UP,at+40)
                j.wiring.rawFunction(when(summary){"Double"->Report.DOUBLE;"Triple"->Report.TRIPLE;else->Report.CLICK},at+50)
                j.wiring.confirmFunction(at+550)
            }
            j.pump()
            check(j.events.toList()==before&&j.input.anchor.holding==h2&&j.windows.reads==reads&&j.runtime.store.snapshot("app")==saved) {
                "Late original/summary recaptured: before=$before after=${j.events} held=${j.input.anchor.holding}"
            }
            j.wiring.short(at+2000);j.pump()
            check(j.input.anchor.holding!=h2)
        }
}
