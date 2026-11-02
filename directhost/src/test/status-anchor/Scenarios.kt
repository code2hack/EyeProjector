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

fun main(){
 val dir=Files.createTempDirectory("actual-status-anchor-").toFile()
 val size=Geometry(480,640);val fact=ReceiverUsability("owner",ReceiverIncarnation("receiver",1),1,1,ReceiverUsabilityMode.PROJECTED)
 val context=AnchorIntentContext(fact,HeadPointerState(1,1,true,true,30f,40f,Fit(0,0,480,640),size))
 val source=ManagedUiSource(ProjectionContext("owner","session"),"original",63,"display","app",null,UiSourceGeometry(size,size,160,0,false,size))
 val target=ResolvedAnchorTarget(source,UiWindowMetadata(63,4,1,0,true,true,UiBounds(0,0,480,640)),
  UiTargetObservation(0,4,"app","app:id/hold","Button",UiBounds(20,30,40,50),emptyList(),false,false,true,true))
 val setting=AnchorSettingContext("store","app",1)
 val touches=CopyOnWriteArrayList<Touch>();val work=InputWork("status-original-cancel");val ended=CountDownLatch(1);work.whenTerminated{ended.countDown()}
 val controls=AnchorSessionInput(Any()){error("No stock fallback")}
 controls.cancellation(work::submit){throw it}
 controls.bindMotionTarget(object:PhoneTouchDispatcher.MotionTarget{
  override fun geometry(next:Geometry)=Unit
  override fun touch(event:Touch)=true.also{touches.add(event)}
  override fun tap(event:Tap)=error("No user tap")
  override fun scroll(event:Scroll)=error("No user scroll")
 });controls.geometry(size);controls.anchor.reconcile(setting)
 val storage=ActivationStore(dir.resolve("phone.properties"));storage.write(ActivationJournal(
  current=DesiredActivation("H1"),triggers=mapOf("T1" to TriggerBinding("H1","admission1"),
      "stale-T" to TriggerBinding("old-H","old-admission",canceled=true)),canceled=setOf("old-H")))
 val core=SessionController({error("No backend start")},{error("No backend Stop")})
 val engine=PhoneActivationEngine(core,storage::read,storage::write,{"FIXTURE_NO_BACKEND"},{source.context})
 var gates=0
 val facade=FacadeCommand(engine,admit={gates++;controls.consumeHeldControl(AnchorControlAdmission())==AnchorControlDisposition.NORMAL},capture={
    val original=AnchorControlAdmission();controls.anchor.receiveControl(original)
    val gate:()->Boolean={gates++;controls.consumeHeldControl(original)==AnchorControlDisposition.NORMAL};gate
   })
 val feature=ReceiverActivationStore(dir.resolve("glasses.properties"))
 feature.startIncarnation();feature.launch(ReceiverLaunchScope("scope1","T1",1),"admission1")
 feature.accept(ActivationReply(ActivationDirective("H1",true,1,"owner"),TriggerReceipt("T1","H1",TriggerDisposition.MAPPED)))
 val requests=LinkedBlockingQueue<SessionControl.Request>();val results=LinkedBlockingQueue<SessionState>()
 val client=PairedSessionClient(dir,{}, {error("No missing peer")},transport={request,_->
  // Preserve the actual request encoding between both source consumers.
  val bytes=ByteArrayOutputStream();SessionControl.writeRequest(DataOutputStream(bytes),request)
  val copied=SessionControl.readRequest(DataInputStream(bytes.toByteArray().inputStream()))
  requests.put(copied);facade.receive(copied).also{results.put(it)}
 },feature=feature)
 fun hold(id:Long){val intent=AnchorIntent(AnchorIntentKind.ACTIVATE,AnchorSource.GLASSES_NATIVE,id,id,context)
  val admission=controls.anchor.capture(intent)!!
  check(controls.hold(admission,setting,target,source,{true},{it()},AnchorHoldOutput(work){_,_->})==AnchorExecutionStatus.HOLD_SUBMITTED)}
 fun settled(){val latch=CountDownLatch(1);work.submit{latch.countDown()};check(latch.await(3,TimeUnit.SECONDS))}
 try{
  hold(1)
  repeat(3){client.presentationChanged();val sent=requests.poll(3,TimeUnit.SECONDS)?:error("No actual poll")
   check(sent.operation==SessionControl.Operation.STATUS&&sent.intent==ActivationIntent("T1"))
   check(results.poll(3,TimeUnit.SECONDS)!=null);settled()
   check(controls.anchor.holding&&touches.map{it.phase}==listOf(Touch.Phase.DOWN)){"Passive mapped STATUS canceled original: gates=$gates edges=${touches.map{it.phase}}"}}
  check(gates==0);println("PASS repeated actual paired STATUS/current T leaves original Hold and no user gate")
  val beforeStale=gates
  val stale=facade.receive(SessionControl.Request(SessionControl.Operation.STATUS,intent=ActivationIntent("stale-T")))
  settled();check(gates==beforeStale&&controls.anchor.holding&&touches.size==1)
  check(stale.activation?.trigger?.disposition==TriggerDisposition.CANCELED)
  check(engine.journal.current!!.id=="H1"&&engine.journal.current!!.desired)
  println("PASS consumed stale trigger remains terminal without canceling independent current Hold")
  val release=controls.anchor.capture(AnchorIntent(AnchorIntentKind.ACTIVATE,AnchorSource.GLASSES_NATIVE,2,2,context))!!
  check(controls.anchor.releaseHeld(release,setting,true,source){true}==AnchorReleaseResult.RELEASE_SUBMITTED)
  check(touches.map{it.phase}==listOf(Touch.Phase.DOWN,Touch.Phase.UP));println("PASS second Short releases original after passive polls")
  hold(3);val before=gates
  facade.receive(SessionControl.Request(SessionControl.Operation.STATUS,intent=ActivationIntent("new-T")))
  settled();check(gates==before+1&&!controls.anchor.holding)
  check(touches.last().phase==Touch.Phase.CANCEL);check(engine.journal.triggers.getValue("new-T").let{it.canceled&&it.activation==null})
  println("PASS fresh embedded user intent retains explicit cancel-only admission")
  hold(4);val retriesBefore=gates
  facade.receive(SessionControl.Request(SessionControl.Operation.START,intent=ActivationIntent("T1")))
  settled();check(gates==retriesBefore&&controls.anchor.holding)
  val terminal=controls.anchor.capture(AnchorIntent(AnchorIntentKind.ACTIVATE,AnchorSource.GLASSES_NATIVE,5,5,context))!!
  check(controls.anchor.releaseHeld(terminal,setting,true,source){true}==AnchorReleaseResult.RELEASE_SUBMITTED)
  println("PASS repeated mapped feature START follows actual engine observation semantics")
  for((index,request) in listOf(
      SessionControl.Request(SessionControl.Operation.START,epoch=core.state().epoch),
      SessionControl.Request(SessionControl.Operation.STOP,epoch=core.state().epoch),
      SessionControl.Request(SessionControl.Operation.STATUS,cancelTrigger=CancelTrigger("T1","admission1"))).withIndex()) {
   hold(10L+index);val previousGates=gates;val previous=touches.size
   facade.receive(request);settled()
   check(gates==previousGates+1&&touches.size==previous+1&&touches.last().phase==Touch.Phase.CANCEL)
   check(!controls.anchor.holding&&engine.journal.current!!.desired)
  }
  println("PASS explicit Start Stop and cancellation fields still cancel only without executing handlers")

 }finally{client.close();facade.close();controls.close();work.close();check(ended.await(3,TimeUnit.SECONDS));dir.deleteRecursively()}
}
