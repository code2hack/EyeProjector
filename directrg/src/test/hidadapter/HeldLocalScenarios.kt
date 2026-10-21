package com.code2hack.eyeprojector.directrg

import android.view.MotionEvent
import android.view.View
import com.code2hack.eyeprojector.protocol.Touch

private fun edge(phase: PadGestureRecognizer.Phase, down: Long = 1_000L, at: Long = down) =
    PadGestureRecognizer.Event(PadGestureRecognizer.Key.HELD_CONTACT, phase, at, down)

/** Like ViewGroup, target attachment happens after the selected child's DOWN callback returns. */
internal class TargetRoot : View() {
    val first = mutableListOf<Int>()
    val second = mutableListOf<Int>()
    var onFirstDown: () -> Unit = {}
    var onFirstCancel: () -> Unit = {}
    var scrolls = 0
    private var target: MutableList<Int>? = null
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) {
            val next = if (event.x < 50) first else second
            next.add(event.action)
            if (next === first) onFirstDown()
            target = next
            return true
        }
        val current = target ?: return false
        current.add(event.action)
        if (current === first && event.action == MotionEvent.ACTION_CANCEL) onFirstCancel()
        if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) target = null
        return true
    }
    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean { scrolls++; return true }
}

private fun localHandoff() {
    val root = TargetRoot()
    val local = LocalPointerDispatcher(root)
    var point = PointerPosition(10f, 20f, true)
    val dispatcher = HeldPointerDispatcher({ point }) { local::touch }
    val input = HeldInputOwner(dispatcher)
    val old = Any()
    val next = Any()
    root.onFirstDown = {
        point = point.copy(x = 80f)
        input.accept(next, edge(PadGestureRecognizer.Phase.DOWN))
    }
    check(input.accept(old, edge(PadGestureRecognizer.Phase.DOWN)))
    check(input.holding)
    check(!input.accept(old, edge(PadGestureRecognizer.Phase.UP, at = 1_010)))
    check(input.accept(next, edge(PadGestureRecognizer.Phase.UP, at = 1_020)))
    check(root.first == listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_CANCEL)) { "old target: ${root.first}" }
    check(root.second == listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) { "new target lost its release: ${root.second}" }
    check(!input.holding)
}

private fun queuedCancellation() {
    val root = TargetRoot()
    val local = LocalPointerDispatcher(root)
    var point = PointerPosition(10f, 20f, true)
    val dispatcher = HeldPointerDispatcher({ point }) { local::touch }
    val input = HeldInputOwner(dispatcher)
    val old = Any()
    val next = Any()
    root.onFirstDown = {
        point = point.copy(x = 80f)
        input.accept(next, edge(PadGestureRecognizer.Phase.DOWN))
        input.cancel(1_010)
    }
    input.accept(old, edge(PadGestureRecognizer.Phase.DOWN))
    check(root.second.isEmpty()) { "a canceled replacement reached the new target" }
    check(root.first == listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_CANCEL))
    check(!input.holding)
    check(!input.accept(next, edge(PadGestureRecognizer.Phase.UP, at = 1_020)))
}

private fun rejectedDownDoesNotOwnRelease() {
    val events = mutableListOf<Touch.Phase>()
    val dispatcher = HeldPointerDispatcher({ PointerPosition(10f, 20f, true) }) { { event ->
        events.add(event.phase)
        event.phase != Touch.Phase.DOWN
    } }
    val input = HeldInputOwner(dispatcher)
    val owner = Any()
    check(!input.accept(owner, edge(PadGestureRecognizer.Phase.DOWN)))
    check(!input.holding)
    check(!input.accept(owner, edge(PadGestureRecognizer.Phase.UP, at = 1_010)))
    check(events == listOf(Touch.Phase.DOWN, Touch.Phase.CANCEL))
}

fun main() {
    localHandoff()
    queuedCancellation()
    rejectedDownDoesNotOwnRelease()
    println("PASS: actual LocalPointerDispatcher handoff, queued cancellation, rejected route")
}
