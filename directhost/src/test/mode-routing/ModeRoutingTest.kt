package com.code2hack.eyeprojector.directhost.input

import android.content.Context
import com.code2hack.eyeprojector.directhost.anchor.position.*
import com.code2hack.eyeprojector.directhost.IPhoneInputSession
import com.code2hack.eyeprojector.protocol.*
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

/** Production owner + production router + actual merger, including concurrent committed emissions. */
class ModeRoutingTest {
    private class Fixture {
        val lock=Any()
        var app: String?="app.one"
        var now=1000L
        val saved=mutableMapOf<String,List<AnchorPosition>>()
        var failWrite=false
        var writes=0
        val owner=PhoneModeOwner(Context(File("unused")),CoordinateAnchorController(object: AnchorPositionStorage {
            override fun read(packageName: String)=saved[packageName].orEmpty()
            override fun write(packageName: String,positions: List<AnchorPosition>) {
                assertFalse(Thread.holdsLock(lock));if(failWrite) throw IOException("disk failed")
                writes++;saved[packageName]=positions.toList()
            }
        }))
        val states=mutableListOf<CoordinateModeResult>()
        fun decide(operation: CoordinateOperation,point: Tap?=null): CoordinateModeResult {
            assertFalse(Thread.holdsLock(lock))
            return CoordinateModeResult.from(owner.decide("owner","projection",app,operation.ordinal,point?.x?.toDouble() ?: 0.0,
                point?.y?.toDouble() ?: 0.0)!!).also { states.add(it) }
        }
        val size=Geometry(480,640)
        val touches=mutableListOf<Touch>()
        val taps=mutableListOf<Tap>()
        val scrolls=mutableListOf<Scroll>()
        val navigation=mutableListOf<Navigation>()
        val router=CoordinateInputRouter(::decide,{ assertFalse(Thread.holdsLock(lock));touches.add(it);true },
            { assertFalse(Thread.holdsLock(lock));taps.add(it);true },
            { assertFalse(Thread.holdsLock(lock));scrolls.add(it);true })
        var blockedMode: (() -> Unit)?=null
        val merger=PhoneInputMerger(lock,{now},{_,_->{}},{size},router::touch,{router.scroll(it)},
            { kind,_->assertFalse(Thread.holdsLock(lock));navigation.add(kind) },{}, {},
            hidMode={ blockedMode?.invoke();decide(CoordinateOperation.CYCLE) },
            hidAction={ action,point->
                val operation=when(action) {
                    PhoneHidAction.TAP->CoordinateOperation.TAP
                    PhoneHidAction.BACK->CoordinateOperation.BACK
                    PhoneHidAction.SCROLL_DOWN->CoordinateOperation.SCROLL_DOWN
                    PhoneHidAction.SCROLL_UP->CoordinateOperation.SCROLL_UP
                    else->CoordinateOperation.SNAPSHOT
                }
                val result=decide(operation,point.target())
                operation != CoordinateOperation.SNAPSHOT && result.consumed
            })
        init {
            owner.onSession("owner","projection",object: IPhoneInputSession {
                override fun active()=true
                override fun edge(routeId:Long,typedEdge:ByteArray,regionId:String,word:Long) { error("No transport in owner test") }
            })
            merger.pointer(HeadPointerState(1,1,true,true,120f,230f,Fit(0,0,480,640),size)) }
        fun edge(action: PhoneHidAction,phase: PhoneHidPhase,at: Long=now)=PhoneHidEdge(action,phase,1,
            action.ordinal+10,action.ordinal+10,1000,at,gesture=PhoneHidGesture.LONG)
        fun modeDown()=merger.edge(100,edge(PhoneHidAction.MODE,PhoneHidPhase.DOWN))
        fun modeUp()=merger.edge(100,edge(PhoneHidAction.MODE,PhoneHidPhase.UP,1800))
        fun committed(): Long=synchronized(lock) {
            merger.javaClass.getDeclaredField("completionSequence").apply { isAccessible=true }.getLong(merger)
        }
        fun awaitCommitted(count: Long) {
            val limit=System.nanoTime()+TimeUnit.SECONDS.toNanos(2)
            while(committed()<count && System.nanoTime()<limit) Thread.yield()
            assertTrue("later operation admitted while Mode delivery blocked",committed()>=count)
        }
    }
    private fun blockedModeThen(action: PhoneHidAction) {
        val f=Fixture();val entered=CountDownLatch(1);val release=CountDownLatch(1)
        f.blockedMode={entered.countDown();check(release.await(4,TimeUnit.SECONDS))}
        f.modeDown()
        val mode=Thread { f.modeUp() }
        val later=Thread {
            f.merger.edge(200,f.edge(action,PhoneHidPhase.DOWN))
            if(action==PhoneHidAction.BACK) f.merger.edge(200,f.edge(action,PhoneHidPhase.UP,1800))
        }
        val tapUp=Thread { f.merger.edge(200,f.edge(action,PhoneHidPhase.UP,1850)) }
        try {
            mode.start();assertTrue(entered.await(2,TimeUnit.SECONDS))
            val before=f.committed();later.start()
            if(action==PhoneHidAction.BACK) {
                // DOWN has no emission; UP completes the learned long gesture.
                f.awaitCommitted(before+1)
            } else f.awaitCommitted(before+1)
            if(action==PhoneHidAction.TAP) { tapUp.start();f.awaitCommitted(before+2) }
            assertTrue(f.states.isEmpty())
            release.countDown();mode.join(2000);later.join(2000);tapUp.join(2000)
            assertFalse(mode.isAlive || later.isAlive || tapUp.isAlive)
            assertEquals(AnchorInteractionMode.ANCHOR_SETTINGS,f.states.first().state.mode)
            assertTrue(f.states.all { it.state.mode==AnchorInteractionMode.ANCHOR_SETTINGS })
            assertTrue(f.touches.isEmpty());assertTrue(f.navigation.isEmpty());assertTrue(f.scrolls.isEmpty())
            assertEquals(if(action==PhoneHidAction.TAP) 1 else 0,f.writes)
        } finally { release.countDown();mode.join(2000);later.join(2000);tapUp.join(2000) }
    }
    @Test(timeout=7000) fun blockedModePrecedesWholeTapContact()=blockedModeThen(PhoneHidAction.TAP)
    @Test(timeout=7000) fun blockedModePrecedesBackDecision()=blockedModeThen(PhoneHidAction.BACK)
    @Test(timeout=7000) fun blockedModePrecedesScrollDecision()=blockedModeThen(PhoneHidAction.SCROLL_DOWN)
    @Test fun settingsNativeContactFailureAndLifoNeverInjectAppInput() {
        val f=Fixture();f.decide(CoordinateOperation.CYCLE)
        val down=Touch(Touch.Phase.DOWN,99,Tap(40,50,f.size))
        assertTrue(f.router.touch(down));assertTrue(f.router.touch(down.copy(phase=Touch.Phase.UP)))
        assertEquals(listOf(AnchorPosition(40.0,50.0)),f.saved[f.app])
        assertTrue(f.touches.isEmpty())
        f.failWrite=true
        assertTrue(f.router.tap(Tap(100,200,f.size)));assertNotNull(f.states.last().failure)
        assertEquals(1,f.saved[f.app]!!.size)
        f.failWrite=false;assertTrue(f.router.back());assertTrue(f.saved[f.app]!!.isEmpty())
        assertTrue(f.router.back());assertTrue(f.taps.isEmpty())
    }
    @Test fun ordinaryHeldDragAndPinnedAnchorUseSameContactOutput() {
        val f=Fixture();val down=Touch(Touch.Phase.DOWN,3,Tap(110,120,f.size))
        f.router.touch(down);f.router.touch(down.copy(phase=Touch.Phase.MOVE,target=Tap(140,150,f.size)))
        f.router.touch(down.copy(phase=Touch.Phase.UP));assertEquals(3,f.touches.size)
        f.decide(CoordinateOperation.CYCLE);f.router.tap(Tap(30,40,f.size));f.decide(CoordinateOperation.CYCLE)
        f.decide(CoordinateOperation.CYCLE)
        assertEquals(AnchorInteractionMode.ANCHOR,f.states.last().state.mode)
        f.router.touch(down);f.router.touch(down.copy(phase=Touch.Phase.UP))
        assertEquals(Tap(30,40,f.size),f.touches.last().target)
        f.router.scroll(Scroll(Tap(30,40,f.size),160),passive=true);assertTrue(f.scrolls.isEmpty())
    }
    @Test fun appSwitchHomeAndRestartKeepOnlySavedPositions() {
        val f=Fixture();f.decide(CoordinateOperation.CYCLE);f.router.tap(Tap(15,25,f.size))
        f.app="app.two";assertEquals(AnchorInteractionMode.NORMAL,f.decide(CoordinateOperation.SNAPSHOT).state.mode)
        f.app="app.one";assertEquals(AnchorInteractionMode.ANCHOR_SETTINGS,f.decide(CoordinateOperation.SNAPSHOT).state.mode)
        f.app=null;assertEquals(CoordinateAnchorState(),f.decide(CoordinateOperation.CYCLE).state)
        val restarted=CoordinateAnchorController(object: AnchorPositionStorage {
            override fun read(packageName:String)=f.saved[packageName].orEmpty()
            override fun write(packageName:String,positions:List<AnchorPosition>)=error("No restart write")
        })
        val result=restarted.dispatch(AnchorModeAction.AppChanged("app.one"))
        assertEquals(AnchorInteractionMode.NORMAL,result.state.mode);assertEquals(1,result.state.positions.size)
    }
    @Test fun matchedWireCarriesStateAndRejectsRemovedGateChannels() {
        val state=CoordinateAnchorState("app",AnchorInteractionMode.ANCHOR_SETTINGS,listOf(AnchorPosition(12.5,40.5)))
        val result=CoordinateModeResult(state,true,"save failure")
        val buffer=java.io.ByteArrayOutputStream()
        Wire.writeFrame(java.io.DataOutputStream(buffer),Frame(Wire.COORDINATE_STATE,payload=result.bytes()))
        val decoded=Wire.readFrame(java.io.DataInputStream(buffer.toByteArray().inputStream()))
        assertEquals(result,CoordinateModeResult.from(decoded.payload))
        for(channel in listOf(25,26,28)) assertThrows(Exception::class.java) { Wire.writeFrame(java.io.DataOutputStream(java.io.ByteArrayOutputStream()),Frame(channel)) }
        assertThrows(Exception::class.java) { Wire.writeFrame(java.io.DataOutputStream(java.io.ByteArrayOutputStream()),Frame(Wire.MODE_CYCLE,payload=byteArrayOf(1))) }
    }
    @Test fun replacedOwnerBindingCannotApplyOldSessionModeCommand() {
        val f=Fixture()
        assertNull(f.owner.decide("old-owner","projection",f.app,CoordinateOperation.CYCLE.ordinal,0.0,0.0))
        assertEquals(AnchorInteractionMode.NORMAL,f.decide(CoordinateOperation.SNAPSHOT).state.mode)
        f.owner.invalidateBinding()
        assertNull(f.owner.decide("owner","projection",f.app,CoordinateOperation.CYCLE.ordinal,0.0,0.0))
    }
}
