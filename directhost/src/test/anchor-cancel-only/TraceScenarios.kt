package com.code2hack.eyeprojector.directhost

import com.code2hack.eyeprojector.protocol.*
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal fun traceScenarios(run:(String,(Joined)->Unit)->Unit) {
    run("actual-Phone-trace-observes-original-blocked-cancel-and-discard-without-gate-or-output-substitution") { j ->
        val file=File(System.getenv("EYEPROJECTOR_HOLD_EVIDENCE"),"phone-original-cancel.jsonl")
        val trace=PhoneInputTrace.create(file,ProjectionContext("trace-owner","trace-original-session"),119,"source-host")
        j.input.anchor.observation(trace)
        j.hold()
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        j.cancelBehavior={entered.countDown();check(release.await(5,TimeUnit.SECONDS));true}
        val tap=Frame(Wire.TAP,payload=Tap(20,30,Geometry(480,640)).bytes())
        trace.withFrame(tap){j.phone.receive(tap)}
        check(entered.await(2,TimeUnit.SECONDS))
        try {
            val next=Frame(Wire.CONTROL_INTENT,payload=ReceiverControlIntent(77,ReceiverControlSource.LOCAL_UI,
                j.glasses.session.observationUsability()!!).bytes())
            trace.withFrame(next){j.phone.receive(next)}
            check(j.events.map{it.phase}==listOf(Touch.Phase.DOWN,Touch.Phase.CANCEL)&&j.ordinary.isEmpty())
        }finally{release.countDown()}
        j.pump()
        val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3)
        while((!file.isFile||!file.readText().contains("CANCEL_FINISHED"))&&System.nanoTime()<deadline)Thread.yield()
        val lines=file.readLines()
        for(stage in listOf("HELD","CANCELING","CANCEL_PREPARED","CANCEL_FINISH_ENTER","CANCEL_FINISHED","CONTROL_RECEIVED","CONTROL_DISPOSITION","FRAME_EXIT"))
            check(lines.any{it.contains("\"stage\":\"$stage\"")}){stage}
        check(lines.any{it.contains("\"receivedDuringRetirement\":true")&&it.contains("\"disposition\":\"CONSUMED\"")})
        check(lines.any{it.contains("\"frameControlId\":77")&&it.contains("\"frameControlSource\":\"LOCAL_UI\"")})
        check(trace.appendFailure==null&&!j.input.anchor.discardingControls)
        trace.close()
        val disposed=System.nanoTime()+TimeUnit.SECONDS.toNanos(3)
        while(!trace.terminated&&System.nanoTime()<disposed)Thread.yield()
        check(trace.terminated)
    }
    run("optional-trace-append-failure-and-throw-observation-do-not-alter-original-input-or-exception") { j ->
        val directory=File(System.getenv("EYEPROJECTOR_HOLD_EVIDENCE"))
        val trace=PhoneInputTrace.create(directory,ProjectionContext("trace-owner","bad-file"),120)
        j.input.anchor.observation(trace)
        j.hold();j.press()
        check(j.events.map{it.phase}==listOf(Touch.Phase.DOWN,Touch.Phase.UP))
        val original=IllegalStateException("original-handler-failure")
        val thrown=runCatching{trace.withFrame(Frame(Wire.PING)){throw original}}.exceptionOrNull()
        check(thrown===original)
        val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3)
        while(trace.appendFailure==null&&System.nanoTime()<deadline)Thread.yield()
        check(trace.appendFailure!=null&&trace.appended==0L)
        trace.close()
        val disposed=System.nanoTime()+TimeUnit.SECONDS.toNanos(3)
        while(!trace.terminated&&System.nanoTime()<disposed)Thread.yield()
        check(trace.terminated)
    }
}
