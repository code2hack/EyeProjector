package com.code2hack.eyeprojector.directrg

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import com.code2hack.eyeprojector.protocol.Geometry
import com.code2hack.eyeprojector.protocol.Touch

internal object HidIngressScenarios {
    private val binding = HidBinding(HidButton(HidDeviceIdentity("host-peripheral", 1, 2), 30, 40), "host peripheral", "host key")
    private fun driver() = ExtractedHidIngress(TargetRoot()).apply { check(bindings.assign(HidAction.TAP, binding)) }
    private fun ExtractedHidIngress.hover(action: HidAction?) {
        hidPanel.row = action
        hidPanel.visibility = if (action == null) View.GONE else View.VISIBLE
    }
    private fun ExtractedHidIngress.hid(
        phase: HidKeyInput.Phase, down: Long = 1_100, at: Long = down,
        attachment: Int = 7, actualBinding: HidBinding = binding,
    ) {
        SystemClock.now = at
        check(hidInput.accept(HidKeyInput.Event(attachment, actualBinding, phase, down, at)))
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

    private fun actualOldCancelCallbackKeepsTheNewerHidAdmission() {
        val d = driver()
        d.hid(HidKeyInput.Phase.DOWN, down = 1_000)
        d.pointer.position = d.pointer.position.copy(x = 80f)
        d.root.onFirstCancel = {
            d.root.onFirstCancel = {}
            // Same binding and Activity context; no cancelAll/generation change.
            d.hid(HidKeyInput.Phase.DOWN, down = 1_200)
        }
        d.hid(HidKeyInput.Phase.DOWN, down = 1_100)
        check(d.root.first == listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_CANCEL))
        check(d.root.second == listOf(MotionEvent.ACTION_DOWN)) {
            "older HID replacement displaced admission from actual local CANCEL callback: ${d.root.second}"
        }
        d.hid(HidKeyInput.Phase.UP, down = 1_200, at = 1_300)
        check(d.root.second == listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            "newer physical release did not complete its actual local route: ${d.root.second}"
        }
        check(!d.heldInput.holding)
        d.hid(HidKeyInput.Phase.UP, down = 1_100, at = 1_310)
        check(d.root.second == listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP))
    }

    private fun actualProjectionCancelCallbackKeepsNewerTimestamps() {
        val d = driver()
        val source = IngressSession()
        d.session = source
        d.connectionView.visibility = View.GONE
        d.hid(HidKeyInput.Phase.DOWN, down = 1_000)
        source.onTouch = {
            if (it.phase == Touch.Phase.CANCEL && it.contactId == 1_000L) {
                source.onTouch = {}
                d.hid(HidKeyInput.Phase.DOWN, down = 1_200)
            }
        }
        d.hid(HidKeyInput.Phase.DOWN, down = 1_100)
        d.hid(HidKeyInput.Phase.UP, down = 1_200, at = 1_300)
        check(source.touches.map { it.phase to it.contactId } == listOf(
            Touch.Phase.DOWN to 1_000L, Touch.Phase.CANCEL to 1_000L,
            Touch.Phase.DOWN to 1_200L, Touch.Phase.UP to 1_200L,
        )) { "replacement retargeted original projection ownership: ${source.touches}" }
        check(!d.heldInput.holding)
        check(d.root.first.isEmpty() && d.root.second.isEmpty())
    }

    private fun actualPointerPublicationCannotOverwriteNewerFirstOrReplacementDown() {
        for (replacement in listOf(false, true)) {
            val d = driver()
            if (replacement) {
                d.hid(HidKeyInput.Phase.DOWN, down = 1_000)
                d.pointer.position = d.pointer.position.copy(x = 80f)
            }
            d.pointer.onRefresh = { d.hid(HidKeyInput.Phase.DOWN, down = 1_200) }
            d.hid(HidKeyInput.Phase.DOWN, down = 1_100)
            d.hid(HidKeyInput.Phase.UP, down = 1_200, at = 1_300)
            val currentTarget = if (replacement) d.root.second else d.root.first
            check(currentTarget == listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                "pointer publication lost newer HID release: replacement=$replacement events=$currentTarget"
            }
            if (replacement) check(d.root.first == listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_CANCEL))
            else check(d.root.second.isEmpty())
            check(!d.heldInput.holding)
        }
    }

    private fun actualDeferredOldCancelCanAdmitAndReleaseTheNewestPhysicalPress() {
        val d = driver()
        d.root.onFirstDown = {
            d.root.onFirstDown = {}
            d.pointer.position = d.pointer.position.copy(x = 80f)
            d.hid(HidKeyInput.Phase.DOWN, down = 1_100)
        }
        d.root.onFirstCancel = {
            d.root.onFirstCancel = {}
            d.hid(HidKeyInput.Phase.DOWN, down = 1_200)
        }
        // The old ViewGroup target is attached only after its DOWN callback returns.
        // The actual local dispatcher defers its CANCEL until then; 1200 supersedes queued 1100.
        d.hid(HidKeyInput.Phase.DOWN, down = 1_000)
        d.hid(HidKeyInput.Phase.UP, down = 1_100, at = 1_250)
        check(d.heldInput.holding)
        d.hid(HidKeyInput.Phase.UP, down = 1_200, at = 1_300)
        check(d.root.first == listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_CANCEL))
        check(d.root.second == listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            "deferred old local CANCEL corrupted final new UP: ${d.root.second}"
        }
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

    private fun outputDriver(projection: Boolean): Pair<ExtractedHidIngress, IngressSession?> {
        val d = driver()
        val source = if (projection) IngressSession() else null
        if (source != null) {
            d.session = source
            d.connectionView.visibility = View.GONE
        }
        return Pair(d, source)
    }

    private fun onOriginalCancel(d: ExtractedHidIngress, source: IngressSession?, callback: () -> Unit) {
        if (source == null) {
            d.root.onFirstCancel = {
                d.root.onFirstCancel = {}
                callback()
            }
        } else {
            source.onTouch = { event ->
                if (event.phase == Touch.Phase.CANCEL && event.contactId == 1_000L) {
                    source.onTouch = {}
                    callback()
                }
            }
        }
    }

    /** Read the actual MotionEvent times or the actual timestamp argument at the session boundary. */
    private fun assertOutputTrace(
        d: ExtractedHidIngress, source: IngressSession?, label: String,
        vararg expected: Triple<Touch.Phase, Long, Long>,
    ) {
        val trace = if (source == null) d.root.events.map { event ->
            val phase = when (event.action) {
                MotionEvent.ACTION_DOWN -> Touch.Phase.DOWN
                MotionEvent.ACTION_UP -> Touch.Phase.UP
                MotionEvent.ACTION_MOVE -> Touch.Phase.MOVE
                MotionEvent.ACTION_CANCEL -> Touch.Phase.CANCEL
                else -> error("unexpected actual local action ${event.action}")
            }
            Triple(phase, event.downTime, event.eventTime)
        } else {
            check(d.root.events.isEmpty()) { "$label reached local touch output during projection" }
            check(source.touchTimes.size == source.touches.size)
            source.touches.zip(source.touchTimes).map { (event, at) -> Triple(event.phase, event.contactId, at) }
        }
        println("TRACE: $label actual phase/downTime/eventTime=$trace; holding=${d.heldInput.holding}")
        check(trace == expected.toList()) { "$label actual output differed: $trace" }
    }

    private fun actualCrossAttachmentCancelCallbackKeepsNewestTap(projection: Boolean) {
        val (d, source) = outputDriver(projection)
        d.hid(HidKeyInput.Phase.DOWN, down = 1_000)
        d.pointer.position = d.pointer.position.copy(x = 80f)
        onOriginalCancel(d, source) {
            // Both live attachments expose the same persisted descriptor/key facts.
            d.hid(HidKeyInput.Phase.DOWN, down = 1_200, attachment = 8)
        }
        d.hid(HidKeyInput.Phase.DOWN, down = 1_100)
        check(d.heldInput.holding)
        d.hid(HidKeyInput.Phase.UP, down = 1_200, at = 1_300, attachment = 8)
        assertOutputTrace(d, source, "cross-attachment replacement projection=$projection",
            Triple(Touch.Phase.DOWN, 1_000L, 1_000L), Triple(Touch.Phase.CANCEL, 1_000L, 1_100L),
            Triple(Touch.Phase.DOWN, 1_200L, 1_200L), Triple(Touch.Phase.UP, 1_200L, 1_300L))
        check(!d.heldInput.holding) { "older attachment7 admission replaced attachment8's release owner" }
        // Neither the replaced admission nor the original contact can terminate/replay the new route.
        d.hid(HidKeyInput.Phase.UP, down = 1_100, at = 1_310)
        d.hid(HidKeyInput.Phase.UP, down = 1_000, at = 1_320)
        check((source?.touches?.size ?: d.root.events.size) == 4)
        if (!projection) {
            check(d.root.first == listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_CANCEL))
            check(d.root.second == listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP))
        }
    }

    private fun actualCompletedOrCanceledNestedAttachmentStillRetiresOuterTap(
        projection: Boolean, terminal: HidKeyInput.Phase,
    ) {
        val (d, source) = outputDriver(projection)
        d.hid(HidKeyInput.Phase.DOWN, down = 1_000)
        d.pointer.position = d.pointer.position.copy(x = 80f)
        onOriginalCancel(d, source) {
            d.hid(HidKeyInput.Phase.DOWN, down = 1_200, attachment = 8)
            d.hid(terminal, down = 1_200, at = 1_230, attachment = 8)
        }
        d.hid(HidKeyInput.Phase.DOWN, down = 1_100)
        val label = "nested attachment $terminal projection=$projection"
        if (terminal == HidKeyInput.Phase.UP) {
            // HeldInputOwner drains this real DOWN and following UP after the old CANCEL stack returns.
            assertOutputTrace(d, source, label,
                Triple(Touch.Phase.DOWN, 1_000L, 1_000L), Triple(Touch.Phase.CANCEL, 1_000L, 1_100L),
                Triple(Touch.Phase.DOWN, 1_200L, 1_200L), Triple(Touch.Phase.UP, 1_200L, 1_230L))
        } else {
            // A queued replacement canceled before dispatch must never inject a DOWN.
            assertOutputTrace(d, source, label,
                Triple(Touch.Phase.DOWN, 1_000L, 1_000L), Triple(Touch.Phase.CANCEL, 1_000L, 1_100L))
        }
        check(!d.heldInput.holding) { "nested $terminal restored the suspended older attachment7 admission" }
        val outputCount = source?.touches?.size ?: d.root.events.size
        d.hid(HidKeyInput.Phase.UP, down = 1_100, at = 1_300)
        d.hid(HidKeyInput.Phase.UP, down = 1_200, at = 1_310, attachment = 8)
        check((source?.touches?.size ?: d.root.events.size) == outputCount)
    }

    private fun actualCrossAttachmentPointerPublicationKeepsNewestTap(projection: Boolean, replacement: Boolean) {
        val (d, source) = outputDriver(projection)
        if (replacement) {
            d.hid(HidKeyInput.Phase.DOWN, down = 1_000)
            d.pointer.position = d.pointer.position.copy(x = 80f)
        }
        d.pointer.onRefresh = { d.hid(HidKeyInput.Phase.DOWN, down = 1_200, attachment = 8) }
        d.hid(HidKeyInput.Phase.DOWN, down = 1_100)
        d.hid(HidKeyInput.Phase.UP, down = 1_200, at = 1_300, attachment = 8)
        val label = "cross-attachment pointer publication projection=$projection replacement=$replacement"
        if (replacement) assertOutputTrace(d, source, label,
            Triple(Touch.Phase.DOWN, 1_000L, 1_000L), Triple(Touch.Phase.CANCEL, 1_000L, 1_100L),
            Triple(Touch.Phase.DOWN, 1_200L, 1_200L), Triple(Touch.Phase.UP, 1_200L, 1_300L))
        else assertOutputTrace(d, source, label,
            Triple(Touch.Phase.DOWN, 1_200L, 1_200L), Triple(Touch.Phase.UP, 1_200L, 1_300L))
        check(!d.heldInput.holding) { "pointer callback attachment8 lost its matching release" }
        val outputCount = source?.touches?.size ?: d.root.events.size
        d.hid(HidKeyInput.Phase.UP, down = 1_100, at = 1_310)
        check((source?.touches?.size ?: d.root.events.size) == outputCount)
    }

    private fun actualReceiverNativeCancelGateRejectsOlderAttachmentTap(projection: Boolean) {
        val (d, source) = outputDriver(projection)
        d.native(PadGestureRecognizer.Key.HELD_CONTACT, PadGestureRecognizer.Phase.DOWN, 1_000)
        d.pointer.position = d.pointer.position.copy(x = 80f)
        onOriginalCancel(d, source) {
            // This runs inside Receiver's gestures.cancel, after HidKeyInput's final pre-output check.
            d.hid(HidKeyInput.Phase.DOWN, down = 1_200, attachment = 8)
        }
        d.hid(HidKeyInput.Phase.DOWN, down = 1_100)
        d.hid(HidKeyInput.Phase.UP, down = 1_200, at = 1_300, attachment = 8)
        assertOutputTrace(d, source, "Receiver native-cancel ownership gate projection=$projection",
            Triple(Touch.Phase.DOWN, 1_000L, 1_000L), Triple(Touch.Phase.CANCEL, 1_000L, 1_100L),
            Triple(Touch.Phase.DOWN, 1_200L, 1_200L), Triple(Touch.Phase.UP, 1_200L, 1_300L))
        check(!d.heldInput.holding) { "Receiver owns(owner) accepted an older TAP after native cancellation reentered" }
        d.hid(HidKeyInput.Phase.UP, down = 1_100, at = 1_310)
        d.native(PadGestureRecognizer.Key.HELD_CONTACT, PadGestureRecognizer.Phase.UP, 1_000, 1_320)
        check((source?.touches?.size ?: d.root.events.size) == 4)
    }

    private fun actualDeferredLocalCancelKeepsNewestAttachmentAndItsRealUp() {
        val d = driver()
        d.root.onFirstDown = {
            d.root.onFirstDown = {}
            d.pointer.position = d.pointer.position.copy(x = 80f)
            d.hid(HidKeyInput.Phase.DOWN, down = 1_100)
        }
        d.root.onFirstCancel = {
            d.root.onFirstCancel = {}
            d.hid(HidKeyInput.Phase.DOWN, down = 1_200, attachment = 8)
        }
        // The actual LocalPointerDispatcher must finish the first target's deferred CANCEL
        // after its DOWN returns, then HeldInputOwner can drain the newest attachment's DOWN.
        d.hid(HidKeyInput.Phase.DOWN, down = 1_000)
        d.hid(HidKeyInput.Phase.UP, down = 1_100, at = 1_250)
        check(d.heldInput.holding) { "old queued attachment7 UP terminated attachment8's deferred contact" }
        d.hid(HidKeyInput.Phase.UP, down = 1_200, at = 1_300, attachment = 8)
        assertOutputTrace(d, null, "cross-attachment deferred actual local CANCEL",
            Triple(Touch.Phase.DOWN, 1_000L, 1_000L), Triple(Touch.Phase.CANCEL, 1_000L, 1_100L),
            Triple(Touch.Phase.DOWN, 1_200L, 1_200L), Triple(Touch.Phase.UP, 1_200L, 1_300L))
        check(d.root.first == listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_CANCEL))
        check(d.root.second == listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP))
        check(!d.heldInput.holding)
    }

    private fun resetAllCancelsActualHeldRouteAndKeepsNativeInputAvailable() {
        val d = driver()
        d.hid(HidKeyInput.Phase.DOWN, down = 1_100)
        SystemClock.now = 1_150
        check(d.hidInput.resetAllBindings())
        check(HidAction.entries.all { d.bindings.binding(it) == null })
        check(d.hidInput.selected == null && !d.heldInput.holding)
        d.hid(HidKeyInput.Phase.UP, down = 1_100, at = 1_170)
        assertOutputTrace(d, null, "reset held HID and suppress its late UP",
            Triple(Touch.Phase.DOWN, 1_100L, 1_100L), Triple(Touch.Phase.CANCEL, 1_100L, 1_150L))

        // Reset changes HID assignments, while the actual native contact path remains available.
        d.pointer.position = d.pointer.position.copy(x = 80f)
        d.native(PadGestureRecognizer.Key.HELD_CONTACT, PadGestureRecognizer.Phase.DOWN, 2_000)
        d.native(PadGestureRecognizer.Key.HELD_CONTACT, PadGestureRecognizer.Phase.UP, 2_000, 2_030)
        assertOutputTrace(d, null, "native contact after HID reset",
            Triple(Touch.Phase.DOWN, 1_100L, 1_100L), Triple(Touch.Phase.CANCEL, 1_100L, 1_150L),
            Triple(Touch.Phase.DOWN, 2_000L, 2_000L), Triple(Touch.Phase.UP, 2_000L, 2_030L))
        check(d.root.first == listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_CANCEL))
        check(d.root.second == listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP))
        check(!d.heldInput.holding)
    }

    private fun resetInsideActualTargetDownDefersCancelAndAllowsFreshRelearning() {
        val d = driver()
        d.root.onFirstDown = {
            d.root.onFirstDown = {}
            SystemClock.now = 1_050
            check(d.hidInput.resetAllBindings())
        }
        // TargetRoot attaches its target after this callback returns, matching ViewGroup's stack.
        d.hid(HidKeyInput.Phase.DOWN, down = 1_000)
        d.hid(HidKeyInput.Phase.UP, down = 1_000, at = 1_100)
        assertOutputTrace(d, null, "reset inside actual local DOWN completes deferred CANCEL",
            Triple(Touch.Phase.DOWN, 1_000L, 1_000L), Triple(Touch.Phase.CANCEL, 1_000L, 1_050L))
        check(d.root.first == listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_CANCEL))
        check(d.root.second.isEmpty() && !d.heldInput.holding)

        d.hover(HidAction.TAP)
        d.hid(HidKeyInput.Phase.DOWN, down = 2_000)
        d.hid(HidKeyInput.Phase.UP, down = 2_000, at = 2_020)
        check(d.bindings.binding(HidAction.TAP) == binding && d.hidInput.selected == null)
        check(d.root.events.size == 2) { "relearning replayed the capture as pointer output" }
        d.hover(null)
        d.pointer.position = d.pointer.position.copy(x = 80f)
        d.hid(HidKeyInput.Phase.DOWN, down = 2_100)
        d.hid(HidKeyInput.Phase.UP, down = 2_100, at = 2_130)
        assertOutputTrace(d, null, "fresh relearned actual HID contact after deferred reset",
            Triple(Touch.Phase.DOWN, 1_000L, 1_000L), Triple(Touch.Phase.CANCEL, 1_000L, 1_050L),
            Triple(Touch.Phase.DOWN, 2_100L, 2_100L), Triple(Touch.Phase.UP, 2_100L, 2_130L))
        check(d.root.second == listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP))
        check(!d.heldInput.holding)
    }

    private fun hoveredRowPinsCaptureAtActualDownAndLeavesHeldReleaseAlone() {
        val d = driver()
        d.hover(HidAction.SCROLL_UP)
        d.hid(HidKeyInput.Phase.DOWN, down = 1_000)
        check(d.hidInput.selected == HidAction.SCROLL_UP)
        val hitTestsAtDown = d.hidPanel.hitTests
        d.hover(HidAction.FUNCTION)
        d.hid(HidKeyInput.Phase.UP, down = 1_000, at = 1_050)
        check(d.bindings.binding(HidAction.SCROLL_UP) == binding)
        check(d.bindings.binding(HidAction.FUNCTION) == null)
        check(d.bindings.binding(HidAction.TAP) == null)
        check(d.hidPanel.hitTests == hitTestsAtDown) { "capture UP chose a different hovered row" }
        check(d.root.events.isEmpty() && !d.heldInput.holding)

        // An already held normal press retains its output lease when a row appears before UP.
        val normal = driver()
        normal.hid(HidKeyInput.Phase.DOWN, down = 2_000)
        normal.hover(HidAction.FUNCTION)
        val heldHitTests = normal.hidPanel.hitTests
        normal.hid(HidKeyInput.Phase.UP, down = 2_000, at = 2_050)
        check(normal.bindings.binding(HidAction.TAP) == binding)
        check(normal.bindings.binding(HidAction.FUNCTION) == null && normal.hidInput.selected == null)
        check(normal.hidPanel.hitTests == heldHitTests) { "owned held release was sent to learning" }
        assertOutputTrace(normal, null, "normal held release while a HID row is hovered",
            Triple(Touch.Phase.DOWN, 2_000L, 2_000L), Triple(Touch.Phase.UP, 2_000L, 2_050L))
        check(!normal.heldInput.holding)
    }

    private fun actualHoverProviderRejectsRefreshInvalidationAndContextPageChanges() {
        for (change in listOf("cancel", "unusable", "session", "projection", "panel-instance", "panel", "connection", "stale-point", "outside-row")) {
            val d = driver()
            check(d.hidInput.resetAllBindings())
            d.hover(HidAction.FUNCTION)
            d.pointer.onRefresh = {
                when (change) {
                    "cancel" -> d.cancelAll()
                    "unusable" -> d.available = false
                    "session" -> d.session = IngressSession()
                    "projection" -> { d.session = IngressSession(); d.connectionView.visibility = View.GONE }
                    "panel-instance" -> d.hidPanel = IngressPanel().apply { row = HidAction.FUNCTION; visibility = View.VISIBLE }
                    "panel" -> d.hidPanel.visibility = View.GONE
                    "connection" -> d.connectionView.visibility = View.GONE
                    "stale-point" -> d.pointer.position = d.pointer.position.copy(available = false)
                    "outside-row" -> d.pointer.position = d.pointer.position.copy(y = 80f)
                }
            }
            SystemClock.now = 3_000
            // No assignments remain. A canceled/context-retired DOWN owns its late UP; an ordinary
            // no-row DOWN remains unbound. Neither route may install the stale learning target.
            val retired = change in setOf("cancel", "unusable", "session", "projection")
            check(d.hidInput.accept(HidKeyInput.Event(7, binding, HidKeyInput.Phase.DOWN, 3_000, 3_000)) == retired) {
                "hover refresh $change admitted a stale learning target"
            }
            SystemClock.now = 3_050
            check(d.hidInput.accept(HidKeyInput.Event(7, binding, HidKeyInput.Phase.UP, 3_000, 3_050)) == retired) {
                "hover refresh $change lost the canceled DOWN's release ownership"
            }
            check(d.hidInput.selected == null)
            check(HidAction.entries.all { d.bindings.binding(it) == null })
            check(d.root.events.isEmpty() && !d.heldInput.holding)
        }
    }

    @JvmStatic fun main(args: Array<String>) {
        val cases = mutableListOf<Pair<String, () -> Unit>>(
            "retained pending native actions" to ::pendingNativeActionsAreCanceledByActualHidAdmission,
            "retained native cancellation before HID DOWN" to ::actualNativeCancellationCanInvalidateHidBeforeItsDown,
            "retained native/HID same timestamp handoff" to ::actualIngressHandsOffNativeAtSameTimestamp,
            "retained original same-key R1 local cancellation" to ::actualOldCancelCallbackKeepsTheNewerHidAdmission,
            "retained original same-key R1 projection timestamps" to ::actualProjectionCancelCallbackKeepsNewerTimestamps,
            "retained original same-key R1 pointer publication" to ::actualPointerPublicationCannotOverwriteNewerFirstOrReplacementDown,
            "retained actual deferred local CANCEL ordering" to ::actualDeferredOldCancelCanAdmitAndReleaseTheNewestPhysicalPress,
            "retained pointer refresh cancellation" to ::pointerRefreshCancellationCannotInventDownOrUp,
            "retained discrete native action handoff" to ::actualDiscreteNativeActionCancelsHidHeldRoute,
            "retained captured projection route" to ::actualCapturedProjectionRouteUsesOriginalSession,
            "retained pending native action pointer context" to ::pointerPublicationCannotRetargetPendingNativeActions,
            "retained pending native action cancellation context" to ::heldCancelCallbackCannotRetargetPendingNativeActions,
            "retained native Tap/Scroll controls" to ::retainedContextStillDeliversNativeTapAndScroll,
        )
        for (projection in listOf(false, true)) {
            val route = if (projection) "projection" else "local"
            cases.add("R3 cross-attachment cancellation $route" to { actualCrossAttachmentCancelCallbackKeepsNewestTap(projection) })
            for (terminal in listOf(HidKeyInput.Phase.UP, HidKeyInput.Phase.CANCEL))
                cases.add("R3 nested attachment $terminal $route" to {
                    actualCompletedOrCanceledNestedAttachmentStillRetiresOuterTap(projection, terminal)
                })
            for (replacement in listOf(false, true))
                cases.add("R3 cross-attachment pointer replacement=$replacement $route" to {
                    actualCrossAttachmentPointerPublicationKeepsNewestTap(projection, replacement)
                })
            cases.add("R3 Receiver native cancellation gate $route" to { actualReceiverNativeCancelGateRejectsOlderAttachmentTap(projection) })
        }
        cases.add("R3 cross-attachment deferred actual local CANCEL" to ::actualDeferredLocalCancelKeepsNewestAttachmentAndItsRealUp)
        cases.add("reset all held cancellation and native input control" to ::resetAllCancelsActualHeldRouteAndKeepsNativeInputAvailable)
        cases.add("reset all during target DOWN and fresh relearning" to ::resetInsideActualTargetDownDefersCancelAndAllowsFreshRelearning)
        cases.add("hover row DOWN pinning and normal held release" to ::hoveredRowPinsCaptureAtActualDownAndLeavesHeldReleaseAlone)
        cases.add("actual hover provider refresh/context/page rejection" to ::actualHoverProviderRejectsRefreshInvalidationAndContextPageChanges)
        val failures = mutableListOf<String>()
        for ((name, run) in cases) {
            try {
                run()
                println("PASS: $name")
            } catch (failure: Throwable) {
                failures.add(name)
                println("FAIL: $name: ${failure.javaClass.simpleName}: ${failure.message}")
            }
        }
        check(failures.isEmpty()) { "${failures.size}/${cases.size} actual HID ingress cases failed: $failures" }
        println("PASS: ${cases.size} extracted actual HID/held initialization, native action dispatch, cancellation and pointer refresh cases")
    }
}
