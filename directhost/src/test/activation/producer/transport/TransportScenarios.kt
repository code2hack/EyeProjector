package com.code2hack.eyeprojector.directhost.activation.producer

import android.os.Handler
import android.view.InputDevice
import android.view.KeyEvent
import com.code2hack.eyeprojector.directhost.*
import com.code2hack.eyeprojector.directhost.activation.*
import com.code2hack.eyeprojector.directhost.activation.snapshot.*
import com.code2hack.eyeprojector.directhost.hid.*
import com.code2hack.eyeprojector.protocol.*
import java.util.concurrent.*

private fun method(o:Any,n:String,vararg args:Any?)=o.javaClass.declaredMethods.single{it.name==n}.apply{isAccessible=true}.invoke(o,*args)
private fun member(o:Any,n:String)=o.javaClass.getDeclaredField(n).apply{isAccessible=true}.get(o)
private fun waitFor(label:String,test:()->Boolean){val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);while(!test()&&System.nanoTime()<end){Handler.flush();Thread.sleep(2)};check(test()){label}}
private class TransportRig(val f:Fixture,val backend:PanelFixture):AutoCloseable {
 val session=backend.session as CapturedProjection
 val device=InputDevice(42,"transport-key",7,8,"External fixture")
 val runtime:PhoneHidRuntime
 val ingress:PhoneHidKeyService
 var revision=0L
 init {
  f.service.display=f.service.display.copy(context=session.receiverUsability().context)
  f.bootstrap.refresh();f.hostLane.submit{}.get(2,TimeUnit.SECONDS);Handler.flush()
  f.ready();f.report(ReceiverUsabilityMode.LOCAL_PANEL);Handler.flush()
  method(backend.session,"prepareHidRegion")
  mode(ReceiverUsabilityMode.LOCAL_PANEL)
  method(backend.session,"receiveControl",Frame(Wire.HEAD_POINTER,payload=HeadPointerState(4,1,true,false,10f,20f,null,null).bytes()))
  // Binder/FD boundary forwards only; Session/provider/read lane/merger/facade/ingress are actual source.
  f.service.snapshotProvider={session.phoneHidSnapshot()}
  f.service.stampedInput={id,edge,region,word->session.phoneHidStampedEdge(id,edge,region,word)}
  PhoneHidRuntime::class.java.getDeclaredField("instance").apply{isAccessible=true}.set(null,null)
  runtime=PhoneHidRuntime.get(f.context);ingress=PhoneHidKeyService();ingress.onServiceConnected()
 }
 fun mode(mode:ReceiverUsabilityMode){method(backend.session,"receiveControl",Frame(Wire.RECEIVER_USABILITY,payload=ReceiverUsability(f.service.sessionEpoch,ReceiverIncarnation("transport-receiver",1),++revision,4,mode).bytes()))}
 fun key(at:Long,phase:Int=KeyEvent.ACTION_DOWN,down:Long=at,k:Int=201)=ingress.onKeyEvent(KeyEvent(device,phase,k,801,down,at))
 fun bound(){check(runtime.controller.bindings.assign(PhoneHidAction.TAP,PhoneHidBinding(PhoneHidButton(PhoneHidDeviceIdentity(device.descriptor,7,8),201,801),device.name,"tap")))}
 fun ready(){f.nativeAwaitReady(runtime)}
 override fun close(){ingress.onDestroy();Handler.flush();(member(runtime,"writer") as ExecutorService).let{it.shutdownNow();it.awaitTermination(2,TimeUnit.SECONDS)};Handler.flush()}
}

