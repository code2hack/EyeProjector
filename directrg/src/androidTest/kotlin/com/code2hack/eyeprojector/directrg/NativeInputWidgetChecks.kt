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
import com.code2hack.eyeprojector.directrg.WidgetCheckDiagnostics.Check

/** Synthetic on-device Android widget replay, kept separate from genuine pad observations. */
internal object NativeInputWidgetChecks {
    fun run(activity: Activity, main: (Runnable) -> Unit,
            report: JSONObject = WidgetCheckDiagnostics.nativeReport()): JSONObject {
        val diagnostics = WidgetCheckDiagnostics(report)
        val heldReport = report.getJSONObject("heldWidgets")
        lateinit var root: LinearLayout
        lateinit var button: Button
        lateinit var nested: ListView
        lateinit var outer: ScrollView
        var clicks = 0; var doubles = 0
        val downTimes = mutableListOf<Long>()
        var tapAttempts = 0; var tapAccepted = 0
        fun observeCounts() = diagnostics.observe("clicks" to clicks, "doubleTaps" to doubles,
            "downTimesMs" to JSONArray(downTimes.take(8)), "downTimeCount" to downTimes.size,
            "downTimesTruncated" to (downTimes.size > 8), "tapAttempts" to tapAttempts,
            "tapAccepted" to tapAccepted)
        diagnostics.enter(Check.NATIVE_SETUP)
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
            diagnostics.geometry("root", root); diagnostics.geometry("button", button)
            diagnostics.geometry("nested", nested); diagnostics.geometry("outer", outer)
            observeCounts()
        })
        diagnostics.completed(Check.NATIVE_SETUP)
        // Let Android lay out the actual hierarchy before deriving pointer targets.
        val laidOut = java.util.concurrent.CountDownLatch(1)
        diagnostics.enter(Check.NATIVE_LAYOUT_WAIT)
        main(Runnable { root.post { laidOut.countDown() } })
        val layoutSignaled = laidOut.await(3, java.util.concurrent.TimeUnit.SECONDS)
        diagnostics.observe("layoutLatchSignaled" to layoutSignaled)
        check(layoutSignaled)
        diagnostics.completed(Check.NATIVE_LAYOUT_WAIT)
        lateinit var local: LocalPointerDispatcher
        main(Runnable {
            diagnostics.enter(Check.NATIVE_LAYOUT_SIZE)
            diagnostics.geometry("root", root); diagnostics.geometry("button", button)
            diagnostics.geometry("nested", nested); diagnostics.geometry("outer", outer)
            diagnostics.observe("nestedChildCount" to nested.childCount,
                "nestedFirstChildTop" to nested.getChildAt(0)?.top)
            check(root.width > 0 && nested.height > 0)
            diagnostics.completed(Check.NATIVE_LAYOUT_SIZE)
            local = LocalPointerDispatcher(root)
            val point = PointerPosition(button.width / 2f, button.height / 2f, true)
            val pad = PadGestureRecognizer(300, 500) { action ->
                if (action is PadGestureRecognizer.Action.Tap) {
                    diagnostics.enter(Check.NATIVE_TAP_ACCEPTED)
                    tapAttempts++
                    observeCounts()
                    val accepted = local.tap(action)
                    if (accepted) tapAccepted++
                    observeCounts(); diagnostics.observe("lastTapAccepted" to accepted)
                    check(accepted)
                    diagnostics.completed(Check.NATIVE_TAP_ACCEPTED)
                }
            }
            diagnostics.enter(Check.NATIVE_REPLAY)
            val start = SystemClock.uptimeMillis() - 1500
            for (delta in listOf(0L, 190L, 343L)) {
                val at = start + delta
                pad.accept(PadGestureRecognizer.Event(PadGestureRecognizer.Key.CONTACT, PadGestureRecognizer.Phase.DOWN,
                    at, at, position = point), at)
                pad.accept(PadGestureRecognizer.Event(PadGestureRecognizer.Key.CONTACT, PadGestureRecognizer.Phase.UP,
                    at + 22, at), at + 22)
            }
            pad.confirm(SystemClock.uptimeMillis())
            observeCounts()
            diagnostics.completed(Check.NATIVE_REPLAY)
        })
        // ACTION_UP posts Button.PerformClick. Drain those callbacks before asserting,
        // waiting only on the instrumentation thread after touch dispatch has returned.
        val clicksDrained = java.util.concurrent.CountDownLatch(1)
        diagnostics.enter(Check.NATIVE_CLICK_DRAIN)
        root.post { clicksDrained.countDown() }
        val clickCallbacksDrained = clicksDrained.await(3, java.util.concurrent.TimeUnit.SECONDS)
        diagnostics.observe("clickDrainSignaled" to clickCallbacksDrained)
        check(clickCallbacksDrained) {
            "Native button click callbacks did not drain within three seconds"
        }
        diagnostics.completed(Check.NATIVE_CLICK_DRAIN)
        main(Runnable {
            observeCounts()
            diagnostics.enter(Check.NATIVE_CLICKS)
            check(clicks == 3) { "Expected three native button clicks: $clicks" }
            diagnostics.completed(Check.NATIVE_CLICKS)
            diagnostics.enter(Check.NATIVE_DOUBLE_TAPS)
            check(doubles == 1) { "Expected one native double-tap classification: $doubles" }
            diagnostics.completed(Check.NATIVE_DOUBLE_TAPS)
            diagnostics.enter(Check.NATIVE_DOWN_INTERVALS)
            check(downTimes.zipWithNext { a, b -> b - a } == listOf(190L, 153L))
            diagnostics.completed(Check.NATIVE_DOWN_INTERVALS)
            report.put("nativeButtonClicks", clicks).put("nativeDoubleTaps", doubles)
                .put("downTimesMs", JSONArray(downTimes))
            diagnostics.enter(Check.NATIVE_NESTED_SCROLL)
            diagnostics.observe("nestedChildCount" to nested.childCount,
                "nestedFirstBefore" to nested.firstVisiblePosition,
                "nestedTopBefore" to nested.getChildAt(0)?.top, "parentBeforeNested" to outer.scrollY)
            val firstBefore = nested.firstVisiblePosition
            val topBefore = nested.getChildAt(0).top
            val nestedPoint = PointerPosition(root.width / 2f, button.height + nested.height / 2f, true)
            local.scroll(nestedPoint, 160)
            diagnostics.observe("nestedFirstAfterDown" to nested.firstVisiblePosition,
                "nestedTopAfterDown" to nested.getChildAt(0)?.top, "parentAfterNested" to outer.scrollY)
            diagnostics.completed(Check.NATIVE_NESTED_SCROLL)
            diagnostics.enter(Check.NATIVE_NESTED_MOVED)
            check(nested.firstVisiblePosition != firstBefore || nested.getChildAt(0).top != topBefore)
            diagnostics.completed(Check.NATIVE_NESTED_MOVED)
            diagnostics.enter(Check.NATIVE_PARENT_UNCHANGED)
            check(outer.scrollY == 0) { "Nested wheel incorrectly scrolled its parent" }
            diagnostics.completed(Check.NATIVE_PARENT_UNCHANGED)
            report.put("nestedFirstAfterDown", nested.firstVisiblePosition).put("nestedTopAfterDown", nested.getChildAt(0).top)
            val parentPoint = PointerPosition(root.width / 2f, root.height - 20f, true)
            diagnostics.enter(Check.NATIVE_MAIN_SCROLL_DOWN)
            local.scroll(parentPoint, 160)
            val afterDown = outer.scrollY
            diagnostics.observe("mainAfterDown" to afterDown)
            diagnostics.completed(Check.NATIVE_MAIN_SCROLL_DOWN)
            diagnostics.enter(Check.NATIVE_MAIN_DOWN)
            check(afterDown > 0)
            diagnostics.completed(Check.NATIVE_MAIN_DOWN)
            diagnostics.enter(Check.NATIVE_MAIN_SCROLL_UP)
            local.scroll(parentPoint, -160)
            diagnostics.observe("mainAfterUp" to outer.scrollY)
            diagnostics.completed(Check.NATIVE_MAIN_SCROLL_UP)
            diagnostics.enter(Check.NATIVE_MAIN_UP)
            check(outer.scrollY < afterDown)
            diagnostics.completed(Check.NATIVE_MAIN_UP)
            report.put("mainAfterDown", afterDown).put("mainAfterUp", outer.scrollY)
            diagnostics.enter(Check.NATIVE_CANCEL)
            local.cancel()
            diagnostics.completed(Check.NATIVE_CANCEL)
        })
        HeldInputWidgetChecks.run(activity, main, heldReport)
        return report.put("passed", true)
    }
}
