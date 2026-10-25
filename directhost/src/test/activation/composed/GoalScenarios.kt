package com.code2hack.eyeprojector.directhost.activation

import android.content.Context
import android.content.Environment
import android.os.MainQueue
import android.view.InputDevice
import android.view.KeyEvent
import com.code2hack.eyeprojector.directhost.*
import com.code2hack.eyeprojector.directhost.hid.*
import com.code2hack.eyeprojector.directhost.input.PhoneInputMerger
import com.code2hack.eyeprojector.protocol.*
import java.util.concurrent.*

private fun goalAwait(message:String,test:()->Boolean){
 val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(4)
 while(!test() && System.nanoTime()<end){MainQueue.drain();Thread.yield()};check(test()){message}
}
internal fun projectedDemandScenarios(){
 Environment.filesDir=java.nio.file.Files.createTempDirectory("goal-facade-").toFile()
 PhoneSessionController::class.java.getDeclaredField("instance").apply{isAccessible=true}.set(null,null)
 PhoneHidRuntime::class.java.getDeclaredField("instance").apply{isAccessible=true}.set(null,null)
 val b=HostBootstrap().also{it.value=HostBootstrap.Status(HostBootstrap.Setup.AVAILABLE,"STOPPED")};HostBootstrap.current=b
 val s=PhoneSessionController.get(Context());val writer=lane(s)
 val runtime=PhoneHidRuntime.get(Context()).also { it.controller.activation=historicalHidPort(s) };val service=PhoneHidKeyService();service.onServiceConnected()
 val command=s.javaClass.getDeclaredMethod("command",SessionControl.Request::class.java).apply{isAccessible=true}
 fun request(r:SessionControl.Request)=command.invoke(s,r) as SessionState
 fun directive()=s.state().activation!!.directive!!
 val inc=ReceiverIncarnation("goal-facade",1);var revision=0L
 fun report(mode:ReceiverUsabilityMode,wake:Long=directive().wakeRevision){
  val d=directive();request(SessionControl.Request(SessionControl.Operation.STATUS,incarnation=inc,
   presentation=PresentationReport(d.activationId,d.ownerEpoch,wake,inc,++revision,mode)));MainQueue.drain()
 }
 val device=InputDevice(81,"goal-device",11,12,"Goal fixture")
 val key=211;val scan=811
 val ui=mutableListOf<ReceiverInput>();val touches=mutableListOf<Touch>();val size=Geometry(100,100)
 val merger=PhoneInputMerger(Any(),{1000},{_,_->{}},{size},{touches.add(it);true},{true},{_,_->},{},{ui.add(it)})
 b.merger=merger
 fun edge(at:Long,down:Long=at,phase:Int=KeyEvent.ACTION_DOWN,k:Int=key,sc:Int=scan)=service.onKeyEvent(KeyEvent(device,phase,k,sc,down,at))
 fun ready(handle:ActivationHandle):ActivationReady<ProjectionContext>{
  var value:ActivationReady<ProjectionContext>?=null;s.whenActivationReady(handle){value=it};MainQueue.drain();return checkNotNull(value)
 }
 try {
  goalAwait("STOPPED resolved"){s.state().phase==SessionPhase.INACTIVE}
  val h=s.requestActivation(ActivationCause.HID_ATTACHMENT)
  goalAwait("one original backend A"){b.starts.size==1};val a=b.starts.single()
  b.publish(HostBootstrap.Status(HostBootstrap.Setup.AVAILABLE,"STREAMING",sessionEpoch=a,projectionToken="goal-A"))
  goalAwait("A current source observed"){s.state().phase==SessionPhase.CONNECTING && directive().ownerEpoch==a}
  request(SessionControl.Request(SessionControl.Operation.STATUS,incarnation=inc))
  report(ReceiverUsabilityMode.LOCAL_PANEL)
  val generic=ready(h);check(generic.handle.demandId==null && !s.needsActivation())
  runtime.controller.bindings.assign(PhoneHidAction.TAP,PhoneHidBinding(
   PhoneHidButton(PhoneHidDeviceIdentity(device.descriptor,device.vendorId,device.productId),key,scan),device.name,"tap"))
  merger.pointer(HeadPointerState(1,1,true,false,20f,30f,null,null))
  check(edge(1000));b.drainInput()
  val blocked=CountDownLatch(1);val release=CountDownLatch(1)
  writer.execute{blocked.countDown();check(release.await(3,TimeUnit.SECONDS))};check(blocked.await(1,TimeUnit.SECONDS))
  val old=directive();val d=s.requestActivation(ActivationCause.NOTIFICATION)
  var completion:ActivationReady<ProjectionContext>?=null
  val subscription=s.whenActivationReady(d){completion=it}
  MainQueue.drain()
  check(d.id==h.id && d.demandId!=null && d.goal==ActivationGoal.PROJECTED && s.isActivationCurrent(h))
  check(completion==null && !s.needsActivation())
  check(!s.tryCommitActivationReady(generic,LaunchCommitPermit()))
  check(edge(1020,1000,KeyEvent.ACTION_UP));b.drainInput()
  check(ui.map{it.kind}==listOf(ReceiverInputKind.DOWN,ReceiverInputKind.UP) && touches.isEmpty())
  check(!edge(1100,k=key+1,sc=scan+1));check(!edge(1120,1100,KeyEvent.ACTION_UP,k=key+1,sc=scan+1))
  release.countDown()
  goalAwait("PROJECTED original D assigned"){directive().goal==ActivationGoal.PROJECTED && directive().wakeRevision>old.wakeRevision}
  check(b.starts.size==1 && directive().ownerEpoch==a && !s.needsActivation())
  report(ReceiverUsabilityMode.LOCAL_PANEL);check(completion==null && !s.needsActivation())
  val current=directive();s.requestActivation(ActivationCause.HID_PRESS);writer.submit{}.get(2,TimeUnit.SECONDS)
  check(directive()==current && s.isActivationCurrent(d))
  report(ReceiverUsabilityMode.PROJECTED,old.wakeRevision);check(completion==null)
  report(ReceiverUsabilityMode.PROJECTED)
  val first=checkNotNull(completion)
  check(first.handle===d && first.binding!!.demandId==d.demandId && first.binding.goal==ActivationGoal.PROJECTED && first.attempt.id==a)
  check(first.target==ProjectionContext(a,"goal-A") && first.binding.wakeRevision==directive().wakeRevision)
  check(b.starts.size==1 && b.displayCalls.isEmpty())
  println("PASS actual facade/HID/merger keeps LOCAL_PANEL held/unassigned input normal while original PROJECTED D waits; no old generic Ready or app selection")

  val committed=LaunchCommitPermit();check(s.tryCommitActivationReady(first,committed));check(!s.tryCommitActivationReady(first,committed));check(!committed.retire())
  val newer=s.requestActivation(ActivationCause.NOTIFICATION)
  var newerReady:ActivationReady<ProjectionContext>?=null;s.whenActivationReady(newer){newerReady=it};MainQueue.drain()
  check(newer.id==d.id && newer.demandId!=d.demandId && newerReady==null && !s.isActivationCurrent(d))
  check(!s.tryCommitActivationReady(first,LaunchCommitPermit()) && s.isActivationCurrent(h))
  writer.submit{}.get(2,TimeUnit.SECONDS);report(ReceiverUsabilityMode.PROJECTED)
  val second=checkNotNull(newerReady);check(second.handle===newer && second.authority!=first.authority)
  subscription.close();check(s.isActivationCurrent(newer) && b.stops==0)
  report(ReceiverUsabilityMode.NONE)
  check(!s.tryCommitActivationReady(second,LaunchCommitPermit()))
  report(ReceiverUsabilityMode.PROJECTED)
  val restored=ready(newer)
  check(restored.authority!=second.authority && !s.tryCommitActivationReady(second,LaunchCommitPermit()))
  println("PASS actual D supersession and NONE/PROJECTED ABA never reuse original Ready authority; closing consumers preserves shared H")

  val retry=s.requestActivation(ActivationCause.NOTIFICATION)
  var retryReady:ActivationReady<ProjectionContext>?=null
  val states=mutableListOf<ActivationState<ProjectionContext>>()
  val observing=s.observeActivation(retry){states.add(it)}
  s.whenActivationReady(retry){retryReady=it};writer.submit{}.get(2,TimeUnit.SECONDS)
  report(ReceiverUsabilityMode.LOCAL_PANEL);check(retryReady==null)
  b.publish(HostBootstrap.Status(HostBootstrap.Setup.AVAILABLE,"FAILED",sessionEpoch=a))
  goalAwait("failed A awaits actual release"){b.stops>0};check(b.starts.size==1 && s.isActivationCurrent(retry))
  b.publish(HostBootstrap.Status(HostBootstrap.Setup.AVAILABLE,"STOPPED",sessionEpoch=a))
  goalAwait("same D admits B only after release"){b.starts.size==2};val secondA=b.starts.last()
  b.publish(HostBootstrap.Status(HostBootstrap.Setup.AVAILABLE,"STREAMING",sessionEpoch=secondA,projectionToken="goal-B"))
  goalAwait("B context observed"){s.state().phase==SessionPhase.CONNECTING && directive().ownerEpoch==secondA}
  report(ReceiverUsabilityMode.PROJECTED)
  val after=checkNotNull(retryReady)
  check(after.handle===retry && after.target==ProjectionContext(secondA,"goal-B") && after.attempt.id==secondA)
  check(states.any{it.binding?.attemptId==a} && states.any{it.binding?.attemptId==secondA})
  check(first.target==ProjectionContext(a,"goal-A") && first.attempt.id==a && !s.tryCommitActivationReady(first,LaunchCommitPermit()))
  println("PASS actual original D remains pending through A release/B retry and publishes new immutable binding without retargeting old Ready")

  val pool=Executors.newFixedThreadPool(3)
  try {
   repeat(20){
    val permit=LaunchCommitPermit();val barrier=CyclicBarrier(3)
    val left=pool.submit<Boolean>{barrier.await();s.tryCommitActivationReady(after,permit)}
    val right=pool.submit<Boolean>{barrier.await();s.tryCommitActivationReady(after,permit)}
    val retired=pool.submit<Boolean>{barrier.await();permit.retire()}
    val commits=listOf(left.get(1,TimeUnit.SECONDS),right.get(1,TimeUnit.SECONDS)).count{it};val revoked=retired.get(1,TimeUnit.SECONDS)
    check(commits+(if(revoked)1 else 0)==1)
    check(permit.state==if(revoked)LaunchCommitPermit.State.RETIRED else LaunchCommitPermit.State.COMMITTED)
   }
  } finally {pool.shutdownNow()}
  val fast=s.javaClass.getDeclaredField("fastLock").apply{isAccessible=true}.get(s)
  val noLock=s.observeActivation(retry){check(!Thread.holdsLock(fast))};MainQueue.drain();noLock.close()
  val firstWins=LaunchCommitPermit();check(s.tryCommitActivationReady(after,firstWins))
  val hold=CountDownLatch(1);val go=CountDownLatch(1)
  writer.execute{hold.countDown();check(go.await(3,TimeUnit.SECONDS))};check(hold.await(1,TimeUnit.SECONDS))
  val stops=b.stops;s.stop()
  check(!s.tryCommitActivationReady(after,LaunchCommitPermit()) && firstWins.state==LaunchCommitPermit.State.COMMITTED && !firstWins.retire())
  check(b.stops==stops)
  go.countDown();observing.close()
  println("PASS actual facade typed permit has one commit/retire winner; Stop invalidates before blocked writer/backend effects and callbacks run outside fast lock")
 } finally {
  service.onDestroy();MainQueue.drain();writer.shutdownNow();writer.awaitTermination(2,TimeUnit.SECONDS)
  val hidWriter=runtime.javaClass.getDeclaredField("writer").apply{isAccessible=true}.get(runtime) as ExecutorService
  hidWriter.shutdownNow();hidWriter.awaitTermination(2,TimeUnit.SECONDS);MainQueue.drain()
 }
}
