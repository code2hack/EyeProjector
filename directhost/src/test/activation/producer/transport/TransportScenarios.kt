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
 attachment77Scenarios()
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


/** Actual eligible InputManager delivery -> KeyService -> Controller -> Runtime -> existing facade/engine. */
fun attachment77Scenarios(){
 Fixture().use{f->PanelFixture(f.service.sessionEpoch).use{backend->TransportRig(f,backend).use{r->
  r.ready();f.report(ReceiverUsabilityMode.PROJECTED);Handler.flush();r.ready()
  val engine=member(f.sessions,"activation") as PhoneActivationEngine
  check(engine.ready && !f.sessions.needsActivation() && member(r.runtime,"pendingSetup")==null)
  val h=f.sessions.activationId();val before=f.directive();val demand=engine.journal.current!!.demandId
  val input=member(r.ingress,"manager") as android.hardware.input.InputManager
  input.install(r.device)
  val held=CountDownLatch(1);val release=CountDownLatch(1)
  f.lane.execute{held.countDown();check(release.await(4,TimeUnit.SECONDS))};check(held.await(1,TimeUnit.SECONDS))
  var reads=0L
  try {
   r.mode(ReceiverUsabilityMode.NONE) // Already parsed, accepted, native-published; facade/feature catch-up held.
   check(!f.sessions.needsActivation() && engine.ready)
   val nativeBefore=NativeSnapshotAccess.reads.get()
   input.add(r.device.id) // Real registered KeyService callback; no key event or compatibility-only port.
   reads=NativeSnapshotAccess.reads.get()-nativeBefore
  } finally {release.countDown()}
  f.lane.submit{}.get(2,TimeUnit.SECONDS);Handler.flush();f.lane.submit{}.get(2,TimeUnit.SECONDS)
  val advanced=f.directive().wakeRevision>before.wakeRevision
  val lost=engine.inputPresentation==null
  val sameScope=f.sessions.activationId()==h && f.directive().ownerEpoch==before.ownerEpoch && engine.journal.current!!.demandId==demand
  val noTarget=f.service.inputCalls.isEmpty() && backend.peer.sink.size()==0
  println("OBSERVED C66-01 actual eligible attachment nativeReads=$reads wakeBefore=${before.wakeRevision} wakeAfter=${f.directive().wakeRevision} inputRetired=$lost sameHAD=$sameScope noTarget=$noTarget")
  check(reads==1L && advanced && lost && sameScope && noTarget) {"C66-01 attachment bypassed established native NONE; no second key may be required"}
  println("PASS actual eligible attachment samples published NONE once and produces durable original H/A wake, preserving D without key/target/replay")
 }}}
 attachment77Controls()
}


private fun attachmentInput(r:TransportRig):android.hardware.input.InputManager =
 (member(r.ingress,"manager") as android.hardware.input.InputManager).also{it.install(r.device)}
private fun localRequest(f:Fixture)=(member(f.sessions,"pendingRequest") as java.util.concurrent.atomic.AtomicReference<*>).get()

