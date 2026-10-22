package com.code2hack.eyeprojector.directrg

import android.app.Instrumentation
import android.content.BroadcastReceiver
import android.content.Intent
import android.os.SystemClock
import android.view.SurfaceView
import org.json.JSONObject

/** Reserved-device fixture only: synthetic navigation proves ownership/context, not physical input. */
internal object ConnectedPanelChecks {
    fun run(instrumentation: Instrumentation, activity: ReceiverActivity): JSONObject {
        val report = JSONObject().put("scope", "Synthetic Function adapter and Activity context calls; no firmware/system-suppression or optical claim")
        fun field(target: Any, name: String): Any? = target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)
        fun function(action: String) {
            instrumentation.runOnMainSync {
                val input = field(activity, "functionInput")!!
                val receiver = field(input, "receiver") as BroadcastReceiver
                // Direct adapter delivery does not emit a competing system broadcast.
                receiver.onReceive(activity, Intent("com.android.action.$action"))
            }
        }
        var session: Any? = null
        var decoder: Any? = null
        var surface: android.view.Surface? = null
        instrumentation.runOnMainSync {
            check(activity.projectionContext && activity.inputSnapshot().getBoolean("streaming"))
            session = field(activity, "session")!!
            decoder = field(session!!, "decoder")!!
            surface = (field(activity, "screen") as SurfaceView).holder.surface
        }
        function("ACTION_SPRITE_BUTTON_CLICK")
        function("ACTION_SPRITE_BUTTON_DOUBLE_CLICK")
        function("ACTION_BOLON_PAIRING")
        Thread.sleep(650)
        instrumentation.runOnMainSync {
            check(!activity.projectionContext)
            check(field(activity, "session") === session && field(session!!, "decoder") === decoder)
            check((field(activity, "screen") as SurfaceView).holder.surface === surface && surface!!.isValid)
            val tap = activity.javaClass.getDeclaredMethod("remoteTap", Float::class.javaPrimitiveType, Float::class.javaPrimitiveType, Long::class.javaPrimitiveType).apply { isAccessible = true }
            val scroll = activity.javaClass.getDeclaredMethod("remoteScroll", Float::class.javaPrimitiveType, Float::class.javaPrimitiveType, Int::class.javaPrimitiveType, Long::class.javaPrimitiveType, Boolean::class.javaPrimitiveType).apply { isAccessible = true }
            check(tap.invoke(activity, 100f, 100f, SystemClock.uptimeMillis()) == false)
            check(scroll.invoke(activity, 100f, 100f, 160, SystemClock.uptimeMillis(), false) == false)
            report.put("panelPreservedSession", true).put("panelPreservedDecoder", true).put("panelPreservedSurface", true)
                .put("projectedTapRejectedInPanel", true).put("projectedScrollRejectedInPanel", true)
        }
        function("ACTION_BOLON_PAIRING")
        Thread.sleep(650)
        instrumentation.runOnMainSync { check(!activity.isFinishing && field(activity, "session") === session) }
        // Fresh physical edges are usable immediately after retirement; one press returns locally.
        function("ACTION_SPRITE_BUTTON_DOWN"); function("ACTION_SPRITE_BUTTON_UP")
        Thread.sleep(650)
        instrumentation.runOnMainSync {
            check(activity.projectionContext)
            check(field(activity, "session") === session && field(session!!, "decoder") === decoder)
            check((field(activity, "screen") as SurfaceView).holder.surface === surface)
            report.put("localBackPreservedSessionDecoderSurface", true).put("duplicateTripleDidNotExit", true)
        }
        function("ACTION_SPRITE_BUTTON_DOWN"); function("ACTION_SPRITE_BUTTON_LONG_PRESS"); function("ACTION_SPRITE_BUTTON_UP")
        Thread.sleep(650)
        instrumentation.runOnMainSync { check(activity.projectionContext && field(activity, "session") === session) }
        report.put("syntheticFunctionLongKeptProjection", true).put("functionArbitrationModelMs", 500)
            .put("physicalFunctionOrderingPass", false).put("physicalSystemSuppressionPass", false)
        return report
    }
}
