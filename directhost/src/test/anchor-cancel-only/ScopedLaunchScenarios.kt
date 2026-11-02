package com.code2hack.eyeprojector.directhost

import com.code2hack.eyeprojector.protocol.*
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

internal fun scopedLaunchScenarios(run:(String,(Joined)->Unit)->Unit) {
    run("actual-queued-contextual-Home-preserves-successor-Hold-before-original-commit") { j -> queuedHomeBefore(j) }
    for(replace in listOf(false,true)) run("actual-platform-resolver-last-submit-original-context-replaced-$replace") { j ->
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val commands=CopyOnWriteArrayList<List<String>>()
        val failures=CopyOnWriteArrayList<Throwable>()
        val result=AtomicReference<ProjectionDisplayState>()
        val platform=PlatformProjectionLaunch(OwnedProjectionCommands { args ->
            check(!Thread.holdsLock(j.phone.get("controlLock")))
            commands.add(args.toList())
            object:Child() {
                override fun getInputStream()=ByteArrayInputStream((if("resolve-activity" in args)"app/.Main\n" else "Status: ok\nActivity: app/.Main\n").toByteArray())
                override fun waitFor(timeout:Long,unit:TimeUnit):Boolean {
                    if("resolve-activity" in args){entered.countDown();check(release.await(5,TimeUnit.SECONDS))}
                    alive=false;return true
                }
            }
        })
        j.phone.adapter(platform)
        val worker=Thread{try{result.set(j.phone.launch(141,"app"))}catch(error:Throwable){failures.add(error)}}
        worker.start()
        try {
            check(entered.await(2,TimeUnit.SECONDS))
            if(replace){
                val fact=(j.phone.session as CapturedProjection).receiverUsability().fact!!
                j.phone.receive(Frame(Wire.RECEIVER_USABILITY,payload=fact.copy(revision=fact.revision+1,inputRevision=fact.inputRevision+1).bytes()))
            }
        }finally{release.countDown();worker.join(3000)}
        check(!worker.isAlive&&failures.isEmpty()) {failures.toString()}
        val launches=commands.filter{"start-activity" in it}
        check(launches.size==if(replace)0 else 1) {"Stale final platform submission: $commands"}
        if(!replace)check(result.get().status==ProjectionLaunchStatus.STARTED)
        platform.close()
    }
}
