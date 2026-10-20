package com.code2hack.eyeprojector.directrg

import android.app.Activity
import android.os.SystemClock
import android.view.ViewConfiguration
import android.view.MotionEvent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import com.code2hack.eyeprojector.protocol.Touch
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import com.code2hack.eyeprojector.directrg.WidgetCheckDiagnostics.Check

/** Real Android timers/widgets, driven by synthetic edges. This never proves a physical pad. */
internal object HeldInputWidgetChecks {
    fun run(activity: Activity, main: (Runnable) -> Unit,
            report: JSONObject = WidgetCheckDiagnostics.heldReport()): JSONObject {
        val diagnostics = WidgetCheckDiagnostics(report)
        lateinit var root: LinearLayout
        lateinit var button: Button
        lateinit var slider: SeekBar
        lateinit var reentrant: Button
        lateinit var local: LocalPointerDispatcher
        val longClick = CountDownLatch(1)
        var clicks = 0
        var canceled = 0
        var reentrantCancels = 0
        fun observeCounts() = diagnostics.observe("ordinaryClicks" to clicks,
            "longClickLatchCount" to longClick.count, "explicitCancelInvocations" to canceled,
            "reentrantCancelEvents" to reentrantCancels)
        diagnostics.enter(Check.HELD_SETUP)
        main(Runnable {
            root = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            button = Button(activity).apply {
                text = "Continuous hold fixture"
                isSoundEffectsEnabled = false; isHapticFeedbackEnabled = false
                setOnClickListener { clicks++ }
                setOnLongClickListener { longClick.countDown(); true }
            }
            slider = SeekBar(activity).apply { max = 100; progress = 20; isSoundEffectsEnabled = false }
            root.addView(button, LinearLayout.LayoutParams(-1, 100))
            root.addView(slider, LinearLayout.LayoutParams(-1, 100))
            reentrant = object : Button(activity) {
                override fun performClick(): Boolean = super.performClick()
                override fun onTouchEvent(event: MotionEvent): Boolean {
                    if (event.actionMasked == MotionEvent.ACTION_DOWN) local.cancel()
                    if (event.actionMasked == MotionEvent.ACTION_CANCEL) reentrantCancels++
                    return super.onTouchEvent(event)
                }
            }.apply { text = "Cancel during DOWN"; isSoundEffectsEnabled = false; isHapticFeedbackEnabled = false }
            root.addView(reentrant, LinearLayout.LayoutParams(-1, 100))
            activity.setContentView(root); local = LocalPointerDispatcher(root)
            diagnostics.geometry("root", root); diagnostics.geometry("button", button)
            diagnostics.geometry("slider", slider); diagnostics.geometry("reentrant", reentrant)
            observeCounts()
        })
        diagnostics.completed(Check.HELD_SETUP)
        val layout = CountDownLatch(1)
        diagnostics.enter(Check.HELD_LAYOUT_WAIT)
        main(Runnable { root.post { layout.countDown() } })
        val layoutSignaled = layout.await(3, TimeUnit.SECONDS)
        diagnostics.observe("layoutLatchSignaled" to layoutSignaled)
        check(layoutSignaled)
        diagnostics.completed(Check.HELD_LAYOUT_WAIT)
        var down = 0L
        main(Runnable {
            diagnostics.enter(Check.HELD_DOWN)
            diagnostics.geometry("root", root); diagnostics.geometry("button", button)
            diagnostics.geometry("slider", slider); diagnostics.geometry("reentrant", reentrant)
            down = SystemClock.uptimeMillis()
            val accepted = local.touch(HeldPointerDispatcher.Event(Touch.Phase.DOWN, down, down,
                PointerPosition(button.width / 2f, button.height / 2f, true)))
            observeCounts(); diagnostics.observe("downTimeMs" to down, "heldDownAccepted" to accepted)
            check(accepted)
            diagnostics.completed(Check.HELD_DOWN)
        })
        diagnostics.enter(Check.HELD_LONG_CLICK_WAIT)
        val longClickWaitMs = ViewConfiguration.getLongPressTimeout().toLong() + 2000
        val longClickSignaled = longClick.await(longClickWaitMs, TimeUnit.MILLISECONDS)
        diagnostics.observe("longClickWaitMs" to longClickWaitMs, "longClickSignaled" to longClickSignaled,
            "longClickLatchCount" to longClick.count)
        check(longClickSignaled) {
            "Stationary continuous DOWN did not let Android recognize long press"
        }
        diagnostics.completed(Check.HELD_LONG_CLICK_WAIT)
        main(Runnable {
            diagnostics.enter(Check.HELD_UP)
            val accepted = local.touch(HeldPointerDispatcher.Event(Touch.Phase.UP, down, SystemClock.uptimeMillis(),
                PointerPosition(button.width / 2f, button.height / 2f, true)))
            observeCounts(); diagnostics.observe("heldUpAccepted" to accepted)
            check(accepted)
            diagnostics.completed(Check.HELD_UP)
        })
        val drained = CountDownLatch(1)
        diagnostics.enter(Check.HELD_CLICK_DRAIN)
        main(Runnable { root.post { drained.countDown() } })
        val clickCallbacksDrained = drained.await(3, TimeUnit.SECONDS)
        diagnostics.observe("clickDrainSignaled" to clickCallbacksDrained)
        check(clickCallbacksDrained)
        diagnostics.completed(Check.HELD_CLICK_DRAIN)
        main(Runnable {
            diagnostics.enter(Check.HELD_NO_ORDINARY_CLICK)
            observeCounts()
            check(clicks == 0) { "Consumed long click also produced an ordinary click" }
            diagnostics.completed(Check.HELD_NO_ORDINARY_CLICK)
            val start = SystemClock.uptimeMillis()
            val y = slider.top + slider.height / 2f
            diagnostics.enter(Check.SLIDER_DOWN)
            val sliderDown = local.touch(HeldPointerDispatcher.Event(Touch.Phase.DOWN, start, start, PointerPosition(slider.width * .2f, y, true)))
            diagnostics.observe("sliderDownAccepted" to sliderDown, "sliderProgress" to slider.progress)
            check(sliderDown)
            diagnostics.completed(Check.SLIDER_DOWN)
            diagnostics.enter(Check.SLIDER_MOVE)
            val sliderMove = local.touch(HeldPointerDispatcher.Event(Touch.Phase.MOVE, start, start, PointerPosition(slider.width * .7f, y, true)))
            diagnostics.observe("sliderMoveAccepted" to sliderMove, "sliderProgress" to slider.progress)
            check(sliderMove)
            diagnostics.completed(Check.SLIDER_MOVE)
            diagnostics.enter(Check.SLIDER_UP)
            val sliderUp = local.touch(HeldPointerDispatcher.Event(Touch.Phase.UP, start, start, PointerPosition(slider.width * .9f, y, true)))
            diagnostics.observe("sliderUpAccepted" to sliderUp, "sliderProgress" to slider.progress)
            check(sliderUp)
            diagnostics.completed(Check.SLIDER_UP)
            diagnostics.enter(Check.SLIDER_FINAL_PROGRESS)
            check(slider.progress >= 80) { "Slider did not follow final UP position: ${slider.progress}" }
            diagnostics.completed(Check.SLIDER_FINAL_PROGRESS)
            val next = SystemClock.uptimeMillis()
            diagnostics.enter(Check.EXPLICIT_DOWN)
            val explicitDown = local.touch(HeldPointerDispatcher.Event(Touch.Phase.DOWN, next, next, PointerPosition(button.width / 2f, button.height / 2f, true)))
            diagnostics.observe("explicitDownAccepted" to explicitDown)
            diagnostics.completed(Check.EXPLICIT_DOWN)
            diagnostics.enter(Check.EXPLICIT_CANCEL)
            local.cancel(); canceled++
            observeCounts()
            diagnostics.completed(Check.EXPLICIT_CANCEL)
            val again = SystemClock.uptimeMillis()
            diagnostics.enter(Check.REENTRANT_DOWN)
            val reentrantDown = local.touch(HeldPointerDispatcher.Event(Touch.Phase.DOWN, again, again,
                PointerPosition(reentrant.width / 2f, reentrant.top + reentrant.height / 2f, true)))
            observeCounts(); diagnostics.observe("reentrantDownAccepted" to reentrantDown)
            diagnostics.completed(Check.REENTRANT_DOWN)
            diagnostics.enter(Check.REENTRANT_CANCEL)
            check(reentrantCancels == 1) { "Reentrant cancellation did not reach the attached DOWN target" }
            diagnostics.completed(Check.REENTRANT_CANCEL)
        })
        return report.put("stationaryLongClick", longClick.count == 0L).put("ordinaryClicksAfterLongClick", clicks)
            .put("draggedSliderProgress", slider.progress).put("canceledContacts", canceled).put("reentrantCancels", reentrantCancels)
            .put("physicalPadPass", false).put("passed", true)
    }
}
