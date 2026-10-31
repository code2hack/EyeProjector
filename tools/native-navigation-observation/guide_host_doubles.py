"""Narrow host-only Android boundaries for the actual guide adapter; no SDK/device."""

DOUBLES = {
    "Context.kt": '''package android.content
import android.content.res.Resources
open class Context { val resources = Resources() }
''',
    "Resources.kt": '''package android.content.res
class DisplayMetrics { var density = 1f }
class Resources { val displayMetrics = DisplayMetrics() }
''',
    "Graphics.kt": '''package android.graphics
class Canvas
class Rect
object Color { const val WHITE = -1; const val BLACK = -16777216 }
class Typeface { companion object { val DEFAULT_BOLD = Typeface() } }
''',
    "Clock.kt": '''package android.os
object SystemClock {
    var now = 0L
    fun uptimeMillis() = now
    fun elapsedRealtimeNanos() = 9000000000L + now * 1000000L
}
''',
    "Activity.kt": '''package android.app
import android.content.Context
import android.view.ViewGroup
class Window { val decorView = ViewGroup() }
open class Activity : Context() {
    val window = Window()
    var focused = true
    fun hasWindowFocus() = focused
}
''',
    "View.kt": '''package android.view
import android.content.Context
import android.graphics.Canvas
import android.graphics.Rect
open class View(val context: Context = Context()) {
    val resources get() = context.resources
    var visibility = VISIBLE
    var windowVisibility = VISIBLE
    var isAttachedToWindow = true
    var overlayOwner: ViewGroup? = null
    open val isShown: Boolean get() = visibility == VISIBLE && isAttachedToWindow && (overlayOwner?.isShown ?: true)
    var isFocusable = true
    var isFocusableInTouchMode = true
    var isClickable = true
    var isLongClickable = true
    var importantForAccessibility = 0
    var left = 0; var top = 0; var right = 0; var bottom = 0
    val width get() = right - left
    val height get() = bottom - top
    var measuredWidth = 0; var measuredHeight = 0
    var padding = 0
    fun setPadding(l: Int, t: Int, r: Int, b: Int) { padding = l + t + r + b }
    open fun measure(w: Int, h: Int) { measuredWidth = MeasureSpec.getSize(w); measuredHeight = minOf(220, MeasureSpec.getSize(h)) }
    open fun layout(l: Int, t: Int, r: Int, b: Int) { left=l; top=t; right=r; bottom=b }
    fun getGlobalVisibleRect(rect: Rect) = isShown && width > 0 && height > 0
    fun invalidate() = Unit
    protected open fun onDraw(canvas: Canvas) = Unit
    fun draw() { if (isShown) onDraw(Canvas()) }
    object MeasureSpec {
        const val EXACTLY = 0x40000000
        const val AT_MOST = -2147483648
        fun makeMeasureSpec(size: Int, mode: Int) = size or mode
        fun getSize(value: Int) = value and 0x3fffffff
    }
    companion object { const val VISIBLE=0; const val INVISIBLE=4; const val IMPORTANT_FOR_ACCESSIBILITY_NO=2 }
}
class ViewGroup : View() {
    val children = mutableListOf<View>()
    val overlay = ViewGroupOverlay(this)
    var childMutations = 0
    var rootLayouts = 0
    init { super.layout(0, 0, 1280, 720) }
    fun addView(view: View) { children.add(view); childMutations++ }
    override fun layout(l: Int,t: Int,r: Int,b: Int) { rootLayouts++; super.layout(l,t,r,b) }
}
class ViewGroupOverlay(private val root: ViewGroup) {
    val views = mutableListOf<View>()
    var failAddAfterSideEffect = false
    var failRemoval = false
    fun add(view: View) {
        check(view !in views); views.add(view); view.overlayOwner=root
        if (failAddAfterSideEffect) error("add after side effect")
    }
    fun remove(view: View) {
        if (failRemoval) error("remove failure")
        views.remove(view); view.overlayOwner=null; view.isAttachedToWindow=false
    }
    fun draw() { views.toList().forEach { it.draw() } }
}
object Gravity { const val CENTER=17 }
''',
    "Text.kt": '''package android.widget
import android.content.Context
import android.graphics.Typeface
import android.view.View
open class TextView(context: Context) : View(context) {
    var text: CharSequence = ""
    var textSize = 0f
    var typeface: Typeface? = null
    var gravity = 0
    var foreground = 0
    var background = 0
    var selectable = true
    fun setTextColor(value: Int) { foreground=value }
    fun setBackgroundColor(value: Int) { background=value }
    fun setTextIsSelectable(value: Boolean) { selectable=value }
}
''',
}

