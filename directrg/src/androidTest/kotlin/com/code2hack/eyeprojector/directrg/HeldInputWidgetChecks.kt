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

/** Real Android timers/widgets, driven by synthetic edges. This never proves a physical pad. */
internal object HeldInputWidgetChecks {
    fun run(activity: Activity, main: (Runnable) -> Unit): JSONObject {
        lateinit var root: LinearLayout
        lateinit var button: Button
        lateinit var slider: SeekBar
        lateinit var reentrant: Button
        lateinit var local: LocalPointerDispatcher
        val longClick = CountDownLatch(1)
        var clicks = 0
        var canceled = 0
        var reentrantCancels = 0
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
        })
        val layout = CountDownLatch(1)
        main(Runnable { root.post { layout.countDown() } })
        check(layout.await(3, TimeUnit.SECONDS))
        var down = 0L
        main(Runnable {
            down = SystemClock.uptimeMillis()
            check(local.touch(HeldPointerDispatcher.Event(Touch.Phase.DOWN, down, down,
                PointerPosition(button.width / 2f, button.height / 2f, true))))
        })
        check(longClick.await(ViewConfiguration.getLongPressTimeout().toLong() + 2000, TimeUnit.MILLISECONDS)) {
            "Stationary continuous DOWN did not let Android recognize long press"
        }
        main(Runnable {
            check(local.touch(HeldPointerDispatcher.Event(Touch.Phase.UP, down, SystemClock.uptimeMillis(),
                PointerPosition(button.width / 2f, button.height / 2f, true))))
        })
        val drained = CountDownLatch(1)
        main(Runnable { root.post { drained.countDown() } })
        check(drained.await(3, TimeUnit.SECONDS))
        main(Runnable {
            check(clicks == 0) { "Consumed long click also produced an ordinary click" }
            val start = SystemClock.uptimeMillis()
            val y = slider.top + slider.height / 2f
            check(local.touch(HeldPointerDispatcher.Event(Touch.Phase.DOWN, start, start, PointerPosition(slider.width * .2f, y, true))))
            check(local.touch(HeldPointerDispatcher.Event(Touch.Phase.MOVE, start, start, PointerPosition(slider.width * .7f, y, true))))
            check(local.touch(HeldPointerDispatcher.Event(Touch.Phase.UP, start, start, PointerPosition(slider.width * .9f, y, true))))
            check(slider.progress >= 80) { "Slider did not follow final UP position: ${slider.progress}" }
            val next = SystemClock.uptimeMillis()
            local.touch(HeldPointerDispatcher.Event(Touch.Phase.DOWN, next, next, PointerPosition(button.width / 2f, button.height / 2f, true)))
            local.cancel(); canceled++
            val again = SystemClock.uptimeMillis()
            local.touch(HeldPointerDispatcher.Event(Touch.Phase.DOWN, again, again,
                PointerPosition(reentrant.width / 2f, reentrant.top + reentrant.height / 2f, true)))
            check(reentrantCancels == 1) { "Reentrant cancellation did not reach the attached DOWN target" }
        })
        return JSONObject().put("source", "synthetic edges through LocalPointerDispatcher and Android Views")
            .put("stationaryLongClick", longClick.count == 0L).put("ordinaryClicksAfterLongClick", clicks)
            .put("draggedSliderProgress", slider.progress).put("canceledContacts", canceled).put("reentrantCancels", reentrantCancels)
            .put("physicalPadPass", false).put("passed", true)
    }
}
