package com.code2hack.eyeprojector.directrg
import android.content.ComponentName

fun main(){
    for(exit in listOf(false,true)) {
        val ui=ActivityLifecycleSource()
        if(exit)ui.exitToLauncher() else ui.finishForTest()
        check(ui.finished && ui.serviceIntents.size==1)
        val sent=ui.serviceIntents.single()
        check(sent.extras["eyeprojector.scope"]=="original-scope" && sent.extras["eyeprojector.trigger"]=="original-T" && sent.extras["eyeprojector.task"]==7)
        check(sent.extras[ReceiverWake.ACTIVATION]=="original-H")
        val owner=OwnerBoundary()
        ui.onServiceConnected(ComponentName(),ReceiverSessionService.LocalBinder(owner))
        check(owner.launches.isEmpty() && owner.canceled.single()==ui.scopeForTest() to "original-H")
        check(ui.attachments==0 && ui.registrations==0)
        println("PASS exact Activity ${if(exit)"Exit" else "finish"} before binding sends original cancellation; delayed binding cannot launch")
    }
    ActivityLifecycleSource().also { ui ->
        val owner=OwnerBoundary();ui.onServiceConnected(ComponentName(),ReceiverSessionService.LocalBinder(owner))
        check(owner.launches.single()==ui.scopeForTest() && owner.canceled.isEmpty() && ui.attachments==1)
        println("PASS ordinary binding retains original automatic launch; Exit handling introduces no manual-first-open gate")
    }
    ActivityLifecycleSource().also { ui ->
        ui.wakeValid=false;ui.intent.putExtra(ReceiverWake.ACTIVATION,"stale")
        val owner=OwnerBoundary();ui.onServiceConnected(ComponentName(),ReceiverSessionService.LocalBinder(owner))
        check(ui.finished && owner.launches.isEmpty() && owner.canceled.isEmpty())
        println("PASS old wake callback cannot launch or cancel another current owner")
    }
}
