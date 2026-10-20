package com.code2hack.eyeprojector.directrg

import android.app.Activity
import android.graphics.Color
import android.os.SystemClock
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject

/** Synthetic on-device Android widget replay, kept separate from genuine pad observations. */
internal object NativeInputWidgetChecks {
    fun run(activity: Activity, main: (Runnable) -> Unit): JSONObject {
        val report = JSONObject().put("source", "synthetic timestamped replay through local Android Views")
            .put("physicalPadPass", false)
        lateinit var root: LinearLayout
        lateinit var button: Button
        lateinit var nested: ListView
        lateinit var outer: ScrollView
        var clicks = 0; var doubles = 0
        val downTimes = mutableListOf<Long>()
        main(Runnable {
            root = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK) }
            val detector = GestureDetector(activity, object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent) = true
                override fun onDoubleTap(e: MotionEvent): Boolean { doubles++; return true }
            })
            button = object : Button(activity) {
                override fun performClick(): Boolean = super.performClick()
                override fun onTouchEvent(event: MotionEvent): Boolean {
                    if (event.actionMasked == MotionEvent.ACTION_DOWN) downTimes.add(event.eventTime)
                    detector.onTouchEvent(event)
                    return super.onTouchEvent(event)
                }
            }.apply { text = "Native tap fixture"; setOnClickListener { clicks++ } }
            root.addView(button, LinearLayout.LayoutParams(-1, 100))
            outer = ScrollView(activity)
            val content = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            nested = ListView(activity).apply {
                adapter = ArrayAdapter(activity, android.R.layout.simple_list_item_1, (1..40).map { "Nested row $it" })
            }
            content.addView(nested, LinearLayout.LayoutParams(-1, 180))
            repeat(40) { i -> content.addView(TextView(activity).apply { text = "Main row $i"; minHeight = 48 }) }
            outer.addView(content)
            root.addView(outer, LinearLayout.LayoutParams(-1, 0, 1f))
            activity.setContentView(root)
        })
        // Let Android lay out the actual hierarchy before deriving pointer targets.
        val laidOut = java.util.concurrent.CountDownLatch(1)
        main(Runnable { root.post { laidOut.countDown() } })
        check(laidOut.await(3, java.util.concurrent.TimeUnit.SECONDS))
        lateinit var local: LocalPointerDispatcher
        main(Runnable {
            check(root.width > 0 && nested.height > 0)
            local = LocalPointerDispatcher(root)
            val point = PointerPosition(button.width / 2f, button.height / 2f, true)
            val pad = PadGestureRecognizer(300, 500) { action ->
                if (action is PadGestureRecognizer.Action.Tap) check(local.tap(action))
            }
            val start = SystemClock.uptimeMillis() - 1500
            for (delta in listOf(0L, 190L, 343L)) {
                val at = start + delta
                pad.accept(PadGestureRecognizer.Event(PadGestureRecognizer.Key.CONTACT, PadGestureRecognizer.Phase.DOWN,
                    at, at, position = point), at)
                pad.accept(PadGestureRecognizer.Event(PadGestureRecognizer.Key.CONTACT, PadGestureRecognizer.Phase.UP,
                    at + 22, at), at + 22)
            }
            pad.confirm(SystemClock.uptimeMillis())
        })
        // ACTION_UP posts Button.PerformClick. Drain those callbacks before asserting,
        // waiting only on the instrumentation thread after touch dispatch has returned.
        val clicksDrained = java.util.concurrent.CountDownLatch(1)
        root.post { clicksDrained.countDown() }
        check(clicksDrained.await(3, java.util.concurrent.TimeUnit.SECONDS)) {
            "Native button click callbacks did not drain within three seconds"
        }
        main(Runnable {
            check(clicks == 3) { "Expected three native button clicks: $clicks" }
            check(doubles == 1) { "Expected one native double-tap classification: $doubles" }
            check(downTimes.zipWithNext { a, b -> b - a } == listOf(190L, 153L))
            report.put("nativeButtonClicks", clicks).put("nativeDoubleTaps", doubles)
                .put("downTimesMs", JSONArray(downTimes))
            val firstBefore = nested.firstVisiblePosition
            val topBefore = nested.getChildAt(0).top
            val nestedPoint = PointerPosition(root.width / 2f, button.height + nested.height / 2f, true)
            local.scroll(nestedPoint, 160)
            check(nested.firstVisiblePosition != firstBefore || nested.getChildAt(0).top != topBefore)
            check(outer.scrollY == 0) { "Nested wheel incorrectly scrolled its parent" }
            report.put("nestedFirstAfterDown", nested.firstVisiblePosition).put("nestedTopAfterDown", nested.getChildAt(0).top)
            val parentPoint = PointerPosition(root.width / 2f, root.height - 20f, true)
            local.scroll(parentPoint, 160)
            val afterDown = outer.scrollY
            check(afterDown > 0)
            local.scroll(parentPoint, -160)
            check(outer.scrollY < afterDown)
            report.put("mainAfterDown", afterDown).put("mainAfterUp", outer.scrollY)
            local.cancel()
        })
        return report.put("passed", true)
    }
}