CHECKS = '''package com.code2hack.eyeprojector.directrg
import android.app.Activity
import android.graphics.Color
import android.os.SystemClock
import android.view.View
import android.widget.TextView

private fun rows(queue: NativeInputObservationQueue): List<NativeInputObservationQueue.Row> {
    val result = mutableListOf<NativeInputObservationQueue.Row>()
    queue.drain { result.add(it) }; return result
}
private fun tick(guide: NativeGestureBatchGuide, now: Long, active: Boolean=true, interactive: Boolean=true) {
    SystemClock.now=now; guide.tick(active, interactive, SystemClock.elapsedRealtimeNanos())
}
fun main() {
    val ids = listOf("prepare","one_single","one_double","one_hold","two_single","two_hold","two_double")
    check(NativeGestureBatchGuidePlan.PROMPTS.map { it.id } == ids)
    check(NativeGestureBatchGuidePlan.PROMPTS.map { it.durationMs } == listOf(10000L,20000L,20000L,20000L,20000L,20000L,20000L))
    val plan=NativeGestureBatchGuidePlan(); val events=mutableListOf<NativeGestureBatchGuidePlan.Event>(); var time=0L
    for (prompt in NativeGestureBatchGuidePlan.PROMPTS) {
        events += plan.update(time,true)
        time += prompt.durationMs
        events += plan.update(time,true)
    }
    check(plan.snapshot.complete && time==130000L)
    check(events.filter { it.name=="step_start" }.map { it.snapshot.prompt.id } == ids.drop(1))
    check(events.filter { it.name=="step_end" }.map { it.snapshot.prompt.id } == ids.drop(1))
    check(events.count { it.name=="complete" }==1)
    val paused=NativeGestureBatchGuidePlan()
    paused.update(0,true); paused.update(4000,true); paused.update(4500,false)
    paused.update(100000,false); check(paused.snapshot.remainingMs==6000L)
    paused.update(100001,true); check(paused.snapshot.remainingMs==6000L)
    paused.update(106001,true); check(paused.snapshot.prompt.id=="one_single")
    paused.close(); check(paused.update(999999,true).isEmpty())
    val delayed=NativeGestureBatchGuidePlan()
    delayed.update(0,true); val late=delayed.update(900000,true)
    check(delayed.snapshot.prompt.id=="one_single" && !delayed.snapshot.complete)
    check(late.none { it.name=="step_start" }) // A late callback cannot skip an unseen prompt.

    val offQueue=NativeInputObservationQueue()
    check(NativeGestureBatchGuide.create(null,offQueue)==null)
    check(NativeGestureBatchGuide.create("false",offQueue)==null)
    check(NativeGestureBatchGuide.create("True",offQueue)==null)
    check(rows(offQueue).isEmpty())

    SystemClock.now=0
    val queue=NativeInputObservationQueue(); val guide=NativeGestureBatchGuide.create("true",queue)!!
    val first=Activity(); val root=first.window.decorView
    val ordinary=View(); root.addView(ordinary)
    val rootBounds=listOf(root.left,root.top,root.right,root.bottom)
    val childMutations=root.childMutations; val layouts=root.rootLayouts
    guide.onResumed(first)
    tick(guide,0,active=false); check(root.overlay.views.isEmpty())
    tick(guide,1000,interactive=false); check(root.overlay.views.isEmpty())
    tick(guide,2000); check(root.overlay.views.size==1 && !guide.uiShown)
    tick(guide,7000); check(!guide.uiShown) // Attaching without a draw cannot start countdown.
    val label=root.overlay.views.single() as TextView
    check(!label.isFocusable && !label.isFocusableInTouchMode && !label.isClickable && !label.isLongClickable && !label.selectable)
    check(label.foreground==Color.WHITE && label.background==Color.BLACK && label.textSize==28f)
    check(root.children==listOf(ordinary) && root.childMutations==childMutations && root.rootLayouts==layouts)
    check(rootBounds==listOf(root.left,root.top,root.right,root.bottom))
    root.overlay.draw(); tick(guide,7000); check(guide.uiShown)
    tick(guide,8000); check(label.text.contains("9s"))
    first.focused=false; tick(guide,8500); check(root.overlay.views.isEmpty())
    tick(guide,100000); check(guide.promptId=="prepare")
    first.focused=true; tick(guide,100001); root.overlay.draw(); tick(guide,100001)
    tick(guide,109001); check(guide.promptId=="one_single")
    val afterTransition=rows(queue)
    check(afterTransition.none { it.fields["guideEvent"]=="step_start" }) // New label has not drawn.
    root.overlay.draw(); tick(guide,109001)
    check(rows(queue).any { it.fields["guideEvent"]=="step_start" && it.fields["promptId"]=="one_single" })
    guide.onUnavailable(first,"paused"); check(root.overlay.views.isEmpty())
    val second=Activity(); val other=View(); second.window.decorView.overlay.add(other)
    guide.onResumed(second); tick(guide,200000); second.window.decorView.overlay.draw(); tick(guide,200000)
    guide.onUnavailable(first,"destroyed") // Stale callback leaves the successor's overlay intact.
    check(second.window.decorView.overlay.views.size==2 && guide.promptId=="one_single")
    val oldLabel=second.window.decorView.overlay.views.last()
    guide.stop(); check(guide.released && second.window.decorView.overlay.views==listOf(other))
    rows(queue); oldLabel.draw(); guide.onResumed(first); tick(guide,999999); guide.stop()
    check(rows(queue).isEmpty()) // No late draw/lifecycle/tick rows or UI after stop.

    val batchQueue=NativeInputObservationQueue(); val batch=NativeGestureBatchGuide.create("true",batchQueue)!!
    val batchActivity=Activity(); var batchTime=0L
    batch.onResumed(batchActivity); tick(batch,batchTime)
    for (prompt in NativeGestureBatchGuidePlan.PROMPTS) {
        batchActivity.window.decorView.overlay.draw(); tick(batch,batchTime)
        if (prompt.id=="two_double") {
            tick(batch,batchTime+1000); tick(batch,batchTime+1100,interactive=false)
            check(!batch.completed && batch.promptId=="two_double" && batch.released)
            batchTime+=60000 // Screen-off/hidden time cannot complete the final instruction.
            tick(batch,batchTime); batchActivity.window.decorView.overlay.draw(); tick(batch,batchTime)
            batchTime+=19000
        } else batchTime+=prompt.durationMs
        tick(batch,batchTime)
    }
    check(batch.completed)
    check((batchActivity.window.decorView.overlay.views.single() as TextView).text.contains("INSTRUCTIONS FINISHED"))
    batch.stop(); val batchRows=rows(batchQueue)
    check(batchRows.filter { it.fields["guideEvent"]=="step_start" }.map { it.fields["promptId"] }==ids.drop(1))
    check(batchRows.filter { it.fields["guideEvent"]=="step_end" }.map { it.fields["promptId"] }==ids.drop(1))
    check(batchRows.all { row -> row.fields.values.all { it==null || it is String || it is Number || it is Boolean } })

    val failureQueue=NativeInputObservationQueue(); val failed=NativeGestureBatchGuide.create("true",failureQueue)!!
    val bad=Activity(); val kept=View(); bad.window.decorView.overlay.add(kept)
    bad.window.decorView.overlay.failAddAfterSideEffect=true
    failed.onResumed(bad); tick(failed,0)
    check(failed.failureCount>0 && failed.released && bad.window.decorView.overlay.views==listOf(kept))
    check(runCatching { failed.throwIfFailed() }.isFailure)
    check(rows(failureQueue).any { it.fields["guideEvent"]=="error" })
    val removal=NativeGestureBatchGuide.create("true",NativeInputObservationQueue())!!; val host=Activity()
    removal.onResumed(host); tick(removal,0); host.window.decorView.overlay.failRemoval=true
    check(runCatching { removal.stop() }.isFailure && !removal.released)
    host.window.decorView.overlay.failRemoval=false
    check(runCatching { removal.stop() }.isFailure && removal.released) // Original error is retained.
    println("PASS: actual guide plan/UI adapter; automatic six slots, draw/visibility/sink gating, pause/resume, default-off, noninteractive unchanged root geometry, stale/late callbacks, exact cleanup and partial failures. Host doubles only.")
}
'''
