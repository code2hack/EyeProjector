package com.code2hack.eyeprojector.directhost
import com.code2hack.eyeprojector.directhost.activation.*
import com.code2hack.eyeprojector.directhost.anchor.execution.*
import com.code2hack.eyeprojector.directhost.anchor.preferences.*
import com.code2hack.eyeprojector.directhost.anchor.target.*
import com.code2hack.eyeprojector.directhost.uiaccess.*
import com.code2hack.eyeprojector.directhost.input.InputWork
import com.code2hack.eyeprojector.directrg.*
import com.code2hack.eyeprojector.protocol.*
import java.io.*
import java.nio.file.Files
import java.util.concurrent.*

private class RetryRig:AutoCloseable {
 val dir=Files.createTempDirectory("remote-retry-original-").toFile()
 val size=Geometry(480,640)
 val context=AnchorIntentContext(ReceiverUsability("owner",ReceiverIncarnation("receiver",1),1,1,ReceiverUsabilityMode.PROJECTED),
  HeadPointerState(1,1,true,true,30f,40f,Fit(0,0,480,640),size))
 val source=ManagedUiSource(ProjectionContext("owner","session"),"original",63,"display","app",null,UiSourceGeometry(size,size,160,0,false,size))
 val target=ResolvedAnchorTarget(source,UiWindowMetadata(63,4,1,0,true,true,UiBounds(0,0,480,640)),
  UiTargetObservation(0,4,"app","app:id/hold","Button",UiBounds(20,30,40,50),emptyList(),false,false,true,true))
 val setting=AnchorSettingContext("store","app",1)
 val touches=CopyOnWriteArrayList<Touch>();val work=InputWork("retry-original-cancel");val ended=CountDownLatch(1)
 val controls=AnchorSessionInput(Any()){error("No stock output")}
 val storage=ActivationStore(dir.resolve("phone.properties"))
 val core=SessionController({error("No backend startup in this fixture")},{error("No backend stop in this fixture")})
 val engine:PhoneActivationEngine
 var gates=0
 @Volatile var captured:()->Unit={}
 val facade:FacadeCommand
 val feature=ReceiverActivationStore(dir.resolve("glasses.properties"))
 val scope1=ReceiverLaunchScope("scope1","T1",1)
 val seen=CopyOnWriteArrayList<Pair<SessionControl.Request,SessionState>>()
 var loseReplies=false
 val client:PairedSessionClient
 init {
  work.whenTerminated{ended.countDown()};controls.cancellation(work::submit){throw it}
  controls.bindMotionTarget(object:PhoneTouchDispatcher.MotionTarget{
   override fun geometry(next:Geometry)=Unit
   override fun touch(event:Touch)=true.also{touches.add(event)}
   override fun tap(event:Tap)=error("No ordinary tap")
   override fun scroll(event:Scroll)=error("No ordinary scroll")
  });controls.geometry(size);controls.anchor.reconcile(setting)
  storage.write(ActivationJournal(current=DesiredActivation("activation1"),triggers=mapOf("T1" to TriggerBinding("activation1","A1"))))
  engine=PhoneActivationEngine(core,storage::read,storage::write,{"FIXTURE_NO_BACKEND"},{source.context})
  facade=FacadeCommand(engine,admit={gates++;controls.consumeHeldControl(AnchorControlAdmission())==AnchorControlDisposition.NORMAL},capture={
    val original=AnchorControlAdmission();controls.anchor.receiveControl(original);captured()
    val gate:()->Boolean={gates++;controls.consumeHeldControl(original)==AnchorControlDisposition.NORMAL};gate
   })
  feature.startIncarnation();feature.launch(scope1,"A1");feature.accept(engine.state().activation!!.copy(
    trigger=TriggerReceipt("T1","activation1",TriggerDisposition.MAPPED)))
  client=PairedSessionClient(dir,{}, {},transport={request,_->
   val bytes=ByteArrayOutputStream();SessionControl.writeRequest(DataOutputStream(bytes),request)
   val copied=SessionControl.readRequest(DataInputStream(bytes.toByteArray().inputStream()))
   val reply=facade.receive(copied);seen.add(copied to reply)
   if(loseReplies)null else reply
  },feature=feature)
 }
 fun poll(){client.presentationChanged();awaitPoll()}
 fun awaitPoll(){val executor=client.javaClass.getDeclaredField("executor").apply{isAccessible=true}.get(client) as ScheduledExecutorService
  executor.submit{}.get(3,TimeUnit.SECONDS);settle()}
 fun hold(id:Long){val i=AnchorIntent(AnchorIntentKind.ACTIVATE,AnchorSource.GLASSES_NATIVE,id,id,context)
  check(controls.hold(controls.anchor.capture(i)!!,setting,target,source,{true},{it()},AnchorHoldOutput(work){_,_->})==AnchorExecutionStatus.HOLD_SUBMITTED)}
 fun release(id:Long){val i=AnchorIntent(AnchorIntentKind.ACTIVATE,AnchorSource.GLASSES_NATIVE,id,id,context)
  check(controls.anchor.releaseHeld(controls.anchor.capture(i)!!,setting,true,source){true}==AnchorReleaseResult.RELEASE_SUBMITTED)}
 fun settle(){val done=CountDownLatch(1);work.submit{done.countDown()};check(done.await(3,TimeUnit.SECONDS))}
 override fun close(){client.close();val executor=client.javaClass.getDeclaredField("executor").apply{isAccessible=true}.get(client) as ScheduledExecutorService
  check(executor.awaitTermination(3,TimeUnit.SECONDS));facade.close();controls.close();work.close();check(ended.await(3,TimeUnit.SECONDS));dir.deleteRecursively()}
}
fun main(){var failures=0
 fun scenario(name:String,body:(RetryRig)->Unit){try{RetryRig().use(body);println("PASS $name")}catch(e:Throwable){failures++;println("FAIL $name: $e");e.printStackTrace()}}
 scenario("consumed fresh trigger retry after successor Hold cannot cancel or activate it"){r->
  r.hold(1);r.feature.launch(ReceiverLaunchScope("scope2","T2",2),"A2");r.loseReplies=true;r.poll()
  check(!r.controls.anchor.holding&&r.touches.map{it.phase}==listOf(Touch.Phase.DOWN,Touch.Phase.CANCEL))
  r.hold(2);val gates=r.gates;val count=r.touches.size;r.loseReplies=false;r.poll()
  check(r.controls.anchor.holding&&r.touches.size==count&&r.gates==gates){"T2 retry canceled successor: ${r.touches.map{it.phase}} gates=$gates->${r.gates}"}
  check(r.storage.read().triggers.getValue("T2").let{it.canceled&&it.activation==null})
  check(r.feature.intent()==null);check(r.engine.journal.current!!.id=="activation1"&&r.engine.journal.current!!.desired)
  r.release(3);check(r.touches.last().phase==Touch.Phase.UP)
 }
 scenario("lost cancellation acknowledgment reconciles original T and H without touching successor"){r->
  r.feature.cancel(r.scope1,"activation1","A1");r.loseReplies=true;r.poll()
  check(r.engine.journal.triggers.getValue("T1").canceled);check("activation1" in r.engine.journal.canceled)
  r.engine.request(ActivationCause.PHONE_START,"activation2")
  val scope2=ReceiverLaunchScope("scope2","T2",2);r.feature.launch(scope2,"A2")
  r.feature.accept(r.engine.command(SessionControl.Request(SessionControl.Operation.STATUS,intent=ActivationIntent("T2"))).activation)
  r.hold(10);val gates=r.gates;val count=r.touches.size;r.loseReplies=false;r.poll()
  check(r.controls.anchor.holding&&r.touches.size==count&&r.gates==gates){"Old cancellation canceled successor: ${r.touches.map{it.phase}} gates=$gates->${r.gates}"}
  check(r.feature.pendingTriggers().isEmpty()&&r.feature.state.pendingCancel.isEmpty())
  check(r.engine.journal.current!!.id=="activation2"&&r.engine.journal.current!!.desired)
  r.release(11)
 }
 scenario("consumed cancellation T and H tails cannot later execute or cancel another Hold"){r->
  r.hold(20);r.feature.cancel(r.scope1,"activation1","A1");r.loseReplies=true;r.poll()
  check(!r.controls.anchor.holding);check(r.engine.journal.current!!.desired){"Consumed user cancellation executed backend transition"}
  r.hold(21);val gates=r.gates;val count=r.touches.size;r.loseReplies=false;r.poll()
  check(r.controls.anchor.holding&&r.touches.size==count&&r.gates==gates){"Consumed cancellation tail canceled successor"}
  check(r.engine.journal.current!!.desired);r.release(22)
 }
 scenario("terminal trigger and paired cancellation receipts survive journal reload"){r->
  r.hold(40);r.facade.receive(SessionControl.Request(SessionControl.Operation.STATUS,intent=ActivationIntent("T2")));r.settle()
  r.hold(41);r.facade.receive(SessionControl.Request(SessionControl.Operation.STATUS,cancelTrigger=CancelTrigger("T1","A1")));r.settle()
  r.hold(42);val count=r.touches.size
  val restored=PhoneActivationEngine(r.core,r.storage::read,r.storage::write,{"FIXTURE_NO_BACKEND"},{r.source.context})
  var gates=0
  FacadeCommand(restored,admit={error("No direct recapture")},capture={
   val original=AnchorControlAdmission();r.controls.anchor.receiveControl(original)
   val gate:()->Boolean={gates++;r.controls.consumeHeldControl(original)==AnchorControlDisposition.NORMAL};gate
  }).use { next ->
   for(request in listOf(SessionControl.Request(SessionControl.Operation.STATUS,intent=ActivationIntent("T2")),
       SessionControl.Request(SessionControl.Operation.STATUS,cancelTrigger=CancelTrigger("T1","A1")),
       SessionControl.Request(SessionControl.Operation.STATUS,cancelActivation=CancelActivation("activation1"))))next.receive(request)
  }
  r.settle();check(gates==0&&r.controls.anchor.holding&&r.touches.size==count)
  check(restored.journal.current!!.desired);r.release(43)
 }
 scenario("control received CANCELING cannot wait behind feature writer then cancel later Hold"){r->
  val cancelEntered=CountDownLatch(1);val cancelRelease=CountDownLatch(1)
  val writerEntered=CountDownLatch(1);val writerRelease=CountDownLatch(1);val received=CountDownLatch(1)
  r.hold(50)
  try{
   r.work.submit{cancelEntered.countDown();check(cancelRelease.await(3,TimeUnit.SECONDS))};check(cancelEntered.await(2,TimeUnit.SECONDS))
   check(r.controls.consumeHeldControl(AnchorControlAdmission())==AnchorControlDisposition.CONSUMED)
   check(r.controls.anchor.discardingControls)
   r.facade.blockWriter(writerEntered,writerRelease);check(writerEntered.await(2,TimeUnit.SECONDS))
   r.captured={received.countDown()};r.feature.launch(ReceiverLaunchScope("scope3","T3",3),"A3");r.client.presentationChanged()
   check(received.await(2,TimeUnit.SECONDS));r.captured={}
   cancelRelease.countDown();r.settle();r.hold(51);val count=r.touches.size
   writerRelease.countDown();r.awaitPoll()
   check(r.controls.anchor.holding&&r.touches.size==count){"Queued received-CANCELING action canceled later Hold"}
   check(r.storage.read().triggers.getValue("T3").canceled);r.release(52)
  }finally{cancelRelease.countDown();writerRelease.countDown();r.captured={}}
 }
 check(failures==0){"$failures retry ownership failures"}
}
