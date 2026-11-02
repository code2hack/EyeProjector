package com.code2hack.eyeprojector.directrg

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.SystemClock
import com.code2hack.eyeprojector.protocol.FunctionIntentKind.*
import org.junit.Assert.*
import org.junit.Test

/** Actual BroadcastReceiver/grammar on host Android boundaries; not a firmware trace. */
class NativeFunctionIntentTest {
    private class Rig {
        val context = Context()
        var owner: Any? = "original"
        var hover = 10
        val gestures = mutableListOf<FunctionGestureRecognizer.Gesture>()
        val input = NativeFunctionInput(context, { owner }, gestures::add, capture = { it to hover })
        init { SystemClock.time = 1000; Handler.tasks.clear(); input.start() }
        fun report(name: String, ordered: Boolean = true, flags: Int = 0) {
            val action = if (name == "TRIPLE") "com.android.action.ACTION_BOLON_PAIRING" else
                "com.android.action.ACTION_SPRITE_BUTTON_$name"
            context.receiver!!.apply {
                isOrderedBroadcast = ordered; resultCode = 99; aborted = false
                onReceive(context, Intent(action).addFlags(flags))
            }
        }
        fun press() { report("DOWN"); SystemClock.time += 20; report("UP") }
    }

    @Test fun registeredNativeEdgesAndDuplicateSummariesDeliverTwoCapturedIntentions() {
        for (api in listOf(32, 33, 35)) {
            Build.VERSION.SDK_INT = api
            val r = Rig(); r.input.start()
            assertEquals(1, r.context.registrations)
            assertEquals(6, r.context.filter!!.actions.size)
            assertEquals(if (api >= 33) Context.RECEIVER_EXPORTED else null, r.context.registerFlags)
            r.report("DOWN"); r.hover = 20; r.report("UP")
            r.press(); repeat(3) { r.report("DOUBLE_CLICK"); r.report("CLICK") }
            Handler.advance(500)
            assertEquals(listOf(ACTIVATE, ACTIVATE), r.gestures.map { it.kind })
            assertEquals(listOf("original" to 10, "original" to 20), r.gestures.map { it.original })
            assertEquals(r.gestures[0].gestureId + 1, r.gestures[1].gestureId)
            assertTrue(r.context.receiver!!.aborted)
            assertEquals(Activity.RESULT_CANCELED, r.context.receiver!!.resultCode)
            r.input.stop(); r.input.stop(); assertEquals(1, r.context.unregistrations)
        }
    }

    @Test fun longNativeReportsAndReleaseEmitOnlyRegistration() {
        val r = Rig(); r.report("DOWN"); Handler.advance(800)
        r.report("LONG_PRESS"); r.report("UP"); r.report("CLICK"); Handler.advance(1000)
        assertEquals(listOf(REGISTER), r.gestures.map { it.kind })
        r.press(); Handler.advance(500)
        assertEquals(listOf(REGISTER, ACTIVATE), r.gestures.map { it.kind })
    }

    @Test fun nonAbortableBroadcastsDoNotClaimSuppression() {
        for ((ordered, flags) in listOf(false to 0, true to Intent.FLAG_RECEIVER_NO_ABORT)) {
            val r = Rig(); r.report("CLICK", ordered, flags)
            assertFalse(r.context.receiver!!.aborted); assertEquals(99, r.context.receiver!!.resultCode)
            Handler.advance(500); assertEquals(listOf(ACTIVATE), r.gestures.map { it.kind }); r.input.stop()
        }
    }

    @Test fun originalOwnerLossAndStopRetirePendingReportsAndCallbacks() {
        val r = Rig(); r.press(); val stale = Handler.tasks.toList()
        r.owner = "successor"; Handler.advance(500); assertTrue(r.gestures.isEmpty())
        r.report("DOWN"); r.input.stop(); stale.forEach { it.run.run() }
        assertTrue(r.gestures.isEmpty())
        r.input.start(); r.press(); Handler.advance(500)
        assertEquals(listOf(ACTIVATE), r.gestures.map { it.kind })
    }
}