fun transportFrontendScenarios(){
 for(bound in listOf(false,true))Fixture().use{f->PanelFixture(f.service.sessionEpoch).use{backend->TransportRig(f,backend).use{r->
  if(bound)r.bound();r.ready()
  check(!r.key(900,k=202));check(!r.key(910,KeyEvent.ACTION_UP,900,k=202)) // Known usable unassigned passes.
  val before=f.directive().wakeRevision;val demand=(member(f.sessions,"activation") as PhoneActivationEngine).journal.current!!.demandId
  val held=CountDownLatch(1);val release=CountDownLatch(1)
  f.lane.execute{held.countDown();check(release.await(4,TimeUnit.SECONDS))};check(held.await(1,TimeUnit.SECONDS))
  val reads=NativeSnapshotAccess.reads.get();val calls=f.service.inputCalls.size
  r.mode(ReceiverUsabilityMode.NONE) // Already accepted/published; main/facade still report local usable.
  check(!f.sessions.needsActivation())
  check(r.key(1000));check(NativeSnapshotAccess.reads.get()==reads+1)
  check(r.key(1020,KeyEvent.ACTION_UP,1000))
  check(f.service.inputCalls.size==calls && backend.peer.sink.size()==0)
  release.countDown();f.lane.submit{}.get(2,TimeUnit.SECONDS);Handler.flush();f.lane.submit{}.get(2,TimeUnit.SECONDS)
  val engine=member(f.sessions,"activation") as PhoneActivationEngine
  check(engine.inputPresentation==null && f.directive().wakeRevision>before && engine.journal.current!!.demandId==demand)
  check(f.directive().ownerEpoch==f.service.sessionEpoch && r.session.receiverUsability().context==f.service.context)
  println("PASS actual established-descriptor HID27 bound=$bound: published NONE beats stale LOCAL facade, one acquire/whole gesture wake-owned/no target; durable original H/A directive preserves PROJECTED D")
 }}}
 Fixture().use{f->PanelFixture(f.service.sessionEpoch).use{backend->TransportRig(f,backend).use{r->
  r.ready();r.runtime.controller.openPage();r.runtime.controller.select(PhoneHidAction.TAP)
  r.mode(ReceiverUsabilityMode.NONE)
  check(r.key(1300));check(r.runtime.controller.capturing) // Actual native loss invalidator must preserve row ownership.
  check(r.key(1320,KeyEvent.ACTION_UP,1300))
  waitFor("real writer saves original row-selected key after native NONE invalidation") {
   r.runtime.controller.saving==0 && r.runtime.controller.bindings.binding(PhoneHidAction.TAP)?.button?.keyCode==201
  }
  check(r.runtime.controller.selected==PhoneHidAction.TAP && f.service.inputCalls.isEmpty())
  r.runtime.controller.select(PhoneHidAction.SCROLL_UP)
  check(r.key(1340,k=202));f.sessions.stop();check(!r.runtime.controller.capturing)
  check(r.key(1360,KeyEvent.ACTION_UP,1340,k=202));check(r.runtime.controller.bindings.binding(PhoneHidAction.SCROLL_UP)==null)
  println("PASS actual native NONE presentation invalidation preserves sticky row learning through UP/save; whole Stop still cancels original capture; no target replay")
 }}}
 Fixture().use{f->PanelFixture(f.service.sessionEpoch).use{backend->TransportRig(f,backend).use{r->
  r.ready();val old=r.session.receiverUsability().regionId
  val slot=backend.field("receiverUsability") as SessionUsability
  val writer=slot.javaClass.getDeclaredField("region").apply{isAccessible=true}.get(slot)!!
  writer.javaClass.getDeclaredField("stamp").apply{isAccessible=true}.setLong(writer,SnapshotWord.MAX_STAMP)
  r.mode(ReceiverUsabilityMode.NONE)
  // Old installed reader stays terminal even if native worker has already installed a new writer.
  check(r.key(1400));check(r.key(1420,KeyEvent.ACTION_UP,1400))
  waitFor("retired original reader replaced and resolves original demand without second press") {
   r.session.receiverUsability().regionId!=old && member(r.runtime,"pendingSetup")==null &&
       (member(f.sessions,"activation") as PhoneActivationEngine).inputPresentation==null
  }
  check(f.service.inputCalls.isEmpty())
  println("PASS original RETIRED reader rotates to new UUID under same H/A; UNKNOWN after UP resolves new native NONE without another press/replay")
 }}}
 Fixture().use{f->PanelFixture(f.service.sessionEpoch).use{backend->TransportRig(f,backend).use{r->
  val failed=CountDownLatch(1);val permit=java.util.concurrent.atomic.AtomicBoolean(false)
  NativeSnapshotAccess.beforePrepare={if(!permit.get()){failed.countDown();throw UnsatisfiedLinkError("explicit client loader boundary")}}
  try {
   Handler.flush();check(failed.await(2,TimeUnit.SECONDS))
   (member(r.runtime,"writer") as ExecutorService).submit{}.get(2,TimeUnit.SECONDS);Handler.flush()
   check(r.key(1500));check(r.key(1520,KeyEvent.ACTION_UP,1500));check(member(r.runtime,"pendingSetup")!=null)
   r.mode(ReceiverUsabilityMode.NONE);permit.set(true)
   // Existing facade publication drives another original setup observation, not another physical press/timer.
   f.lane.submit{}.get(2,TimeUnit.SECONDS);Handler.flush()
   waitFor("original pending setup survives loader failure and existing actor publication") {member(r.runtime,"pendingSetup")==null && (member(f.sessions,"activation") as PhoneActivationEngine).inputPresentation==null}
   check(f.service.inputCalls.isEmpty())
   println("PASS explicit client loader failure remains UNKNOWN; original demand after UP resolves on existing setup publication without a second press/replay")
  } finally {NativeSnapshotAccess.beforePrepare=null}
 }}}
 Fixture().use{f->PanelFixture(f.service.sessionEpoch).use{backend->TransportRig(f,backend).use{r->
  val entered=CountDownLatch(1);val release=CountDownLatch(1)
  f.service.snapshotProvider={entered.countDown();check(release.await(4,TimeUnit.SECONDS));r.session.phoneHidSnapshot()}
  Handler.flush();check(entered.await(2,TimeUnit.SECONDS));check(r.key(1800));check(r.key(1820,KeyEvent.ACTION_UP,1800))
  r.ingress.onDestroy();Handler.flush()
  check(!r.runtime.controller.connected && member(r.runtime,"pendingSetup")!=null)
  r.mode(ReceiverUsabilityMode.NONE);release.countDown()
  waitFor("admitted H wake obligation survives service detach and resolves independently of reconnect") {
   member(r.runtime,"pendingSetup")==null && (member(f.sessions,"activation") as PhoneActivationEngine).inputPresentation==null
  }
  check(f.service.inputCalls.isEmpty())
  println("PASS UNKNOWN after raw UP survives Accessibility detach while H is current; setup resolves native NONE without reconnect/another press or target replay")
 }}}
 Fixture().use{f->PanelFixture(f.service.sessionEpoch).use{backend->TransportRig(f,backend).use{r->
  val entered=CountDownLatch(1);val release=CountDownLatch(1)
  f.service.snapshotProvider={entered.countDown();check(release.await(4,TimeUnit.SECONDS));r.session.phoneHidSnapshot()}
  Handler.flush();check(entered.await(2,TimeUnit.SECONDS))
  val before=f.directive().wakeRevision
  check(r.key(2000));check(r.key(2020,KeyEvent.ACTION_UP,2000))
  check(member(r.runtime,"pendingSetup")!=null && f.service.inputCalls.isEmpty())
  r.mode(ReceiverUsabilityMode.NONE);release.countDown()
  waitFor("original UNKNOWN resolves after UP to actual native NONE") {member(r.runtime,"pendingSetup")==null && (member(f.sessions,"activation") as PhoneActivationEngine).inputPresentation==null}
  f.lane.submit{}.get(2,TimeUnit.SECONDS);Handler.flush()
  check(f.directive().wakeRevision>before && f.service.inputCalls.isEmpty())
  println("PASS actual UNKNOWN descriptor setup demand survives raw UP and resolves original native NONE to durable wake without another press or replay")
 }}}
 Fixture().use{f->PanelFixture(f.service.sessionEpoch).use{backend->TransportRig(f,backend).use{r->
  val entered=CountDownLatch(1);val release=CountDownLatch(1)
  f.service.snapshotProvider={entered.countDown();check(release.await(4,TimeUnit.SECONDS));r.session.phoneHidSnapshot()}
  Handler.flush();check(entered.await(2,TimeUnit.SECONDS));check(r.key(3000));check(r.key(3020,KeyEvent.ACTION_UP,3000))
  val old=f.sessions.activationId();f.sessions.stop();val h2=f.sessions.requestActivation(ActivationCause.PHONE_START)
  check(h2.id!=old);release.countDown();Handler.flush()
  (member(r.runtime,"writer") as ExecutorService).submit{}.get(2,TimeUnit.SECONDS);Handler.flush()
  check(member(r.runtime,"pendingSetup")==null && f.service.inputCalls.isEmpty())
  check(f.sessions.isActivationCurrent(h2))
  println("PASS delayed original descriptor/UNKNOWN demand after Stop cannot install into or wake successor H2")
 }}}
}