private fun attachment77Controls(){
 Fixture().use{f->PanelFixture(f.service.sessionEpoch).use{backend->TransportRig(f,backend).use{r->
  r.ready();f.report(ReceiverUsabilityMode.PROJECTED);Handler.flush();r.ready()
  val input=attachmentInput(r);val original=f.directive();val h=f.sessions.activationId();val starts=f.service.starts
  var reads=NativeSnapshotAccess.reads.get();input.add(r.device.id)
  check(NativeSnapshotAccess.reads.get()==reads+1 && member(r.runtime,"pendingSetup")==null)
  f.lane.submit{}.get(2,TimeUnit.SECONDS);Handler.flush();check(f.directive()==original && f.sessions.activationId()==h)
  val held=CountDownLatch(1);val release=CountDownLatch(1)
  f.lane.execute{held.countDown();check(release.await(4,TimeUnit.SECONDS))};check(held.await(1,TimeUnit.SECONDS))
  try {
   val d=f.sessions.requestActivation(ActivationCause.NOTIFICATION);val pending=localRequest(f)
   reads=NativeSnapshotAccess.reads.get();input.add(r.device.id)
   check(NativeSnapshotAccess.reads.get()==reads+1 && localRequest(f)===pending && f.sessions.isActivationCurrent(d))
  } finally {release.countDown()}
  f.lane.submit{}.get(2,TimeUnit.SECONDS);Handler.flush()
  check(f.service.starts==starts && f.service.inputCalls.isEmpty() && backend.peer.sink.size()==0)
  reads=NativeSnapshotAccess.reads.get();r.runtime.controller.activation.request(ActivationCause.HID_ATTACHMENT)
  check(NativeSnapshotAccess.reads.get()==reads+1) // Compatibility entry captures once; typed path never recaptures.
  for(device in listOf(r.device.copy(id=80,isExternal=false),r.device.copy(id=81,isVirtual=true),r.device.copy(id=82,name="ROKID,PSOC-TP-R"),r.device.copy(id=83,name="qpnp_pon"))){input.install(device);input.add(device.id)}
  val after=NativeSnapshotAccess.reads.get();input.add(999);check(after==NativeSnapshotAccess.reads.get() && after==reads+1)
  println("PASS actual USABLE attachment is one-capture/no-effect original-H join; pending PROJECTED D unchanged; compatibility one capture and existing eligibility exclusions/no targets")
 }}}
 Fixture().use{f->PanelFixture(f.service.sessionEpoch).use{backend->TransportRig(f,backend).use{r->
  val entered=CountDownLatch(1);val release=CountDownLatch(1)
  f.service.snapshotProvider={entered.countDown();check(release.await(4,TimeUnit.SECONDS));r.session.phoneHidSnapshot()}
  Handler.flush();check(entered.await(2,TimeUnit.SECONDS));f.report(ReceiverUsabilityMode.PROJECTED);Handler.flush()
  val original=f.directive();val h=f.sessions.activationId();val input=attachmentInput(r)
  try {
   input.add(r.device.id);check(member(r.runtime,"pendingSetup")!=null && f.service.inputCalls.isEmpty())
   r.ingress.onDestroy();Handler.flush();check(member(r.runtime,"pendingSetup")!=null)
   r.mode(ReceiverUsabilityMode.NONE)
  } finally {release.countDown()}
  waitFor("UNKNOWN attachment resolves original NONE without a key or Accessibility reconnect") {
   member(r.runtime,"pendingSetup")==null && (member(f.sessions,"activation") as PhoneActivationEngine).inputPresentation==null && f.directive().wakeRevision>original.wakeRevision
  }
  check(f.sessions.activationId()==h && f.directive().ownerEpoch==original.ownerEpoch && f.service.inputCalls.isEmpty() && backend.peer.sink.size()==0)
  println("PASS actual UNKNOWN eligible attachment survives Accessibility detach and resolves original durable NONE wake without any key/reconnect/target replay")
 }}}
 for(mode in listOf(PhoneHidDisposition.USABLE,PhoneHidDisposition.NONE))Fixture().use{f->PanelFixture(f.service.sessionEpoch).use{backend->TransportRig(f,backend).use{r->
  r.ready();f.report(ReceiverUsabilityMode.PROJECTED);Handler.flush();r.ready();if(mode==PhoneHidDisposition.NONE)r.mode(ReceiverUsabilityMode.NONE)
  val actual=r.runtime.controller.activation;var captured:PhoneHidCapture?=null;var typedCalls=0;var h2:ActivationHandle?=null
  val held=CountDownLatch(1);val release=CountDownLatch(1)
  f.lane.execute{held.countDown();check(release.await(4,TimeUnit.SECONDS))};check(held.await(1,TimeUnit.SECONDS))
  // Explicit test scheduling boundary after ACTUAL pure capture; capture/admission algorithms remain production.
  r.runtime.controller.activation=object:PhoneHidActivation {
   override fun needsWake()=actual.needsWake()
   override fun capture():PhoneHidCapture=actual.capture().also {
    check(it.disposition==mode);captured=it;f.sessions.stop();h2=f.sessions.requestActivation(ActivationCause.PHONE_START)
    r.runtime.controller.activation=PhoneHidActivation.Unwired // Caller must retain its original port/capture.
   }
   override fun request(cause:ActivationCause)=error("unexpected unscoped attachment fallback")
   override fun request(cause:ActivationCause,observation:PhoneHidCapture){check(observation===captured && cause==ActivationCause.HID_ATTACHMENT);typedCalls++;actual.request(cause,observation)}
  }
  try {
   attachmentInput(r).add(r.device.id)
   check(typedCalls==1 && member(r.runtime,"pendingSetup")==null && f.sessions.isActivationCurrent(h2!!))
   val pending=checkNotNull(localRequest(f));check(member(pending,"hidWake")==null && member(pending,"handle")===h2)
   check(f.service.inputCalls.isEmpty() && backend.peer.sink.size()==0)
  } finally {r.runtime.controller.activation=actual;release.countDown()}
  println("PASS actual attachment $mode capture held across Stop/H2 and port replacement cannot rebind, enqueue old loss into H2, recapture or emit target input")
 }}}
 Fixture().use{f->PanelFixture(f.service.sessionEpoch).use{backend->TransportRig(f,backend).use{r->
  val entered=CountDownLatch(1);val release=CountDownLatch(1)
  f.service.snapshotProvider={entered.countDown();check(release.await(4,TimeUnit.SECONDS));r.session.phoneHidSnapshot()}
  Handler.flush();check(entered.await(2,TimeUnit.SECONDS));attachmentInput(r).add(r.device.id);check(member(r.runtime,"pendingSetup")!=null)
  val held=CountDownLatch(1);val go=CountDownLatch(1)
  f.lane.execute{held.countDown();check(go.await(4,TimeUnit.SECONDS))};check(held.await(1,TimeUnit.SECONDS))
  try {
   val old=f.sessions.activationId();f.sessions.stop();val h2=f.sessions.requestActivation(ActivationCause.PHONE_START);val pending=localRequest(f)
   check(old!=h2.id);release.countDown();(member(r.runtime,"writer") as ExecutorService).submit{}.get(2,TimeUnit.SECONDS);Handler.flush()
   check(member(r.runtime,"pendingSetup")==null && localRequest(f)===pending && f.sessions.isActivationCurrent(h2))
   check(member(checkNotNull(pending),"hidWake")==null && f.service.inputCalls.isEmpty())
  } finally {release.countDown();go.countDown()}
  println("PASS actual UNKNOWN attachment delayed original descriptor/result after Stop cannot install or transfer wake payload into H2")
 }}}
 Fixture().use{f->PanelFixture(f.service.sessionEpoch).use{backend->TransportRig(f,backend).use{r->PanelFixture(f.service.sessionEpoch).use{replacement->
  val next=replacement.session as CapturedProjection
  method(replacement.session,"prepareHidRegion")
  method(replacement.session,"receiveControl",Frame(Wire.RECEIVER_USABILITY,payload=ReceiverUsability(f.service.sessionEpoch,ReceiverIncarnation("replacement",1),1,4,ReceiverUsabilityMode.LOCAL_PANEL).bytes()))
  val entered=CountDownLatch(1);val release=CountDownLatch(1);val first=java.util.concurrent.atomic.AtomicBoolean(true)
  f.service.snapshotProvider={if(first.compareAndSet(true,false)){entered.countDown();check(release.await(4,TimeUnit.SECONDS));r.session.phoneHidSnapshot()}else next.phoneHidSnapshot()}
  Handler.flush();check(entered.await(2,TimeUnit.SECONDS));val h=f.sessions.activationId();attachmentInput(r).add(r.device.id)
  check(member(r.runtime,"pendingSetup")!=null)
  try {
   f.service.display=f.service.display.copy(context=next.receiverUsability().context)
   f.bootstrap.refresh();f.hostLane.submit{}.get(2,TimeUnit.SECONDS);Handler.flush()
  } finally {release.countDown()}
  waitFor("old attachment setup result cannot install old C; replacement setup uses exact same-H new context") {
   val authority=member(member(r.runtime,"snapshot"),"authority") as HidInputAuthority?
   authority?.context==next.receiverUsability().context
  }
  check(f.sessions.activationId()==h && member(r.runtime,"pendingSetup")==null && f.service.inputCalls.isEmpty())
  println("PASS actual UNKNOWN attachment old-C descriptor is rejected after original source replacement; same-H replacement reader binds new C without old demand/target replay")
 }}}}
 Fixture(initialActivation=false).use{f->PanelFixture(f.service.sessionEpoch).use{backend->
  val session=backend.session as CapturedProjection
  f.service.display=f.service.display.copy(context=session.receiverUsability().context)
  f.bootstrap.refresh();f.hostLane.submit{}.get(2,TimeUnit.SECONDS);Handler.flush()
  method(backend.session,"prepareHidRegion");method(backend.session,"receiveControl",Frame(Wire.RECEIVER_USABILITY,payload=ReceiverUsability(f.service.sessionEpoch,ReceiverIncarnation("no-owner",1),1,4,ReceiverUsabilityMode.NONE).bytes()))
  f.service.snapshotProvider={session.phoneHidSnapshot()}
  PhoneHidRuntime::class.java.getDeclaredField("instance").apply{isAccessible=true}.set(null,null)
  val runtime=PhoneHidRuntime.get(f.context);val ingress=PhoneHidKeyService();ingress.onServiceConnected()
  try {
   Handler.flush();check(f.sessions.activationId()==null)
   val input=member(ingress,"manager") as android.hardware.input.InputManager;val device=InputDevice(44,"no-owner-hid",4,5,"External fixture")
   val readsBefore=NativeSnapshotAccess.reads.get()
   input.install(device);input.add(device.id);val h=checkNotNull(f.sessions.activationId())
   check(member(runtime,"pendingSetup")!=null)
   waitFor("no-owner UNKNOWN attachment establishes original H/A and completes pending setup without key") {
    f.directiveOrNull()?.ownerEpoch==f.service.sessionEpoch && member(runtime,"pendingSetup")==null && NativeSnapshotAccess.reads.get()>readsBefore
   }
   check(f.sessions.activationId()==h && f.service.inputCalls.isEmpty() && backend.peer.sink.size()==0)
   println("PASS actual no-owner UNKNOWN attachment allocates scoped H and resolves established NONE to original durable directive without any key/target")
  } finally {ingress.onDestroy();Handler.flush();(member(runtime,"writer") as ExecutorService).let{it.shutdownNow();it.awaitTermination(2,TimeUnit.SECONDS)};Handler.flush()}
 }}
}
