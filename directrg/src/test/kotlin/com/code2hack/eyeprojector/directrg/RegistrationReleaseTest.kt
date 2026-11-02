package com.code2hack.eyeprojector.directrg

import com.code2hack.eyeprojector.protocol.*
import com.code2hack.eyeprojector.directhost.input.PhoneInputMerger
import org.junit.Assert.*
import org.junit.Test

class RegistrationReleaseTest {
    private val geometry = Geometry(480, 640)
    private val context = FunctionIntentContext(ReceiverUsability("owner", ReceiverIncarnation("receiver", 1), 1, 4, ReceiverUsabilityMode.PROJECTED),
        HeadPointerState(4, 1, true, true, 240f, 320f, Fit(0, 0, 480, 640), geometry))
    private fun intent(source: FunctionSource, id: Long = 1) = FunctionIntent(FunctionIntentKind.REGISTER, source, id, id, context)
    private class Clock {
        var now = 0L
        data class Task(val at: Long, val run: () -> Unit, var canceled: Boolean = false)
        val tasks = mutableListOf<Task>()
        fun schedule(at: Long, run: () -> Unit): () -> Unit {
            val task = Task(at, run); tasks.add(task); return { task.canceled = true }
        }
        fun advance(at: Long) { now = at; while (true) {
            val task = tasks.firstOrNull { !it.canceled && it.at <= at } ?: return
            task.canceled = true; task.run()
        } }
    }
    @Test fun realHeldReleaseSurvivesChooserRevisionAndSummariesNeverConfirm() {
        val clock = Clock(); var owner: Any? = Any()
        val original = Any(); val gestures = mutableListOf<FunctionGestureRecognizer.Gesture>()
        val terminals = mutableListOf<Pair<FunctionGestureRecognizer.Gesture,Boolean>>()
        val r = FunctionGestureRecognizer({ clock.now }, { owner }, clock::schedule, gestures::add,
            capture = { original }, registrationRelease = { g, canceled -> terminals.add(g to canceled) })
        r.accept(FunctionGestureRecognizer.Report.DOWN); clock.advance(800)
        assertTrue(gestures.single().physicalHeld)
        val registered = gestures.single()
        owner = null; r.cancel(preserveRegistration = true)
        for (report in listOf(FunctionGestureRecognizer.Report.CLICK, FunctionGestureRecognizer.Report.DOUBLE, FunctionGestureRecognizer.Report.LONG)) r.accept(report)
        assertTrue(terminals.isEmpty())
        r.accept(FunctionGestureRecognizer.Report.UP)
        assertEquals(listOf(registered to false), terminals)
        r.accept(FunctionGestureRecognizer.Report.UP)
        assertEquals(1, gestures.size); assertEquals(1, terminals.size)
        assertSame(original, registered.original)
    }
    @Test fun summariesAndLongClassifiedAtAlreadyReleasedUpHaveNoHeldLease() {
        for (report in listOf(FunctionGestureRecognizer.Report.LONG, FunctionGestureRecognizer.Report.UP)) {
            val clock = Clock(); val output = mutableListOf<FunctionGestureRecognizer.Gesture>()
            val terminals = mutableListOf<Boolean>()
            val r = FunctionGestureRecognizer({ clock.now }, { "context" }, clock::schedule, output::add,
                registrationRelease = { _, canceled -> terminals.add(canceled) })
            if (report == FunctionGestureRecognizer.Report.UP) { r.accept(FunctionGestureRecognizer.Report.DOWN); clock.now = 800 }
            r.accept(report)
            assertEquals(FunctionIntentKind.REGISTER, output.single().kind)
            assertFalse(output.single().physicalHeld)
            assertTrue(terminals.isEmpty())
        }
    }
    @Test fun genuineCancellationRetiresOriginalReleaseOnlyOnceAndFreshInputRecovers() {
        val clock = Clock(); val output = mutableListOf<FunctionGestureRecognizer.Gesture>(); val terminals = mutableListOf<Boolean>()
        val r = FunctionGestureRecognizer({ clock.now }, { "context" }, clock::schedule, output::add,
            edgeOnly = true, registrationRelease = { _, canceled -> terminals.add(canceled) })
        r.accept(FunctionGestureRecognizer.Report.DOWN); clock.advance(800); r.cancel(); r.cancel()
        r.accept(FunctionGestureRecognizer.Report.UP)
        assertEquals(listOf(true), terminals); assertEquals(1, output.size)
        clock.now = 900; r.accept(FunctionGestureRecognizer.Report.DOWN); clock.now = 910; r.accept(FunctionGestureRecognizer.Report.UP)
        assertEquals(listOf(FunctionIntentKind.REGISTER, FunctionIntentKind.ACTIVATE), output.map { it.kind })
    }
    @Test fun sameSourcePressAndTransactionReleaseCommitsOnlyHighlightedMenuOnce() {
        for (source in FunctionSource.entries) for (action in AnchorRegistrationAction.entries) {
            val session = Any(); val original = intent(source); val sent = mutableListOf<AnchorRegistrationAction>()
            val c = AnchorRegistrationController<Any> { s, id, choice -> assertSame(session,s); assertEquals(7L,id); sent.add(choice); true }
            c.registration(session, original, true)
            assertTrue(c.show(session,7,context,AnchorRegistrationHighlight(200.0,300.0,280.0,340.0),original))
            assertTrue(c.enteredChooser(session,context.usability.incarnation,geometry,5))
            c.presented(); c.highlight(action)
            assertTrue(sent.isEmpty())
            assertFalse(c.released(Any(),original)); assertFalse(c.released(session,original.copy(gestureId=99)))
            assertTrue(c.released(session,original)); assertFalse(c.released(session,original))
            assertEquals(listOf(action),sent)
        }
    }
    @Test fun releaseBeforeMenuReadyOrOutsideChoicesCancelsAndLateOriginalCannotSaveSuccessor() {
        val session=Any(); val original=intent(FunctionSource.GLASSES_NATIVE)
        for (ready in listOf(false,true)) {
            val sent=mutableListOf<AnchorRegistrationAction>();val c=AnchorRegistrationController<Any>{_,_,a->sent.add(a);true}
            c.registration(session,original,true);c.show(session,1,context,AnchorRegistrationHighlight(1.0,1.0,50.0,50.0),original)
            c.highlight(if(ready)null else AnchorRegistrationAction.HOLD);if(ready)c.presented()
            assertTrue(c.released(session,original));assertEquals(listOf(AnchorRegistrationAction.CANCEL),sent)
            val next=original.copy(gestureId=2,sequence=2);c.registration(session,next,true)
            assertTrue(c.show(session,2,context,AnchorRegistrationHighlight(1.0,1.0,50.0,50.0),next));c.presented();c.highlight(AnchorRegistrationAction.CLICK)
            assertFalse(c.released(session,original));assertEquals(1,sent.size)
            assertTrue(c.released(session,next));assertEquals(AnchorRegistrationAction.CLICK,sent.last())
        }
        for(source in FunctionSource.entries) {
            val i=intent(source);val c=AnchorRegistrationController<Any>{_,_,_->error("No offer may write")}
            c.registration(session,i,true);c.released(session,i)
            assertFalse(c.show(session,3,context,AnchorRegistrationHighlight(1.0,1.0,2.0,2.0),i))
        }
    }
    @Test fun menuUsesTargetAndViewportAndHeadHitTestingWithoutDefaultChoice() {
        for(target in listOf(AnchorRegistrationHighlight(0.0,0.0,10.0,10.0),AnchorRegistrationHighlight(470.0,630.0,480.0,640.0))) {
            val m=AnchorRegistrationMenu.layout(target,480,640,1f)
            assertTrue(m.left>=0&&m.top>=0&&m.left+m.width<=480&&m.top+m.height<=640)
            for((i,a) in AnchorRegistrationAction.entries.withIndex())assertEquals(a,m.at(m.left+(i+0.5f)*m.width/3,m.top+m.height/2))
            assertNull(m.at(m.left-1,m.top));assertNull(m.at(m.left+m.width,m.top))
        }
    }
    @Test fun actualHidInputRetainsExactPhysicalTerminalAcrossChooserCancellation() {
        val clock=Clock();var owner:Any?=Any();val bindings=HidBindings({null},{true})
        val binding=HidBinding(HidButton(HidDeviceIdentity("external",1,2),3,4),"Remote","Key")
        bindings.assign(HidAction.ANCHOR,binding)
        val output=mutableListOf<FunctionGestureRecognizer.Gesture>();val terminals=mutableListOf<Boolean>()
        val input=HidKeyInput(bindings,{clock.now},{owner},clock::schedule,{error("No pointer required")},
            {_,_->error("No contact")},{_,_->error("No cancel contact")},{_,_,_->error("No scroll")},output::add,
            registrationRelease={_,c->terminals.add(c)})
        input.accept(HidKeyInput.Event(9,binding,HidKeyInput.Phase.DOWN,0,0));clock.advance(800)
        assertTrue(output.single().physicalHeld)
        owner=null;input.prepareCancel(preserveRegistration=true).finishOriginal()
        assertFalse(input.consumeRetired(10,3,4,HidKeyInput.Phase.UP,0))
        assertTrue(terminals.isEmpty())
        assertTrue(input.consumeRetired(9,3,4,HidKeyInput.Phase.UP,0))
        assertEquals(listOf(false),terminals)
        input.accept(HidKeyInput.Event(9,binding,HidKeyInput.Phase.UP,0,900))
        assertEquals(1,terminals.size);assertEquals(1,output.size)
    }
    @Test fun phoneOriginalReleaseSurvivesOnlyMatchingChooserAndOutputsOutsideControlLock() {
        for(cancel in listOf(false,true)) {
            val clock=Clock();val lock=Any();val intents=mutableListOf<FunctionIntent>();val terminals=mutableListOf<Pair<FunctionIntent,Boolean>>()
            val m=PhoneInputMerger(lock,{clock.now},clock::schedule,{geometry},{error("No touch")},{error("No scroll")},
                {_,_->error("No navigation")},{},{error("No receiver action")},functionIntent=intents::add,
                registrationRelease={i,c->assertFalse(Thread.holdsLock(lock));terminals.add(i to c)})
            m.pointer(context.pointer,context.usability)
            val down=PhoneHidEdge(PhoneHidAction.ANCHOR,PhoneHidPhase.DOWN,8,3,4,0,0)
            m.edge(7,down);clock.advance(800);val original=intents.single()
            val local=context.usability.copy(revision=2,inputRevision=5,mode=ReceiverUsabilityMode.LOCAL_PANEL)
            m.pointer(context.pointer.copy(revision=5,sequence=2,projected=false),local)
            assertTrue(terminals.isEmpty())
            if(cancel)m.invalidate()
            m.edge(7,down.copy(phase=PhoneHidPhase.UP,eventTimeMs=900));m.edge(7,down.copy(phase=PhoneHidPhase.UP,eventTimeMs=901))
            assertEquals(listOf(original to cancel),terminals);assertEquals(1,intents.size)
        }
    }
}
