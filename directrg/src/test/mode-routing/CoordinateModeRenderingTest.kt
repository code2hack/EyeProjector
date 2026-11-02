package com.code2hack.eyeprojector.directrg

import android.content.Context
import android.graphics.Canvas
import com.code2hack.eyeprojector.protocol.*
import org.junit.Assert.*
import org.junit.Test

/** Actual native adapter/recognizer and overlay/model; OS acquisition/Canvas are host boundaries. */
class CoordinateModeRenderingTest {
    @Test fun rawShortOnceLongNoopAndOemSummariesNeverDoubleAdvance() {
        val context=Context();val session=Any();var cycles=0
        val input=NativeFunctionInput(context,{session},{assertSame(session,it);cycles++})
        input.start();val source=Any();input.rawSourceAvailable(source)
        input.rawEdge(source,1,true,1000,input.captureRawOriginal())
        context.report("com.android.action.ACTION_SPRITE_BUTTON_CLICK")
        input.rawEdge(source,1,false,1100,null)
        context.report("com.android.action.ACTION_SPRITE_BUTTON_CLICK")
        input.rawEdge(source,1,false,1100,null)
        assertEquals(1,cycles)
        input.rawEdge(source,2,true,2000,input.captureRawOriginal())
        context.report("com.android.action.ACTION_SPRITE_BUTTON_LONG_PRESS")
        input.rawEdge(source,2,false,3000,null)
        assertEquals(1,cycles)
        input.rawEdge(source,3,true,4000,input.captureRawOriginal());input.rawSourceLost(source)
        input.rawEdge(source,3,false,4050,null);assertEquals(1,cycles)
        input.stop();assertTrue(context.receivers.isEmpty())
    }
    @Test fun rawCaptureCannotActAfterSessionReplacementOrCancellation() {
        val context=Context();var session:Any=Any();var cycles=0
        val input=NativeFunctionInput(context,{session},{cycles++});input.start();val source=Any();input.rawSourceAvailable(source)
        val captured=input.captureRawOriginal();input.rawEdge(source,1,true,1000,captured)
        session=Any();input.rawEdge(source,1,false,1010,null);assertEquals(0,cycles)
        val retired=input.captureRawOriginal()!!;retired.discard()
        input.rawEdge(source,2,true,2000,retired);input.rawEdge(source,2,false,2010,null);assertEquals(0,cycles)
    }
    @Test fun fallbackEdgesAndCompletedDoubleDescribeTwoShortsNotThree() {
        var now=1000L;var owner:Any?=Any();var cycles=0
        data class Job(val run:()->Unit,var canceled:Boolean=false)
        val jobs=mutableListOf<Job>()
        val input=FunctionGestureRecognizer({now},{owner},{_,action->
            val job=Job(action);jobs.add(job);{job.canceled=true}
        },{cycles++})
        input.accept(FunctionGestureRecognizer.Report.DOWN);now=1020;input.accept(FunctionGestureRecognizer.Report.UP)
        input.accept(FunctionGestureRecognizer.Report.CLICK);input.accept(FunctionGestureRecognizer.Report.DOUBLE)
        now=1600;jobs.toList().filterNot{it.canceled}.forEach{it.run()};assertEquals(2,cycles)
        input.accept(FunctionGestureRecognizer.Report.DOUBLE);assertEquals(2,cycles)
        now=2200;input.accept(FunctionGestureRecognizer.Report.DOWN);input.accept(FunctionGestureRecognizer.Report.LONG)
        now=4000;jobs.toList().filterNot{it.canceled}.forEach{it.run()};assertEquals(2,cycles)
    }
    @Test fun anchorPositionIsBothVisibleAndPublishedPointerWithoutHeadFreshness() {
        val pointer=PointerOverlay(Context());pointer.bounds(100f,50f,580f,690f,0)
        val state=CoordinateAnchorState("app",AnchorInteractionMode.ANCHOR,listOf(AnchorPosition(120.0,230.0)),0)
        pointer.coordinateState(state,Geometry(480,640),true)
        assertEquals(PointerPosition(220f,280f,true),pointer.position)
        assertEquals(pointer.position,pointer.inputSnapshot());assertEquals(pointer.position,pointer.inputPosition())
        assertEquals(Long.MAX_VALUE,pointer.expiresAtNs);assertTrue(pointer.anchorMode)
        pointer.coordinateState(state,Geometry(480,640),false);assertFalse(pointer.anchorMode)
    }
    @Test fun settingsDrawAllPositionsInCreationOrderAndNormalClearsThem() {
        val pointer=PointerOverlay(Context());pointer.bounds(0f,0f,480f,640f,0)
        val state=CoordinateAnchorState("app",AnchorInteractionMode.ANCHOR_SETTINGS,
            listOf(AnchorPosition(10.0,20.0),AnchorPosition(70.0,80.0)))
        pointer.coordinateState(state,Geometry(480,640),true)
        val settings=Canvas();pointer.onDraw(settings);assertEquals(listOf("1","2"),settings.labels)
        assertFalse(pointer.anchorMode)
        pointer.coordinateState(state.copy(mode=AnchorInteractionMode.NORMAL),Geometry(480,640),true)
        val normal=Canvas();pointer.onDraw(normal);assertTrue(normal.labels.isEmpty())
    }
}
