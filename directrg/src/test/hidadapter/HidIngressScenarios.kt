package com.code2hack.eyeprojector.directrg

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import com.code2hack.eyeprojector.protocol.Geometry
import com.code2hack.eyeprojector.protocol.Touch

internal object HidIngressScenarios {
    private val binding = HidBinding(HidButton(HidDeviceIdentity("host-peripheral", 1, 2), 30, 40), "host peripheral", "host key")
    private fun driver() = ExtractedHidIngress(TargetRoot()).apply { check(bindings.assign(HidAction.TAP, binding)) }
    private fun ExtractedHidIngress.hid(phase: HidKeyInput.Phase, down: Long = 1_100, at: Long = down) {
        SystemClock.now = at
        check(hidInput.accept(HidKeyInput.Event(7, binding, phase, down, at)))
    }
    private fun ExtractedHidIngress.native(key: PadGestureRecognizer.Key, phase: PadGestureRecognizer.Phase, down: Long, at: Long = down) {
        SystemClock.now = at
        gestures.accept(PadGestureRecognizer.Event(key, phase, at, down, position = pointer.position), at)
    }

    private fun pendingNativeActionsAreCanceledByActualHidAdmission() {
        for (key in listOf(PadGestureRecognizer.Key.TAP, PadGestureRecognizer.Key.FORWARD)) {
            val d = driver()
            d.native(key, PadGestureRecognizer.Phase.DOWN, 1_000)
            d.native(key, PadGestureRecognizer.Phase.UP, 1_000, 1_020)
            check(d.gestures.deadline != null)
            d.hid(HidKeyInput.Phase.DOWN)
            d.hid(HidKeyInput.Phase.UP, at = 1_130)
            d.gestures.confirm(5_000)
            check(d.root.first == listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) { "native $key replayed after HID admission: ${d.root.first}" }
            check(d.root.scrolls == 0)
            check(d.gestures.deadline == null)
        }
    }

