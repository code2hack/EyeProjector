package com.code2hack.eyeprojector.directrg

import android.app.Instrumentation
import android.os.SystemClock
import android.view.SurfaceView
import org.json.JSONObject

/** Reserved-device fixture only: synthetic navigation proves ownership/context, not physical input. */
internal object ConnectedPanelChecks {
    fun run(instrumentation: Instrumentation, activity: ReceiverActivity): JSONObject {
        val report = JSONObject().put("scope", "Synthetic Activity panel/context calls; no physical Function or optical claim")
        fun field(target: Any, name: String): Any? = target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)
        var session: Any? = null
        var decoder: Any? = null
        var surface: android.view.Surface? = null
        instrumentation.runOnMainSync {
            check(activity.projectionContext && activity.inputSnapshot().getBoolean("streaming"))
            session = field(activity, "session")!!
            decoder = field(session!!, "decoder")!!
            surface = (field(activity, "screen") as SurfaceView).holder.surface
        }
        instrumentation.runOnMainSync { activity.showControlPanel() }
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
        instrumentation.runOnMainSync { activity.contextualBack() }
        Thread.sleep(650)
        instrumentation.runOnMainSync {
            check(activity.projectionContext)
            check(field(activity, "session") === session && field(session!!, "decoder") === decoder)
            check((field(activity, "screen") as SurfaceView).holder.surface === surface)
            report.put("localBackPreservedSessionDecoderSurface", true)
        }
        report.put("physicalFunctionOrderingPass", false).put("physicalSystemSuppressionPass", false)
        return report
    }
}
