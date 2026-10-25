package com.code2hack.eyeprojector.directhost.activation.producer

import android.content.Context
import android.os.Handler
import android.util.AtomicFile
import com.code2hack.eyeprojector.directhost.*
import com.code2hack.eyeprojector.directhost.activation.*
import com.code2hack.eyeprojector.protocol.*
import rikka.shizuku.Shizuku
import java.util.concurrent.*

private fun field(o:Any,name:String)=o.javaClass.getDeclaredField(name).apply{isAccessible=true}
private fun await(message:String,pump:Boolean=true,test:()->Boolean){
 val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(4)
 while(!test() && System.nanoTime()<end){if(pump)Handler.flush();Thread.yield()};check(test()){message}
}
internal class Fixture:AutoCloseable {
 val directory=java.nio.file.Files.createTempDirectory("actual-producer-").toFile()
 val context=Context(directory)
 val service=FakeService().apply{state="STREAMING";replaceProjection("original-A")}
 val sessions:PhoneSessionController
 val bootstrap:HostBootstrap
 val lane:ScheduledExecutorService
 val hostLane:ScheduledExecutorService
 private var reportRevision=0L
 private var nativeSlot:SessionUsability?=null
 val inc=ReceiverIncarnation("producer-receiver",1)
 init {
  Shizuku.reset();Shizuku.peekService=service;Shizuku.peekVersion=123
  Handler.defer=true
  HostBootstrap::class.java.getDeclaredField("instance").apply{isAccessible=true}.set(null,null)
  PhoneSessionController::class.java.getDeclaredField("instance").apply{isAccessible=true}.set(null,null)
  directory.resolve("session").mkdirs()
  for(name in listOf("identity.p12","password","peer.crt","endpoint.properties"))directory.resolve("session/$name").writeText("SYNTHETIC_FIXTURE_ONLY")
  sessions=PhoneSessionController.get(context);bootstrap=sessions.bootstrap
  lane=field(sessions,"featureLane").get(sessions) as ScheduledExecutorService
  hostLane=field(bootstrap,"executor").get(bootstrap) as ScheduledExecutorService
  await("actual Bootstrap attached running fixture"){bootstrap.status().projectionContext==service.context}
  sessions.requestActivation(ActivationCause.HID_ATTACHMENT)
  await("feature H adopts actual already-owned A"){directiveOrNull()?.ownerEpoch=="original-A"}
 }
 fun directiveOrNull()=sessions.state().activation?.directive
 fun directive()=directiveOrNull()!!
 fun request(r:SessionControl.Request):SessionState = sessions.javaClass.getDeclaredMethod("command",SessionControl.Request::class.java).apply{isAccessible=true}.invoke(sessions,r) as SessionState
 fun report(mode:ReceiverUsabilityMode):SessionState {
  lane.submit{}.get(2,TimeUnit.SECONDS)
  val d=directive()
  return request(SessionControl.Request(SessionControl.Operation.STATUS,incarnation=inc,
   presentation=PresentationReport(d.activationId,d.ownerEpoch,d.wakeRevision,inc,++reportRevision,mode)))
 }
 fun nativeLocal(runtime:com.code2hack.eyeprojector.directhost.hid.PhoneHidRuntime) {
  val slot=SessionUsability(service.context);slot.prepareRegion()
  slot.publish(ReceiverUsability(service.sessionEpoch,inc,1,1,ReceiverUsabilityMode.LOCAL_PANEL))
  nativeSlot=slot;service.snapshotProvider=slot::descriptor
  nativeAwaitReady(runtime)
 }
 fun nativeAwaitReady(runtime:com.code2hack.eyeprojector.directhost.hid.PhoneHidRuntime) {
  await("actual original reader installed") {runtime.controller.activation.capture().disposition==PhoneHidDisposition.USABLE}
 }
 fun ready():ActivationReady<ProjectionContext> {
  val h=sessions.requestActivation(ActivationCause.NOTIFICATION)
  lane.submit{}.get(2,TimeUnit.SECONDS)
  report(ReceiverUsabilityMode.PROJECTED);Handler.flush()
  var ready:ActivationReady<ProjectionContext>?=null
  sessions.whenActivationReady(h){ready=it};Handler.flush()
  return checkNotNull(ready){"Original projected D did not become Ready: ${sessions.state()}"}
 }
 override fun close(){
  nativeSlot?.closeResources()
  AtomicFile.beforeStart=null;service.statusGate?.countDown();service.stopGate?.countDown()
  @Suppress("UNCHECKED_CAST")
  (field(bootstrap,"observers").get(bootstrap) as MutableSet<(HostBootstrap.Status)->Unit>).clear()
  (field(sessions,"contextRegistration").get(sessions) as java.io.Closeable).close()
  (field(sessions,"retiredWitnessRegistration").get(sessions) as java.io.Closeable).close()
  Handler.flush();lane.shutdownNow();lane.awaitTermination(2,TimeUnit.SECONDS)
  for(name in listOf("executor","displayExecutor","inputExecutor","scaleExecutor","panelExecutor","panelQueryExecutor"))runCatching{
   (field(bootstrap,name).get(bootstrap) as ExecutorService).apply{shutdownNow();awaitTermination(2,TimeUnit.SECONDS)}
  };Handler.flush();Shizuku.reset()
 }
}
fun main(){
 inputAuthorityRegression()
 requestMerge57Scenarios()
 if (System.getProperty("eyeprojector.transport")=="true") Class.forName("com.code2hack.eyeprojector.directhost.activation.producer.TransportScenariosKt").getMethod("transportFrontendScenarios").invoke(null)
 Fixture().use{f->
  val ready=f.ready();check(f.sessions.tryCommitActivationReady(ready,LaunchCommitPermit()))
  f.bootstrap.stop()
  check(!f.sessions.tryCommitActivationReady(ready,LaunchCommitPermit()))
  println("PASS actual Bootstrap Stop retires original Ready synchronously while main observer delivery is held")
 }
 Fixture().use{f->
  val ready=f.ready();val gate=CountDownLatch(1);val entered=CountDownLatch(1)
  AtomicFile.beforeStart={file->if(file.name=="bootstrap-status.json"){entered.countDown();check(gate.await(4,TimeUnit.SECONDS))}}
  f.service.state="STOP_UNVERIFIED";f.bootstrap.refresh()
  check(entered.await(2,TimeUnit.SECONDS))
  var delivered=false
  val watching=f.sessions.whenActivationReady(ready.handle){delivered=true}
  val fast=(field(f.sessions,"contextAuthority").get(f.sessions) as ActivationContextAuthority).lock
  val gateLock=(field(f.bootstrap,"contextPublisher").get(f.bootstrap) as ActivationContextPublisher).gate
  val observation=f.sessions.observeActivation(ready.handle){check(!Thread.holdsLock(fast) && !Thread.holdsLock(gateLock));check(it.phase!=ActivationPhase.READY)}
  Handler.flush();check(!delivered)
  val reads=f.service.statusReads;val writes=AtomicFile.starts
  val worker=Executors.newSingleThreadExecutor()
  try{check(!worker.submit<Boolean>{f.sessions.tryCommitActivationReady(ready,LaunchCommitPermit())}.get(1,TimeUnit.SECONDS))}
  finally{check(reads==f.service.statusReads && writes==AtomicFile.starts);watching.close();observation.close();AtomicFile.beforeStart=null;gate.countDown();worker.shutdownNow()}
  println("PASS actual readStatus learns loss and invalidates fast authority BEFORE blocked status-file persistence or queued main observation")
 }
 Fixture().use{f->
  val ready=f.ready();val captured=CountDownLatch(1);val reply=CountDownLatch(1)
  f.service.statusEntered=captured;f.service.statusGate=reply;f.bootstrap.refresh()
  check(captured.await(2,TimeUnit.SECONDS))
  val stop=CountDownLatch(1);f.service.stopGate=stop
  f.bootstrap.stop() // Invalidates the original captured status before its queued Stop can run.
  check(!f.sessions.tryCommitActivationReady(ready,LaunchCommitPermit()))
  reply.countDown();check(f.service.stopEntered.await(2,TimeUnit.SECONDS))
  check(!f.sessions.tryCommitActivationReady(ready,LaunchCommitPermit()))
  val authority=field(f.sessions,"contextAuthority").get(f.sessions) as ActivationContextAuthority
  synchronized(authority.lock){check(authority.fact?.eligible==false)}
  stop.countDown()
  println("PASS actual in-flight positive Binder status cannot rearm its original binding after synchronous Stop; queued backend Stop remains independent")
 }
 Fixture().use{f->
  val ready=f.ready();val paused=CountDownLatch(1);val resume=CountDownLatch(1)
  val local=field(f.sessions,"localRevision").get(f.sessions) as java.util.concurrent.atomic.AtomicLong
  val oldRevision=local.get()
  val publication=f.lane.submit {
   paused.countDown();check(resume.await(4,TimeUnit.SECONDS))
   f.sessions.javaClass.getDeclaredMethod("publishActivation",Long::class.javaPrimitiveType).apply{isAccessible=true}.invoke(f.sessions,oldRevision)
  }
  check(paused.await(2,TimeUnit.SECONDS))
  Shizuku.available=false;f.bootstrap.refresh();f.hostLane.submit{}.get(2,TimeUnit.SECONDS)
  check(!f.sessions.tryCommitActivationReady(ready,LaunchCommitPermit()))
  var replacement:ActivationReady<ProjectionContext>?=null
  f.sessions.whenActivationReady(ready.handle){replacement=it};Handler.flush();check(replacement==null)
  Shizuku.available=true;f.bootstrap.refresh();f.hostLane.submit{}.get(2,TimeUnit.SECONDS)
  check(f.bootstrap.status().projectionContext==ready.target)
  check(!f.sessions.tryCommitActivationReady(ready,LaunchCommitPermit()))
  resume.countDown();publication.get(2,TimeUnit.SECONDS)
  check(!f.sessions.tryCommitActivationReady(ready,LaunchCommitPermit()))
  val projected=field(f.sessions,"projectedActivation").get(f.sessions) as ActivationState<*>
  check(projected.authority==0L || projected.authority==ready.authority) // Retired or unchanged, never re-stamped by the stale publisher.
  Handler.flush();f.lane.submit{}.get(2,TimeUnit.SECONDS)
  Handler.flush();check(replacement==null)
  f.report(ReceiverUsabilityMode.PROJECTED);Handler.flush()
  check(checkNotNull(replacement).authority!=ready.authority)
  check(!f.sessions.tryCommitActivationReady(ready,LaunchCommitPermit()))
  println("PASS actual producer equal-context loss/restore while main is held rejects old Ready and paused actual feature publication; fresh revision report alone restores new authority")
 }
 Fixture().use{f->
  val ready=f.ready()
  val first=ActivationContextAuthority();val old=f.bootstrap.registerActivationAuthority(first)
  val second=ActivationContextAuthority();val current=f.bootstrap.registerActivationAuthority(second)
  old.close()
  synchronized(first.lock){check(first.fact==null)}
  synchronized(second.lock){check(second.fact?.context==ready.target && second.fact?.eligible==true)}
  val workers=Executors.newSingleThreadExecutor()
  var active:java.io.Closeable=current
  try {
   repeat(10){
    val next=ActivationContextAuthority();val barrier=CyclicBarrier(2)
    val registration=workers.submit<java.io.Closeable>{barrier.await();f.bootstrap.registerActivationAuthority(next)}
    f.hostLane.submit {barrier.await();f.service.replaceProjection("original-A");f.bootstrap.refresh()}.get(2,TimeUnit.SECONDS)
    val installed=registration.get(2,TimeUnit.SECONDS);f.hostLane.submit{}.get(2,TimeUnit.SECONDS)
    active.close();active=installed
    synchronized(next.lock){check(next.fact?.context==f.bootstrap.status().projectionContext && next.fact?.eligible==true)}
   }
  } finally {
   workers.shutdownNow();active.close()
   val own=field(f.sessions,"contextAuthority").get(f.sessions) as ActivationContextAuthority
   field(f.sessions,"contextRegistration").set(f.sessions,f.bootstrap.registerActivationAuthority(own))
  }
  check(!f.sessions.tryCommitActivationReady(ready,LaunchCommitPermit()))
  println("PASS actual Bootstrap registration/publication races seed coherent current facts; closing original registration never detaches its successor")
 }
 Fixture().use{f->
  val ready=f.ready();val replacement=FakeService().apply{state="STREAMING";replaceProjection("original-A")}
  Shizuku.connection!!.onServiceConnected(Shizuku.args!!.component,replacement)
  f.hostLane.submit{}.get(2,TimeUnit.SECONDS)
  check(f.bootstrap.status().projectionContext==replacement.context)
  val own=field(f.sessions,"contextAuthority").get(f.sessions) as ActivationContextAuthority
  val before=synchronized(own.lock){own.version}
  f.service.die() // Exact original binding captured by actual linkToDeath, after successor installation.
  f.hostLane.submit{}.get(2,TimeUnit.SECONDS)
  synchronized(own.lock){check(own.version==before && own.fact?.eligible==true && own.fact?.context==replacement.context)}
  check(!f.sessions.tryCommitActivationReady(ready,LaunchCommitPermit()))
  println("PASS actual old service death callback cannot retire a newer installed backend binding/context")
 }

 Fixture().use{f->
  val publisher=field(f.bootstrap,"contextPublisher").get(f.bootstrap) as ActivationContextPublisher
  val original=publisher.capture()
  val replacement=FakeService().apply{state="STREAMING";replaceProjection("successor-A")}
  Shizuku.connection!!.onServiceConnected(Shizuku.args!!.component,replacement)
  f.hostLane.submit{}.get(2,TimeUnit.SECONDS);Handler.flush();f.lane.submit{}.get(2,TimeUnit.SECONDS);Handler.flush()
  val target=f.sessions.launchAppForScale("fixture.app",replacement.context){}
  check(f.sessions.appScales.state()?.target==target)
  val seen=mutableListOf<HostBootstrap.Status>();val witnesses=mutableListOf<HostBootstrap.Status>()
  val observer:(HostBootstrap.Status)->Unit={seen.add(it)};f.bootstrap.observe(observer);Handler.flush();seen.clear()
  val witness=f.bootstrap.observeRetiredBackendWitness{witnesses.add(it)}
  try {
   f.hostLane.submit {
    f.bootstrap.javaClass.getDeclaredMethod("readStatus",String::class.java,ActivationContextPublisher.Observation::class.java).apply{isAccessible=true}
     .invoke(f.bootstrap,"{\"state\":\"STOPPED\",\"sessionEpoch\":\"original-A\"}",original)
   }.get(2,TimeUnit.SECONDS)
   Handler.flush()
   check(witnesses.single().sessionEpoch=="original-A" && witnesses.single().backend=="STOPPED")
   check(seen.none{it.sessionEpoch=="original-A" && it.backend=="STOPPED"})
   check(f.bootstrap.status().projectionContext==replacement.context && f.sessions.appScales.state()?.target==target)
  } finally {f.bootstrap.removeObserver(observer);witness.close()}
  println("PASS actual delayed original terminal witness reaches only the ownership ledger, preserving successor UI/scale/current authority")
 }

 Fixture().use{f->
  val ready=f.ready();val old=f.service.context;val stopCount=f.service.stops;val startCount=f.service.starts
  val gate=CountDownLatch(1);val entered=CountDownLatch(1)
  AtomicFile.beforeStart={file->if(file.name=="bootstrap-status.json"){entered.countDown();check(gate.await(4,TimeUnit.SECONDS))}}
  f.service.replaceProjection("original-A");f.bootstrap.refresh();check(entered.await(2,TimeUnit.SECONDS))
  var current:ActivationReady<ProjectionContext>?=null
  val observing=f.sessions.whenActivationReady(ready.handle){current=it};Handler.flush()
  check(current==null && !f.sessions.tryCommitActivationReady(ready,LaunchCommitPermit()))
  check(f.bootstrap.status().projectionContext==old) // Cached current is held behind disk; primitive loss is already known.
  AtomicFile.beforeStart=null;gate.countDown();f.hostLane.submit{}.get(2,TimeUnit.SECONDS)
  Handler.flush();f.lane.submit{}.get(2,TimeUnit.SECONDS);Handler.flush()
  check(current==null);f.report(ReceiverUsabilityMode.PROJECTED);Handler.flush()
  val replacement=checkNotNull(current)
  check(replacement.handle===ready.handle && replacement.attempt===ready.attempt && replacement.target==f.service.context && replacement.target!=old)
  check(replacement.authority!=ready.authority && !f.sessions.tryCommitActivationReady(ready,LaunchCommitPermit()))
  check(f.service.stops==stopCount && f.service.starts==startCount);observing.close()
  println("PASS actual eligible same-Binder/A token replacement retires original Ready before disk/current/main; same D/A requires fresh matching presentation without fake release")
 }

}