    private fun actualNativeCancellationCanInvalidateHidBeforeItsDown() {
        val d = driver()
        d.native(PadGestureRecognizer.Key.HELD_CONTACT, PadGestureRecognizer.Phase.DOWN, 1_000)
        d.pointer.position = d.pointer.position.copy(x = 80f)
        d.root.onFirstCancel = { d.cancelAll() }
        d.hid(HidKeyInput.Phase.DOWN)
        d.hid(HidKeyInput.Phase.UP, at = 1_130)
        check(d.root.first == listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_CANCEL))
        check(d.root.second.isEmpty()) { "HID DOWN survived synchronous native cancellation" }
        check(!d.heldInput.holding)
    }

    private fun actualIngressHandsOffNativeAtSameTimestamp() {
        val d = driver()
        d.native(PadGestureRecognizer.Key.HELD_CONTACT, PadGestureRecognizer.Phase.DOWN, 1_100)
        d.pointer.position = d.pointer.position.copy(x = 80f)
        d.hid(HidKeyInput.Phase.DOWN)
        d.native(PadGestureRecognizer.Key.HELD_CONTACT, PadGestureRecognizer.Phase.UP, 1_100, 1_120)
        check(d.heldInput.holding)
        d.hid(HidKeyInput.Phase.UP, at = 1_130)
        check(d.root.first == listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_CANCEL))
        check(d.root.second == listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP))
        check(!d.heldInput.holding)
    }

    private fun pointerRefreshCancellationCannotInventDownOrUp() {
        val down = driver()
        down.pointer.onRefresh = { down.cancelAll() }
        down.hid(HidKeyInput.Phase.DOWN)
        check(down.root.first.isEmpty())
        check(!down.heldInput.holding)

        val up = driver()
        up.hid(HidKeyInput.Phase.DOWN)
        up.pointer.onRefresh = { up.cancelAll() }
        up.hid(HidKeyInput.Phase.UP, at = 1_130)
        check(up.root.first == listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_CANCEL))
        check(!up.heldInput.holding)
    }

    private fun actualDiscreteNativeActionCancelsHidHeldRoute() {
        val d = driver()
        d.hid(HidKeyInput.Phase.DOWN)
        d.dispatch(PadGestureRecognizer.Action.Scroll(160, d.pointer.position, 1_120))
        d.hid(HidKeyInput.Phase.UP, at = 1_130)
        check(d.root.first == listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_CANCEL))
        check(d.root.scrolls == 1)
        check(!d.heldInput.holding)
    }

    private fun actualCapturedProjectionRouteUsesOriginalSession() {
        val d = driver()
        val old = IngressSession()
        val next = IngressSession()
        d.session = old
        d.connectionView.visibility = View.GONE
        d.hid(HidKeyInput.Phase.DOWN)
        d.session = next
        d.hid(HidKeyInput.Phase.UP, at = 1_130)
        check(old.touches.map { it.phase } == listOf(com.code2hack.eyeprojector.protocol.Touch.Phase.DOWN, com.code2hack.eyeprojector.protocol.Touch.Phase.CANCEL))
        check(next.touches.isEmpty())
        check(!d.heldInput.holding)
    }

    private fun discrete(scroll: Boolean, point: PointerPosition): PadGestureRecognizer.Action =
        if (scroll) PadGestureRecognizer.Action.Scroll(160, point, 1_200)
        else PadGestureRecognizer.Action.Tap(1_100, 1_130, point,
            PadGestureRecognizer.Timing.INFERRED_SUMMARY, 1_200)

    private fun noDiscreteOutput(d: ExtractedHidIngress, vararg sessions: IngressSession) {
        check(d.root.scrolls == 0) { "stale scroll reached a local target" }
        check(sessions.all { it.taps.isEmpty() && it.scrolls.isEmpty() }) { "stale discrete action reached a session" }
    }

    private fun pointerPublicationCannotRetargetPendingNativeActions() {
        for (scroll in listOf(false, true)) {
            for (change in listOf("session", "view", "geometry", "generation", "local-to-projection")) {
                val d = driver()
                val old = IngressSession()
                val next = IngressSession()
                d.session = old
                d.connectionView.visibility = if (change == "local-to-projection") View.VISIBLE else View.GONE
                d.pointer.onRefresh = {
                    when (change) {
                        "session" -> d.session = next
                        "view" -> d.connectionView.visibility = View.VISIBLE
                        "geometry" -> d.currentGeometry = Geometry(90, 90)
                        "generation" -> d.cancelAll() // Same final target, new input generation.
                        else -> { d.session = next; d.connectionView.visibility = View.GONE }
                    }
                }
                d.dispatch(discrete(scroll, d.pointer.position))
                noDiscreteOutput(d, old, next)
                check(d.root.first.isEmpty() && d.root.second.isEmpty()) {
                    "pointer publication retargeted stale native action: scroll=$scroll change=$change"
                }
            }
        }
    }

    private fun heldCancelCallbackCannotRetargetPendingNativeActions() {
        for (scroll in listOf(false, true)) {
            for (change in listOf("projection-to-session", "projection-to-local", "local-to-projection", "local-generation")) {
                val d = driver()
                val old = IngressSession()
                val next = IngressSession()
                val wasLocal = change.startsWith("local")
                d.session = old
                d.connectionView.visibility = if (wasLocal) View.VISIBLE else View.GONE
                d.hid(HidKeyInput.Phase.DOWN)
                val changeContext: () -> Unit = {
                    when (change) {
                        "projection-to-session" -> d.session = next
                        "projection-to-local" -> d.connectionView.visibility = View.VISIBLE
                        "local-to-projection" -> { d.session = next; d.connectionView.visibility = View.GONE }
                        else -> d.cancelAll() // Same local target; stale actions still retire.
                    }
                }
                if (wasLocal) d.root.onFirstCancel = changeContext
                else old.onTouch = { if (it.phase == Touch.Phase.CANCEL) changeContext() }
                d.dispatch(discrete(scroll, d.pointer.position))
                noDiscreteOutput(d, old, next)
                check(d.root.second.isEmpty())
                if (wasLocal) {
                    check(d.root.first == listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_CANCEL)) {
                        "old local CANCEL retargeted native action: scroll=$scroll change=$change ${d.root.first}"
                    }
                } else {
                    check(d.root.first.isEmpty())
                    check(old.touches.map { it.phase } == listOf(Touch.Phase.DOWN, Touch.Phase.CANCEL))
                }
                check(next.touches.isEmpty())
                check(!d.heldInput.holding)
            }
        }
    }

    private fun retainedContextStillDeliversNativeTapAndScroll() {
        for (scroll in listOf(false, true)) {
            for (local in listOf(false, true)) {
                val d = driver()
                val source = IngressSession()
                d.session = source
                d.connectionView.visibility = if (local) View.VISIBLE else View.GONE
                d.dispatch(discrete(scroll, d.pointer.position))
                if (local) {
                    check(source.taps.isEmpty() && source.scrolls.isEmpty())
                    if (scroll) check(d.root.scrolls == 1 && d.root.first.isEmpty())
                    else check(d.root.first == listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP) && d.root.scrolls == 0)
                } else {
                    check(d.root.first.isEmpty() && d.root.scrolls == 0)
                    if (scroll) check(source.scrolls.size == 1 && source.taps.isEmpty())
                    else check(source.taps.size == 1 && source.scrolls.isEmpty())
                }
            }
        }
    }

    @JvmStatic fun main(args: Array<String>) {
        pendingNativeActionsAreCanceledByActualHidAdmission()
        actualNativeCancellationCanInvalidateHidBeforeItsDown()
        actualIngressHandsOffNativeAtSameTimestamp()
        pointerRefreshCancellationCannotInventDownOrUp()
        actualDiscreteNativeActionCancelsHidHeldRoute()
        actualCapturedProjectionRouteUsesOriginalSession()
        pointerPublicationCannotRetargetPendingNativeActions()
        heldCancelCallbackCannotRetargetPendingNativeActions()
        retainedContextStillDeliversNativeTapAndScroll()
        println("PASS: extracted actual HID/held initialization, native action dispatch, cancellation and pointer refresh seams")
    }
}
