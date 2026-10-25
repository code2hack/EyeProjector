package com.code2hack.eyeprojector.directrg
import android.view.Surface
import org.junit.Assert.*
import com.code2hack.eyeprojector.protocol.*
import org.junit.Rule
import org.junit.Test
class ReceiverActivityFeedbackTest {
 private val retained=RetainedReceiverSessionTest()
 @get:Rule val temporary=retained.temporary
 @Test fun actualActivityCallbackCancellationResizeAndLocalPanelFeedbackRemainFinite(){
  retained.Fixture(withFeature=true).use {f->
   val source=ReceiverUiFeedbackSource(f.owner,temporary.root)
   f.owner.attach(source,f.first)
   assertTrue(source.maxDepth in 1..3)
   val before=source.callbacks
   f.owner.advanceInputRevision();source.produce()
   assertEquals(before,source.callbacks)
   val session=f.owner.session
   f.streaming()
   assertTrue(source.maxDepth<=3)
   source.focus=false;f.owner.detach(source)
   source.focus=true;f.owner.attach(source,Surface())
   f.pumpUntil("current retained image reattaches through actual Activity resize feedback"){f.owner.snapshot().state=="STREAMING"}
   assertSame(session,f.owner.session);assertEquals(0,f.codec.releases.get())
   assertTrue(source.maxDepth<=3)
  }
 }
 @Test fun actualGoalSelectionRetainsStaticPixelsDeduplicatesAndRejectsStaleDemand(){
  retained.Fixture(withFeature=true).use{f->
   val ui=ReceiverUiFeedbackSource(f.owner,temporary.root)
   f.owner.attach(ui,f.first);f.streaming();ui.selectLocal()
   assertTrue(ui.panel);assertEquals(ReceiverUsabilityMode.LOCAL_PANEL,f.feature!!.presentation!!.mode)
   val session=f.owner.session;var frames=0;val originalInput=f.codec.queuedInput
   f.codec.queuedInput={pts,flags->frames++;originalInput(pts,flags)}
   val old=f.feature.demand()!!
   f.feature.accept(ActivationReply(f.feature.directive!!.copy(wakeRevision=2,goal=ActivationGoal.PROJECTED)))
   val projected=f.feature.demand()!!
   ui.apply(projected)
   assertFalse(ui.panel)
   val generation=f.owner.javaClass.getDeclaredField("windowGeneration").apply{isAccessible=true}.get(f.owner) as java.util.concurrent.atomic.AtomicLong
   val selected=generation.get();repeat(3){ui.apply(projected)};assertEquals(selected,generation.get())
   f.pumpUntil("static retained redraw fulfills exact projected goal"){f.feature.presentation?.mode==ReceiverUsabilityMode.PROJECTED}
   assertSame(session,f.owner.session);assertEquals(0,frames)
   assertEquals(projected.wakeRevision,f.feature.presentation!!.wakeRevision)
   ui.selectLocal();ui.apply(projected);assertTrue(ui.panel) // A fulfilled unchanged intent cannot override later user selection.
   f.feature.accept(ActivationReply(ActivationDirective("successor",true,1,"successor-A",ActivationGoal.PROJECTED)))
   val unchanged=generation.get();ui.apply(old);ui.apply(projected)
   assertTrue(ui.panel);assertEquals(unchanged,generation.get())
   ui.retire();ui.apply(f.feature.demand()!!);assertTrue(ui.panel)
  }
 }
 @Test fun restoredGoalSelectionStillAttachesANewViewToRetainedOutput(){
  retained.Fixture(withFeature=true).use{f->
   f.streaming();val session=f.owner.session
   val d=f.feature!!.directive!!.copy(goal=ActivationGoal.PROJECTED)
   f.feature.accept(ActivationReply(d));val demand=f.feature.demand()!!
   f.owner.detach(f.view)
   val ui=ReceiverUiFeedbackSource(f.owner,temporary.root);ui.restore(demand,false)
   ui.attach();ui.apply(demand)
   f.pumpUntil("recreated Activity first attachment redraws retained static frame"){f.feature.presentation?.mode==ReceiverUsabilityMode.PROJECTED}
   assertSame(session,f.owner.session);assertFalse(ui.panel);assertEquals(0,f.codec.releases.get())
  }
 }
 @Test fun originalOwnerReportCannotBeRestampedAfterConcurrentDirectiveReplacement(){
  retained.Fixture(withFeature=true).use{f->
   f.streaming();f.owner.presentation(f.view,true,false)
   val session=f.owner.session!!;val entered=java.util.concurrent.CountDownLatch(1);val go=java.util.concurrent.CountDownLatch(1)
   val revisions=java.util.concurrent.atomic.AtomicLong(1000)
   session.javaClass.getDeclaredField("nextUsabilityRevision").apply{isAccessible=true}.set(session,{
    entered.countDown();check(go.await(3,java.util.concurrent.TimeUnit.SECONDS));revisions.incrementAndGet()
   } as ()->Long)
   val old=f.feature!!.demand()!!
   val publishing=Thread{f.owner.advanceInputRevision();f.owner.presentation(f.view,true,false)};publishing.start()
   assertTrue(entered.await(2,java.util.concurrent.TimeUnit.SECONDS))
   val next=f.feature.directive!!.copy(activationId="new-H",ownerEpoch="new-A",wakeRevision=9,goal=ActivationGoal.PROJECTED)
   f.feature.accept(ActivationReply(next));go.countDown();publishing.join(2000);assertFalse(publishing.isAlive)
   assertNull(f.feature.presentation)
   f.feature.report(old,999,ReceiverUsabilityMode.PROJECTED,"old source")
   assertNull(f.feature.presentation)
  }
 }

 @Test fun actualNewIntentParserAndValidationKeepOriginalTupleAndExitAuthority(){
  retained.Fixture(withFeature=true).use{f->
   val ui=ReceiverUiFeedbackSource(f.owner,temporary.root,f.feature)
   f.owner.attach(ui,f.first);f.streaming();ui.selectLocal()
   f.feature!!.accept(ActivationReply(f.feature.directive!!.copy(wakeRevision=3,goal=ActivationGoal.PROJECTED)))
   val d=f.feature.demand()!!
   fun intent(value:ReceiverDemand)=android.content.Intent().putExtra(ReceiverWake.ACTIVATION,value.activationId)
    .putExtra(ReceiverWake.OWNER,value.ownerEpoch).putExtra(ReceiverWake.REVISION,value.wakeRevision)
    .putExtra(ReceiverWake.INCARNATION,value.incarnation.id).putExtra(ReceiverWake.SEQUENCE,value.incarnation.sequence)
    .putExtra(ReceiverWake.GOAL,value.goal.name)
   for(stale in listOf(d.copy(activationId="old-H"),d.copy(ownerEpoch="old-A"),d.copy(wakeRevision=2),
    d.copy(incarnation=d.incarnation.copy(id="old-inc")),d.copy(goal=ActivationGoal.USABLE))){
    ui.onNewIntent(intent(stale));assertTrue(ui.panel)
   }
   val valid=intent(d);ui.onNewIntent(valid);assertSame(valid,ui.delivered());assertFalse(ui.panel)
   f.pumpUntil("actual typed new Intent restores projected retained pixels"){f.feature.presentation?.mode==ReceiverUsabilityMode.PROJECTED}
   ui.selectLocal();ui.pendingExit()
   val next=f.feature.directive!!.copy(wakeRevision=4);f.feature.accept(ActivationReply(next))
   ui.onNewIntent(intent(f.feature.demand()!!));assertTrue(ui.panel);assertSame(valid,ui.delivered())
  }
 }

}
