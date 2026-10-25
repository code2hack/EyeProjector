package com.code2hack.eyeprojector.directhost

import android.os.MemoryBoundary
import com.code2hack.eyeprojector.directhost.activation.snapshot.*
import com.code2hack.eyeprojector.protocol.*
import java.io.*
import java.util.concurrent.*
import java.util.concurrent.atomic.*
import kotlin.concurrent.thread

private fun nativeCall(f:PanelFixture,name:String,vararg args:Any?):Any? = f.session.javaClass.declaredMethods.single{it.name==name}.apply{isAccessible=true}.invoke(f.session,*args)
private fun nativeReceive(f:PanelFixture,channel:Int,payload:ByteArray) = nativeCall(f,"receiveControl",Frame(channel,payload=payload))
private fun nativeFact(f:PanelFixture,rev:Long,mode:ReceiverUsabilityMode,input:Long=4,inc:ReceiverIncarnation=ReceiverIncarnation("native-source",1))=ReceiverUsability(f.ownerEpoch,inc,rev,input,mode)
private fun nativeAwait(message:String,limit:Long=2000,test:()->Boolean){val end=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(limit);while(!test()&&System.nanoTime()<end)Thread.sleep(2);check(test()){message}}
internal fun nativeTransportBackendScenarios(){
 PanelFixture("native-publication").use{f->
  nativeCall(f,"prepareHidRegion")
  val captured=f.session as CapturedProjection;val reader=NativeSnapshotReadLane()
  reader.install(checkNotNull(captured.phoneHidSnapshot()),captured.receiverUsability().context)
  check(reader.read()==0L) // Established region, no accepted Receiver fact: UNKNOWN, not observed NONE.
  nativeReceive(f,Wire.RECEIVER_USABILITY,nativeFact(f,1,ReceiverUsabilityMode.LOCAL_PANEL).bytes())
  val first=reader.read();check(SnapshotWord.mode(first)==SnapshotWord.LOCAL_PANEL)
  nativeReceive(f,Wire.RECEIVER_USABILITY,nativeFact(f,2,ReceiverUsabilityMode.LOCAL_PANEL).bytes())
  check(reader.read()==first) // Report-only change is not an input authority change.
  val entered=CountDownLatch(1);val release=CountDownLatch(1)
  MemoryBoundary.beforeDuplicate={entered.countDown();check(release.await(4,TimeUnit.SECONDS))}
  var delayed:NativeSnapshotDescriptor?=null
  val descriptor=thread{delayed=captured.phoneHidSnapshot()};check(entered.await(1,TimeUnit.SECONDS))
  f.blockOff=CountDownLatch(1);val stock=thread{f.off()};check(f.offEntered.await(1,TimeUnit.SECONDS))
  try {
   val published=CountDownLatch(1)
   val producer=thread{nativeReceive(f,Wire.RECEIVER_USABILITY,nativeFact(f,3,ReceiverUsabilityMode.NONE).bytes());published.countDown()}
   check(published.await(1,TimeUnit.SECONDS)){"Accepted NONE blocked on descriptor/stock work"}
   check(SnapshotWord.mode(reader.read())==SnapshotWord.NONE && captured.receiverUsability().fact!!.revision==3L)
   producer.join(1000)
   val closed=CountDownLatch(1);val closing=thread{f.close(SessionStopReason.HOST_STOP);closed.countDown()}
   nativeAwait("retirement visible while descriptor and stock remain blocked"){SnapshotWord.mode(reader.read())==SnapshotWord.RETIRED}
   check(closed.await(2,TimeUnit.SECONDS)){"Stop joined native descriptor/setup work"}
   closing.join(1000)
  } finally {MemoryBoundary.beforeDuplicate=null;release.countDown();f.blockOff!!.countDown();descriptor.join(2000);stock.join(2000);delayed?.close();reader.clear()}
  println("PASS actual channel17/native region publishes NONE and RETIRED through blocked FD duplication/stock; report-only mode stays same word; Stop does not join native cleanup")
 }
 PanelFixture("native-namespace").use{f->
  nativeCall(f,"prepareHidRegion");val c=f.session as CapturedProjection
  nativeReceive(f,Wire.RECEIVER_USABILITY,nativeFact(f,1,ReceiverUsabilityMode.NONE).bytes())
  val first=NativeSnapshotReadLane();val second=NativeSnapshotReadLane()
  val d1=checkNotNull(c.phoneHidSnapshot());val d2=checkNotNull(c.phoneHidSnapshot())
  check(d1.regionId==d2.regionId)
  first.install(d1,c.receiverUsability().context);second.install(d2,c.receiverUsability().context)
  val w1=first.read();check(second.read()==w1)
  first.clear();first.install(checkNotNull(c.phoneHidSnapshot()),c.receiverUsability().context)
  check(first.regionId==second.regionId && first.read()==w1) // Reexport/remap does not reset producer namespace.
  nativeReceive(f,Wire.RECEIVER_USABILITY,nativeFact(f,2,ReceiverUsabilityMode.LOCAL_PANEL).bytes())
  nativeReceive(f,Wire.RECEIVER_USABILITY,nativeFact(f,3,ReceiverUsabilityMode.NONE).bytes())
  check(first.read()==second.read() && SnapshotWord.stamp(first.read())>SnapshotWord.stamp(w1))
  first.clear();second.clear()
  println("PASS namespace matrix: independent descriptor reexport/remap keeps writer UUID and monotonic stamp; frontend mapping lifetime never resets producer sequence")
 }
 PanelFixture("native-rotate").use{f->
  nativeCall(f,"prepareHidRegion");val slot=f.field("receiverUsability") as SessionUsability
  nativeReceive(f,Wire.RECEIVER_USABILITY,nativeFact(f,1,ReceiverUsabilityMode.LOCAL_PANEL).bytes())
  val reader=NativeSnapshotReadLane();reader.install(checkNotNull((f.session as CapturedProjection).phoneHidSnapshot()),slot.snapshot().context)
  val old=slot.snapshot().regionId;val writer=slot.javaClass.getDeclaredField("region").apply{isAccessible=true}.get(slot)!!
  writer.javaClass.getDeclaredField("stamp").apply{isAccessible=true}.setLong(writer,SnapshotWord.MAX_STAMP)
  val unmaps=MemoryBoundary.unmaps.get()
  nativeReceive(f,Wire.RECEIVER_USABILITY,nativeFact(f,2,ReceiverUsabilityMode.NONE).bytes())
  nativeAwait("rotation installs new current region and drains old resources before Session Stop") {slot.snapshot().regionId!=old && MemoryBoundary.unmaps.get()>unmaps}
  check(!slot.snapshot().retired && SnapshotWord.mode(reader.read())==SnapshotWord.RETIRED)
  reader.install(checkNotNull(f.session.phoneHidSnapshot()),slot.snapshot().context)
  check(SnapshotWord.mode(reader.read())==SnapshotWord.NONE && SnapshotWord.stamp(reader.read())==1L);reader.clear()
  println("PASS actual exhausted region retires, replacement publishes latest NONE, old writer resources drain on native lane while original Session stays live")
 }
 PanelFixture("native-queued-down").use{f->
  nativeCall(f,"prepareHidRegion");val c=f.session as CapturedProjection
  nativeReceive(f,Wire.RECEIVER_USABILITY,nativeFact(f,1,ReceiverUsabilityMode.LOCAL_PANEL).bytes())
  nativeReceive(f,Wire.HEAD_POINTER,HeadPointerState(4,1,true,false,10f,20f,null,null).bytes())
  val old=c.receiverUsability();val edge=PhoneHidEdge(PhoneHidAction.TAP,PhoneHidPhase.DOWN,1,2,3,1000,1000)
  f.blockOff=CountDownLatch(1);val stock=thread{f.off()};check(f.offEntered.await(1,TimeUnit.SECONDS))
  val waiting=thread{c.phoneHidStampedEdge(1,edge,old.regionId!!,old.word)}
  nativeAwait("actual stamped DOWN waits for real stock/control lock"){waiting.state==Thread.State.BLOCKED}
  nativeReceive(f,Wire.RECEIVER_USABILITY,nativeFact(f,2,ReceiverUsabilityMode.NONE).bytes())
  f.blockOff!!.countDown();stock.join(2000);waiting.join(2000)
  check(!waiting.isAlive && f.peer.sink.size()==0)
  c.phoneHidStampedEdge(1,edge.copy(phase=PhoneHidPhase.UP,eventTimeMs=1020),old.regionId!!,old.word)
  check(f.peer.sink.size()==0)
  println("PASS actual stamped DOWN queued across accepted NONE is rejected AFTER real merger/control lock wait; no retained contact")
 }
 for(phase in listOf(PhoneHidPhase.UP,PhoneHidPhase.CANCEL))PanelFixture("native-terminal-$phase").use{f->
  nativeCall(f,"prepareHidRegion");val c=f.session as CapturedProjection
  nativeReceive(f,Wire.RECEIVER_USABILITY,nativeFact(f,1,ReceiverUsabilityMode.LOCAL_PANEL).bytes())
  nativeReceive(f,Wire.HEAD_POINTER,HeadPointerState(4,1,true,false,10f,20f,null,null).bytes())
  val old=c.receiverUsability();val edge=PhoneHidEdge(PhoneHidAction.TAP,PhoneHidPhase.DOWN,1,2,3,1000,1000)
  c.phoneHidStampedEdge(1,edge,old.regionId!!,old.word)
  nativeReceive(f,Wire.RECEIVER_USABILITY,nativeFact(f,2,ReceiverUsabilityMode.NONE).bytes())
  c.phoneHidStampedEdge(1,edge.copy(phase=phase,eventTimeMs=1020),old.regionId!!,old.word)
  val data=DataInputStream(f.peer.sink.toByteArray().inputStream())
  check(ReceiverInput.from(Wire.readFrame(data).payload).kind==ReceiverInputKind.DOWN)
  check(ReceiverInput.from(Wire.readFrame(data).payload).kind.name==phase.name && data.available()==0)
  println("PASS admitted original stamped contact $phase reaches cleanup after native loss without latest-word gate")
 }
 for(delayed in listOf(false,true))PanelFixture("native-function-$delayed").use{f->
  nativeCall(f,"prepareHidRegion");val c=f.session as CapturedProjection
  nativeReceive(f,Wire.RECEIVER_USABILITY,nativeFact(f,1,ReceiverUsabilityMode.LOCAL_PANEL).bytes())
  nativeReceive(f,Wire.HEAD_POINTER,HeadPointerState(4,1,true,false,10f,20f,null,null).bytes())
  val gate=CountDownLatch(1);val entered=CountDownLatch(1)
  if(delayed){f.timer.execute{entered.countDown();check(gate.await(3,TimeUnit.SECONDS))};check(entered.await(1,TimeUnit.SECONDS))}
  val first=c.receiverUsability();val now=android.os.SystemClock.uptimeMillis()
  val edge=PhoneHidEdge(PhoneHidAction.FUNCTION,PhoneHidPhase.DOWN,1,2,3,now,now)
  c.phoneHidStampedEdge(1,edge,first.regionId!!,first.word);c.phoneHidStampedEdge(1,edge.copy(phase=PhoneHidPhase.UP,eventTimeMs=now+10),first.regionId!!,first.word)
  nativeReceive(f,Wire.RECEIVER_USABILITY,nativeFact(f,2,ReceiverUsabilityMode.NONE).bytes())
  nativeReceive(f,Wire.RECEIVER_USABILITY,nativeFact(f,3,ReceiverUsabilityMode.LOCAL_PANEL).bytes())
  if(delayed)Thread.sleep(550)
  val next=c.receiverUsability();val at=android.os.SystemClock.uptimeMillis()
  val newer=edge.copy(downTimeMs=at,eventTimeMs=at)
  c.phoneHidStampedEdge(2,newer,next.regionId!!,next.word);c.phoneHidStampedEdge(2,newer.copy(phase=PhoneHidPhase.UP,eventTimeMs=at+10),next.regionId!!,next.word)
  gate.countDown();nativeAwait("new valid Function survives old invalid sequence"){f.peer.sink.size()>0}
  val data=DataInputStream(f.peer.sink.toByteArray().inputStream());check(ReceiverInput.from(Wire.readFrame(data).payload).kind==ReceiverInputKind.BACK && data.available()==0)
  println("PASS native Function old authority retired/new equal-revision press starts fresh single BACK delayedOldTimer=$delayed; no merge or dropped valid press")
 }
 PanelFixture("native-post-up-cancel").use{f->
  nativeCall(f,"prepareHidRegion");val c=f.session as CapturedProjection
  nativeReceive(f,Wire.RECEIVER_USABILITY,nativeFact(f,1,ReceiverUsabilityMode.LOCAL_PANEL).bytes())
  nativeReceive(f,Wire.HEAD_POINTER,HeadPointerState(4,1,true,false,10f,20f,null,null).bytes())
  val before=c.receiverUsability();val at=android.os.SystemClock.uptimeMillis()
  val e=PhoneHidEdge(PhoneHidAction.FUNCTION,PhoneHidPhase.DOWN,1,2,3,at,at)
  c.phoneHidStampedEdge(1,e,before.regionId!!,before.word);c.phoneHidStampedEdge(1,e.copy(phase=PhoneHidPhase.UP,eventTimeMs=at+10),before.regionId!!,before.word)
  nativeReceive(f,Wire.RECEIVER_USABILITY,nativeFact(f,2,ReceiverUsabilityMode.NONE).bytes())
  c.phoneHidStampedEdge(1,e.copy(phase=PhoneHidPhase.CANCEL,eventTimeMs=at+20),before.regionId!!,before.word)
  val merger=f.field("phoneInput")!!
  check(merger.javaClass.getDeclaredField("function").apply{isAccessible=true}.get(merger)==null)
  Thread.sleep(550);check(f.peer.sink.size()==0)
  println("PASS original post-UP Function CANCEL retires its quiet recognition after native loss without a newer stamp")
 }
 for(deadline in listOf(false,true))PanelFixture("native-setup-$deadline").use{f->
  val entered=CountDownLatch(1);val release=CountDownLatch(1);val first=AtomicBoolean(true)
  NativeSnapshotAccess.beforePrepare={if(first.compareAndSet(true,false)){entered.countDown();check(release.await(12,TimeUnit.SECONDS))}}
  val beforeMaps=MemoryBoundary.maps.get();val beforeUnmaps=MemoryBoundary.unmaps.get()
  val startup=nativeCall(f,"beginStartup") as ScheduledFuture<*>
  check(entered.await(1,TimeUnit.SECONDS))
  try {
   val tick=CountDownLatch(1);f.timer.execute{tick.countDown()};check(tick.await(1,TimeUnit.SECONDS)){"Native setup occupied deadline scheduler"}
   nativeReceive(f,Wire.RECEIVER_USABILITY,nativeFact(f,1,ReceiverUsabilityMode.LOCAL_PANEL).bytes())
   nativeReceive(f,Wire.HEAD_POINTER,HeadPointerState(4,1,true,false,10f,20f,null,null).bytes())
   val now=android.os.SystemClock.uptimeMillis();val edge=PhoneHidEdge(PhoneHidAction.FUNCTION,PhoneHidPhase.DOWN,1,2,3,now,now)
   (f.session as CapturedProjection).phoneHidEdge(1,edge)
   f.session.phoneHidEdge(1,edge.copy(phase=PhoneHidPhase.UP,eventTimeMs=now+10))
   nativeAwait("actual Function quiet action progresses with native setup blocked"){f.peer.sink.size()>0}
   check(ReceiverInput.from(Wire.readFrame(DataInputStream(f.peer.sink.toByteArray().inputStream())).payload).kind==ReceiverInputKind.BACK)
   if(deadline) {
    nativeAwait("existing eight-second startup deadline closes while setup is blocked",9500){f.socketClosed.get()}
    check(f.receipt().contains("STARTUP_DEADLINE"))
   } else {
    (f.field("writeStarted") as AtomicLong).set(System.nanoTime()-TimeUnit.SECONDS.toNanos(3))
    val closed=CountDownLatch(1);f.timer.execute{nativeCall(f,"checkWriteDeadline");closed.countDown()}
    check(closed.await(2,TimeUnit.SECONDS)){"Actual write deadline waited for native setup"}
    check(f.receipt().contains("WRITE_DEADLINE"))
   }
   check((f.session as CapturedProjection).receiverUsability().retired && f.session.phoneHidSnapshot()==null)
  } finally {
   startup.cancel(false);NativeSnapshotAccess.beforePrepare=null;release.countDown()
   val setup=f.field("nativeSetup") as ExecutorService;check(setup.awaitTermination(3,TimeUnit.SECONDS))
  }
  check(MemoryBoundary.maps.get()-beforeMaps==MemoryBoundary.unmaps.get()-beforeUnmaps)
  check((f.session as CapturedProjection).receiverUsability().regionId==null)
  println("PASS actual original native setup blocked: Function + ${if(deadline)"startup" else "write"} deadline progress; late retired allocation closes itself without publication")
 }
}
