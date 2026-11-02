package com.code2hack.eyeprojector.directrg

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.os.Handler
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import com.code2hack.eyeprojector.protocol.*
import org.junit.Assert.*
import org.junit.Test

/** Actual receiver, recognizer, menu rendering and chooser; Android draw/report delivery is modeled. */
class RegistrationMenuRenderingTest {
    private class Rig {
        val context=Context();val source=Any();var current:Any?="projected"
        val sent=mutableListOf<AnchorRegistrationAction>();val gestures=mutableListOf<AnchorGesture>()
        val fact=AnchorIntentContext(ReceiverUsability("owner",ReceiverIncarnation("receiver",1),1,4,ReceiverUsabilityMode.PROJECTED),
            HeadPointerState(4,1,true,true,240f,320f,Fit(0,0,480,640),Geometry(480,640)))
        fun original(g:AnchorGesture)=AnchorIntent(g.kind,AnchorSource.GLASSES_NATIVE,g.gestureId,g.sequence,fact)
        val controller=AnchorRegistrationController<Any>{_,_,a->sent.add(a);true}
        val overlay=AnchorRegistrationOverlay(context,controller::highlight,controller::presented)
        val input=NativeFunctionInput(context,{current},{g->gestures.add(g);if(g.kind==AnchorIntentKind.REGISTER)controller.registration(source,original(g),g.physicalHeld)},
            registrationRelease={g,c->controller.released(source,original(g),c);Unit})
        init {SystemClock.time=1000;Handler.tasks.clear();input.start()}
        fun report(name:String){context.receiver!!.onReceive(context,Intent("com.android.action.ACTION_SPRITE_BUTTON_$name"))}
        fun held(){report("DOWN");Handler.advance(800);assertTrue(gestures.single().physicalHeld)}
        fun show(bounds:AnchorRegistrationHighlight=AnchorRegistrationHighlight(200.0,300.0,280.0,340.0),
            downX:Float=240f,downY:Float=320f) {
            val i=original(gestures.last())
            assertTrue(controller.show(source,7,fact,bounds,i));assertTrue(controller.enteredChooser(source,fact.usability.incarnation,fact.pointer.geometry,5))
            input.prepareCancel(preserveRegistration=true).finishOriginal();current=null
            overlay.show(controller.offer!!.highlight,downX,downY)
        }
    }
    @Test fun edgeTargetsAndOriginalDownPositionsKeepMenuCentredAndTargetPinnedWhileHeadSelects() {
        val targets=listOf(AnchorRegistrationHighlight(0.0,0.0,10.0,10.0),
            AnchorRegistrationHighlight(470.0,0.0,480.0,10.0),
            AnchorRegistrationHighlight(0.0,630.0,10.0,640.0),
            AnchorRegistrationHighlight(470.0,630.0,480.0,640.0),
            AnchorRegistrationHighlight(0.0,0.0,480.0,640.0))
        for(bounds in targets) {
            val r=Rig();r.held();r.show(bounds,bounds.left.toFloat(),bounds.top.toFloat())
            r.overlay.width=480;r.overlay.height=640
            for((index,action) in AnchorRegistrationAction.entries.withIndex()) {
                r.overlay.pointer(132f+(index+0.5f)*72f,320f,true)
                val canvas=Canvas();r.overlay.draw(canvas)
                assertEquals(listOf(bounds.left.toFloat(),bounds.top.toFloat(),bounds.right.toFloat(),bounds.bottom.toFloat()),canvas.rectangles.first())
                assertEquals(listOf(132f,298f,204f,342f),canvas.rectangles[1])
                assertEquals(listOf(276f,298f,348f,342f),canvas.rectangles[3])
                assertEquals(action,r.controller.offer!!.highlighted)
                assertEquals(AnchorRegistrationAction.entries.map { if(it==action)Color.YELLOW else Color.argb(235,0,0,0) },canvas.rectangleColors.drop(1))
                assertTrue(r.sent.isEmpty())
            }
            r.overlay.pointer(168f,320f,true);r.report("UP")
            assertEquals(listOf(AnchorRegistrationAction.CLICK),r.sent);r.input.stop()
        }
    }
    @Test fun viewportResizeRecentresMenuAndRechecksStationaryHeadWithoutMovingPinnedTarget() {
        val r=Rig();r.held();r.show(AnchorRegistrationHighlight(0.0,0.0,10.0,10.0),0f,0f)
        r.overlay.width=480;r.overlay.height=640;r.overlay.pointer(240f,320f,true)
        r.overlay.draw(Canvas());assertEquals(AnchorRegistrationAction.HOLD,r.controller.offer!!.highlighted)
        r.overlay.width=640
        val canvas=Canvas();r.overlay.draw(canvas)
        assertEquals(listOf(212f,298f,284f,342f),canvas.rectangles[1])
        assertEquals(listOf(0f,0f,10f,10f),canvas.rectangles.first())
        assertEquals(AnchorRegistrationAction.CLICK,r.controller.offer!!.highlighted)
        assertTrue(r.sent.isEmpty());r.report("UP")
        assertEquals(listOf(AnchorRegistrationAction.CLICK),r.sent);r.input.stop()
    }
    @Test fun measuredDrawDisplaysAllChoicesAndOriginalNativeUpConfirmsHeadHighlightWithoutTouch() {
        for(action in AnchorRegistrationAction.entries) {
            val r=Rig();r.held();r.show()
            val m=AnchorRegistrationMenu.layout(480,640,1f)
            r.overlay.pointer(m.left+(action.ordinal+0.5f)*m.width/3,m.top+m.height/2,true)
            val unmeasured=Canvas();r.overlay.draw(unmeasured)
            assertFalse(r.controller.offer!!.ready);assertTrue(unmeasured.rectangles.all{it[2]>=it[0]})
            r.overlay.width=480;r.overlay.height=640
            val canvas=Canvas();r.overlay.draw(canvas)
            assertEquals(listOf("Click","Hold","Cancel"),canvas.labels)
            assertTrue(r.controller.offer!!.ready);assertEquals(action,r.controller.offer!!.highlighted)
            assertTrue(r.overlay.onTouchEvent(MotionEvent(MotionEvent.ACTION_UP)))
            assertTrue(r.overlay.performClick());assertTrue(r.sent.isEmpty())
            r.report("UP");assertEquals(listOf(action),r.sent)
            r.report("UP");r.report("CLICK");assertEquals(1,r.sent.size);assertEquals(1,r.gestures.size)
            r.input.stop()
        }
    }
    @Test fun physicalReleaseBeforeFirstDrawOrNoLiveHeadChoiceCancelsWithoutModeDefault() {
        for(beforeDraw in listOf(false,true)) {
            val r=Rig();r.held();r.show();r.overlay.width=480;r.overlay.height=640
            r.overlay.pointer(240f,320f,beforeDraw)
            if(!beforeDraw)r.overlay.draw(Canvas())
            r.report("UP")
            assertEquals(listOf(AnchorRegistrationAction.CANCEL),r.sent)
            r.overlay.draw(Canvas());assertEquals(1,r.sent.size);r.input.stop()
        }
    }
    @Test fun retiredPresentationCannotHideSuccessorAndNativeOriginalCleanupDoesNotEraseNewRelease() {
        val r=Rig();r.held();r.show();r.overlay.width=480;r.overlay.height=640;r.overlay.draw(Canvas())
        val old=r.overlay.prepareDismiss();r.overlay.show(AnchorRegistrationHighlight(470.0,630.0,480.0,640.0))
        r.overlay.pointer(470f,630f,true);old.finishOriginal();assertTrue(old.superseded());assertEquals(View.VISIBLE,r.overlay.visibility)
        val canvas=Canvas();r.overlay.draw(canvas)
        assertEquals(3,canvas.labels.size)
        assertTrue(canvas.rectangles.drop(1).all{it[0]>=0&&it[1]>=0&&it[2]<=480&&it[3]<=640})
        r.input.stop()
    }
}
