package com.code2hack.eyeprojector.directhost.activation.producer

import android.os.Handler
import com.code2hack.eyeprojector.directhost.activation.*
import com.code2hack.eyeprojector.protocol.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

private fun mergeField(o:Any,n:String)=o.javaClass.getDeclaredField(n).apply{isAccessible=true}
private fun pending(f:Fixture):Any?=(mergeField(f.sessions,"pendingRequest").get(f.sessions) as AtomicReference<*>).get()
private fun wakeOf(value:Any?)=value?.let{mergeField(it,"hidWake").get(it)} as HidWakeRequest?
private fun demandOf(value:Any?)=value?.let{mergeField(it,"handle").get(it)} as ActivationHandle?
private fun engine(f:Fixture)=mergeField(f.sessions,"activation").get(f.sessions) as PhoneActivationEngine
private fun loss(f:Fixture):HidWakeRequest {
 val scope=f.sessions.captureHidAuthority();check(scope.context.available && scope.attemptId!=null)
 val installed=checkNotNull(f.sessions.installHidRegion(scope,"merge-region"))
 return HidWakeRequest(PhoneHidDisposition.NONE,installed,"merge-region",4L)
}
private fun unknown(f:Fixture)=HidWakeRequest(PhoneHidDisposition.UNKNOWN,f.sessions.captureHidAuthority(),setupReason=HidSetupReason.UNESTABLISHED)
internal fun requestMerge57Scenarios(){
 for(lossFirst in listOf(false,true))Fixture().use{f->
  f.ready();f.report(ReceiverUsabilityMode.LOCAL_PANEL);Handler.flush()
  val original=loss(f);val before=f.directive().wakeRevision
  val entered=CountDownLatch(1);val release=CountDownLatch(1)
  f.lane.execute{entered.countDown();check(release.await(4,TimeUnit.SECONDS))};check(entered.await(1,TimeUnit.SECONDS))
  try {
   if(lossFirst)check(f.sessions.requestHidActivation(original)!=null)
   val d=f.sessions.requestActivation(ActivationCause.NOTIFICATION)
   if(!lossFirst)check(f.sessions.requestHidActivation(original)!=null)
   // Resource/setup churn is not cancellation of the already admitted original NONE obligation.
   f.sessions.retireHidRegion(original.authority)
   check(f.sessions.requestHidActivation(unknown(f))!=null)
   val slot=pending(f);check(demandOf(slot)===d && wakeOf(slot)?.disposition==PhoneHidDisposition.NONE && wakeOf(slot)?.admitted==true)
  } finally {release.countDown()}
  f.lane.submit{}.get(2,TimeUnit.SECONDS);Handler.flush();f.lane.submit{}.get(2,TimeUnit.SECONDS)
  check(f.directive().wakeRevision>before && f.directive().goal==ActivationGoal.PROJECTED)
  check(engine(f).inputPresentation==null && f.sessions.needsActivation() && f.service.inputCalls.isEmpty())
  println("PASS actual helper/actor lossFirst=$lossFirst retains latest D and admitted NONE through later UNKNOWN/region clear; durable original directive/no replay")
 }
 Fixture().use{f->
  f.ready();f.report(ReceiverUsabilityMode.LOCAL_PANEL);Handler.flush();val original=loss(f)
  val feature=engine(f);val writeField=mergeField(feature,"write")
  @Suppress("UNCHECKED_CAST") val write=writeField.get(feature) as (ActivationJournal)->Unit
  val failOnce=AtomicBoolean(true);val entered=CountDownLatch(1);val release=CountDownLatch(1)
  var d1:ActivationHandle?=null
  writeField.set(feature,{journal:ActivationJournal->
   if(journal.current?.demandId==d1?.demandId && failOnce.compareAndSet(true,false)) {
    entered.countDown();check(release.await(4,TimeUnit.SECONDS));throw java.io.IOException("explicit persistence fixture")
   };write(journal)
  })
  val hold=CountDownLatch(1);val go=CountDownLatch(1)
  f.lane.execute{hold.countDown();check(go.await(3,TimeUnit.SECONDS))};check(hold.await(1,TimeUnit.SECONDS))
  check(f.sessions.requestHidActivation(original)!=null);d1=f.sessions.requestActivation(ActivationCause.NOTIFICATION);go.countDown()
  check(entered.await(2,TimeUnit.SECONDS));check(pending(f)==null)
  // D1 is in flight: the projected priority comes from currentDemand, not just the empty slot.
  check(f.sessions.requestHidActivation(unknown(f))!=null);check(demandOf(pending(f))===d1)
  val d2=f.sessions.requestActivation(ActivationCause.NOTIFICATION);check(demandOf(pending(f))===d2)
  release.countDown();f.lane.submit{}.get(2,TimeUnit.SECONDS);Handler.flush();f.lane.submit{}.get(2,TimeUnit.SECONDS);Handler.flush()
  check(feature.journal.current?.demandId==d2.demandId && !f.sessions.isActivationCurrent(d1!!))
  check(feature.inputPresentation==null && f.sessions.needsActivation() && f.service.inputCalls.isEmpty())
  writeField.set(feature,write)
  println("PASS actual in-flight D1/empty-slot weaker HID + D2 + failed persistence requeues original H loss into D2 without old-D predicate/CAS loss")
 }
 Fixture().use{f->
  f.ready();f.report(ReceiverUsabilityMode.LOCAL_PANEL);Handler.flush()
  val original=loss(f);val before=f.directive().wakeRevision
  val entered=CountDownLatch(1);val take=CountDownLatch(1);val selected=CountDownLatch(1);val apply=CountDownLatch(1)
  val work=f.lane.submit {
   entered.countDown();check(take.await(4,TimeUnit.SECONDS))
   @Suppress("UNCHECKED_CAST") val slot=mergeField(f.sessions,"pendingRequest").get(f.sessions) as AtomicReference<Any?>
   // Controlled dequeue/pause at the real actor's one-slot boundary; all effects use its actual method.
   val old=checkNotNull(slot.getAndSet(null));selected.countDown();check(apply.await(4,TimeUnit.SECONDS))
   f.sessions.javaClass.declaredMethods.single{it.name=="applyLocalRequest"}.apply{isAccessible=true}.invoke(f.sessions,old)
  }
  check(entered.await(1,TimeUnit.SECONDS));check(f.sessions.requestHidActivation(original)!=null)
  val d1=f.sessions.requestActivation(ActivationCause.NOTIFICATION);take.countDown();check(selected.await(2,TimeUnit.SECONDS))
  val d2=f.sessions.requestActivation(ActivationCause.NOTIFICATION);check(demandOf(pending(f))===d2)
  apply.countDown();work.get(2,TimeUnit.SECONDS);f.lane.submit{}.get(2,TimeUnit.SECONDS);Handler.flush()
  check(!f.sessions.isActivationCurrent(d1) && f.sessions.isActivationCurrent(d2))
  check(engine(f).journal.current?.demandId==d2.demandId && f.directive().wakeRevision>before)
  check(engine(f).inputPresentation==null && f.service.inputCalls.isEmpty())
  println("PASS actual NORMAL old-D actor skip after selected D1/new pending D2 still applies original H loss and advances durable directive; no exception credit")
 }
 Fixture().use{f->
  f.ready();val original=loss(f);f.sessions.stop()
  check(f.sessions.requestHidActivation(original)==null)
  val noOwner=f.sessions.captureHidAuthority();check(noOwner.handle==null)
  val h2=f.sessions.requestActivation(ActivationCause.PHONE_START)
  check(f.sessions.requestHidActivation(HidWakeRequest(PhoneHidDisposition.UNKNOWN,noOwner))==null)
  check(f.sessions.requestHidActivation(HidWakeRequest(PhoneHidDisposition.UNKNOWN,f.sessions.captureHidAuthority()),original.authority)==null)
  check(f.sessions.isActivationCurrent(h2))
  println("PASS actual original H and no-owner/revision capture cannot admit after Stop/new H2")
 }
}
