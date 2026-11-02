package com.code2hack.eyeprojector.directhost.input

import com.code2hack.eyeprojector.protocol.*
import org.junit.Assert.*
import org.junit.Test

class PhoneRegistrationReleaseTest {
    private val geometry = Geometry(480, 640)
    private val context = FunctionIntentContext(ReceiverUsability("owner", ReceiverIncarnation("receiver", 1), 1, 4, ReceiverUsabilityMode.PROJECTED),
        HeadPointerState(4, 1, true, true, 240f, 320f, Fit(0, 0, 480, 640), geometry))
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
