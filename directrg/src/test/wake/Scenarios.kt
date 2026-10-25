package com.code2hack.eyeprojector.directrg
import android.content.Context
import com.code2hack.eyeprojector.protocol.*
class ReceiverActivity
fun main(){
    val file=java.nio.file.Files.createTempDirectory("wake-source-").resolve("feature.properties").toFile()
    val feature=ReceiverActivationStore(file);feature.startIncarnation()
    val scope=ReceiverLaunchScope("scope","T",7);feature.launch(scope,"admission")
    val d=ActivationDirective("H",true,1,"A")
    feature.accept(ActivationReply(d,TriggerReceipt("T","H",TriggerDisposition.MAPPED)))
    val c=Context();val wake=ReceiverWake(c,feature)
    wake.request(d);check(c.launches.size==1 && c.power.locks.none{it.isHeld})
    check(wake.outcome=="ACTIVITY_LAUNCH_SUBMITTED")
    // Explicit fixture ticks: a successful return still has no observed presentation.
    repeat(3){wake.reconcile(feature.presentation,false);wake.retryPending()}
    check(c.launches.size==4 && c.power.locks.none{it.isHeld})
    feature.report(feature.demand()!!,1,ReceiverUsabilityMode.LOCAL_PANEL,"LOCAL_PANEL");wake.reconcile(feature.presentation,false);wake.retryPending();check(c.launches.size==4)
    feature.report(feature.demand()!!,2,ReceiverUsabilityMode.NONE,"DISPLAY_DETACHED");wake.reconcile(feature.presentation,false);wake.retryPending();check(c.launches.size==4)
    println("PASS actual Wake retries unpresented submissions on existing reconciliation ticks with no retry cap; observed presentation ends retries and later ordinary sleep does not self-wake")
    val d2=d.copy(wakeRevision=2);feature.accept(ActivationReply(d2))
    c.failLaunch=true;wake.request(d2);check(c.power.locks.none{it.isHeld})
    c.failLaunch=false;wake.retryPending();check(c.launches.size==5)
    check(c.launches.last().extras[ReceiverWake.ACTIVATION]=="H" && c.launches.last().extras[ReceiverWake.REVISION]==2L)
    println("PASS actual Wake exception remains retryable and every operation releases its screen lock")
    val projected=d2.copy(wakeRevision=3,goal=ActivationGoal.PROJECTED)
    feature.accept(ActivationReply(projected));wake.request(projected)
    val original=feature.demand()!!
    check(ReceiverWake.readIntent(c.launches.last())==original)
    feature.report(feature.demand()!!,3,ReceiverUsabilityMode.LOCAL_PANEL,"LOCAL_PANEL");wake.reconcile(feature.presentation,false);wake.retryPending()
    check(c.launches.size==7 && wake.outcome!="PRESENTATION_OBSERVED")
    feature.report(feature.demand()!!,4,ReceiverUsabilityMode.PROJECTED,"PROJECTED");wake.reconcile(feature.presentation,false);wake.retryPending()
    check(c.launches.size==7 && wake.outcome=="PRESENTATION_OBSERVED")
    val next=projected.copy(ownerEpoch="next-A")
    feature.accept(ActivationReply(next));wake.request(next)
    check(c.launches.size==8 && ReceiverWake.readIntent(c.launches.last())==feature.demand())
    check(ReceiverWake.readIntent(c.launches.last())!=original)
    println("PASS actual PROJECTED wake requires matching projected mode; Intent binds H/A/revision/incarnation/goal and a changed attempt is new demand")
    val d3=ActivationDirective("H2",true,1,"B");feature.accept(ActivationReply(d3))
    wake.request(d);check(c.launches.size==8)
    wake.request(d3);check(c.launches.size==9)
    feature.cancel(scope,"H2");wake.reconcile(feature.presentation,false);wake.retryPending();check(c.launches.size==9 && c.power.locks.none{it.isHeld})
    println("PASS actual Wake uses exact current directive/incarnation; stale or canceled H cannot restart another owner")
}
