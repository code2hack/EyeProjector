package com.code2hack.eyeprojector.directrg

import com.code2hack.eyeprojector.directrg.HidDecisionTrace.Step
import com.code2hack.eyeprojector.directrg.HidDecisionTrace.observed
import com.code2hack.eyeprojector.protocol.HidNavigationGestureRecognizer

/** Actual HID edges stay separate from Rokid marker/summary normalization. */
internal class HidKeyInput(
    private val bindings: HidBindings,
    private val now: () -> Long,
    private val context: () -> Any?,
    schedule: (Long, () -> Unit) -> (() -> Unit),
    private val position: () -> PointerPosition,
    private val contact: (Any, PadGestureRecognizer.Event) -> Unit,
    private val cancelContact: (Any, Long) -> Unit,
    private val scroll: (Int, PointerPosition, Long) -> Unit,
    function: (FunctionGestureRecognizer.Gesture) -> Unit,
    private val changed: () -> Unit = {},
    private val captureTarget: (HidDecisionTrace.Event?) -> HidAction? = { null },
    functionCapture: (Any) -> Any? = { it },
    private val navigation: (ContextualNavigationAction) -> Unit = {},
    functionCaptureOriginal: ((Any, Long, Long?) -> Any?)? = null,
    functionRetireOriginal: ((Long, Any) -> Unit)? = null,
    registrationRelease: (FunctionGestureRecognizer.Gesture, Boolean) -> Unit = { _, _ -> },
) {
    companion object { private val functionIdentities = FunctionGestureRecognizer.Identity() }

    enum class Phase { DOWN, UP, CANCEL }
    data class Event(
        val attachmentId: Int, val binding: HidBinding, val phase: Phase,
        val downTimeMs: Long, val timeMs: Long, val repeat: Int = 0,
    )
    private data class Key(val attachment: Int, val button: HidButton)
    private class Press(val event: Event, val action: HidAction, val context: Any) { val owner = Any() }
    private data class Capture(val action: HidAction, val event: Event, val context: Any, val trace: HidDecisionTrace.Event?)
    private data class Retired(val key: Key, val downTimeMs: Long)
    private val presses = mutableMapOf<Key, Press>()
    // A pending DOWN owns its transition before any old-target or pointer callback runs.
    private val admissions = mutableMapOf<Key, Press>()
    // Independent physical keys still replace one shared contact, including pending DOWNs.
    private var contactAdmission: Press? = null
    // A canceled capture still owns its release; it must never become ordinary Back/input.
    private val suppressed = mutableSetOf<Retired>()
    private val navigationDowns = mutableMapOf<Key, Long>()
    private var capture: Capture? = null
    private var captureAttempt: Any? = null
    val selected: HidAction? get() = capture?.action
    private var functionAttachment: Int? = null
    private var registrationPress: Event? = null
    private var generation = 0L
    private var admission = 0L // Actual Press publication, including a later completed Scroll.
    private var retiring: MutableList<() -> Unit>? = null
    private val originalSchedule: (Long, () -> Unit) -> (() -> Unit) = { at, action ->
        val cancel = schedule(at, action)
        val original: () -> Unit = { retiring?.add(cancel) ?: cancel() }
        original
    }
    private val recognizer = FunctionGestureRecognizer(now,
        { context()?.let { Pair(it, functionAttachment) } }, originalSchedule, function, edgeOnly = true,
        capture = { functionCapture((it as Pair<*, *>).first!!) }, identity = functionIdentities,
        captureOriginal = functionCaptureOriginal?.let { capture -> { owner, id, parent -> capture((owner as Pair<*, *>).first!!,id,parent) } },
        retireOriginal = functionRetireOriginal?.let { retire -> { id, original ->
            val effect: () -> Unit = {retire(id,original)}
            retiring?.add(effect) ?: effect()
        } }, registrationRelease = { gesture, canceled ->
            val effect: () -> Unit = { registrationRelease(gesture, canceled) }
            retiring?.add(effect) ?: effect()
        })
    private val backHome = HidNavigationGestureRecognizer<Any>(now, { context() == it }, originalSchedule,
        { action, _ -> navigation(when (action) {
            HidNavigationGestureRecognizer.Action.BACK -> ContextualNavigationAction.BACK
            HidNavigationGestureRecognizer.Action.HOME -> ContextualNavigationAction.HOME
            HidNavigationGestureRecognizer.Action.LOCK -> ContextualNavigationAction.LOCK
        }) })

    /** Pure current binding/captured-route observation before the adapter's central admission. */
    fun actionFor(event: Event): HidAction? {
        val key = Key(event.attachmentId, event.binding.button)
        return presses[key]?.takeIf { it.event.downTimeMs == event.downTimeMs }?.action
            ?: admissions[key]?.takeIf { it.event.downTimeMs == event.downTimeMs }?.action
            ?: bindings.action(event.binding.button)
    }

    fun owns(owner: Any): Boolean = presses.values.any {
        it.owner === owner && it.context == context() &&
            (it.action != HidAction.TAP || contactAdmission === it)
    }

    fun resetAllBindings(): Boolean {
        val saved = bindings.resetAll()
        cancel()
        changed()
        return saved
    }

    /** Live Android device facts identify obsolete assignments; unrelated peripherals stay intact. */
    fun clearInvalidBindings(devices: Set<HidDeviceIdentity>) {
        if (HidAction.entries.none { bindings.binding(it)?.button?.device in devices }) return
        bindings.clearDevices(devices)
        if (capture?.event?.binding?.button?.device in devices ||
            admissions.values.any { it.event.binding.button.device in devices }) cancel()
        changed()
    }

    fun accept(event: Event, trace: HidDecisionTrace.Event? = null): Boolean {
        if (consumeRegistrationRelease(event.attachmentId, event.binding.button.keyCode,
                event.binding.button.scanCode, event.phase, event.downTimeMs)) return true
        val key = Key(event.attachmentId, event.binding.button)
        val retired = Retired(key, event.downTimeMs)
        if (retired in suppressed) {
            trace?.record(Step.INPUT_RETIRED, HidDecisionTrace.Fact.YES)
            if (event.phase == Phase.UP) suppressed.remove(retired)
            return true
        }
        val capturing = capture
        if (capturing != null && key == Key(capturing.event.attachmentId, capturing.event.binding.button) &&
            event.downTimeMs == capturing.event.downTimeMs) {
            trace?.record(Step.CAPTURE_MATCH, HidDecisionTrace.Fact.YES, capturing.action)
            if (event.phase != Phase.DOWN) {
                capture = null // Retire before persistence/publication callbacks.
                if (observed(trace, Step.CAPTURE_RELEASE_UP, event.phase == Phase.UP) &&
                    observed(trace, Step.CAPTURE_RELEASE_TIME, event.timeMs >= event.downTimeMs) &&
                    observed(trace, Step.CAPTURE_RELEASE_CONTEXT, context() == capturing.context)) {
                    val saved = bindings.assign(capturing.action, capturing.event.binding)
                    observed(trace, Step.CAPTURE_SAVE, saved, capturing.action)
                } else {
                    suppressed.add(retired)
                    trace?.record(Step.CAPTURE_INVALID_RELEASE, target = capturing.action)
                }
                changed()
            }
            return true
        }
        val old = presses[key]
        val action = old?.action ?: admissions[key]?.action ?: bindings.action(event.binding.button)
        if (observed(trace, Step.INPUT_NO_CONTEXT, context() == null)) {
            if (action != null) cancel()
            return action != null
        }
        if (event.phase == Phase.DOWN) {
            if (navigationDowns[key] == event.downTimeMs) return true
            if (observed(trace, Step.INPUT_REPEAT, admissions[key]?.event?.downTimeMs == event.downTimeMs || event.repeat != 0)) return action != null
            val current = context()
            observed(trace, Step.INPUT_DOWN_CONTEXT_PRESENT, current != null)
            if (current == null) return action != null
            val admitted = generation
            val attempt = Any()
            captureAttempt = attempt
            // A normal bound DOWN owns its per-key/shared-output transition before pointer refresh.
            // An independent Function key can advance without invalidating this contact.
            val press = action?.let { Press(event, it, current) }
            if (press != null) {
                admissions[key] = press
                if (action == HidAction.TAP) contactAdmission = press
                presses.remove(key)
                old?.let { cancelPress(it, event.timeMs) }
            }
            fun stillCurrent() = generation == admitted && context() == current &&
                (press == null || currentAdmission(key, press, admitted))
            if (observed(trace, Step.INPUT_STALE_BEFORE_TARGET, !stillCurrent())) {
                if (press != null) abandon(key, press)
                suppressed.add(retired)
                return true
            }
            val target = captureTarget(trace)
            trace?.record(Step.INPUT_TARGET, if (target == null) HidDecisionTrace.Fact.NO else HidDecisionTrace.Fact.YES, target)
            if (observed(trace, Step.INPUT_STALE_AFTER_TARGET, !stillCurrent() || (target != null && captureAttempt !== attempt))) {
                if (press != null) abandon(key, press)
                suppressed.add(retired)
                return true
            }
            if (target != null) {
                val next = if (event.downTimeMs <= event.timeMs) Capture(target, event, current, trace) else null
                if (next == null) suppressed.add(retired)
                // Transfer this exact pending DOWN to capture, without retiring its own release.
                if (press != null && admissions[key] === press) admissions.remove(key)
                if (contactAdmission === press) contactAdmission = null
                // Publish before old held-target cancellation; nested completion cannot be replayed.
                retire(next, attempt)
                if (capture === next && next != null && context() != next.context) cancel()
                return true
            }
            if (capturing != null && key == Key(capturing.event.attachmentId, capturing.event.binding.button)) {
                suppressed.add(Retired(key, capturing.event.downTimeMs))
                capture = null
                changed()
                if (!stillCurrent()) {
                    if (press != null) abandon(key, press)
                    suppressed.add(retired)
                    return true
                }
            }
            if (press == null) {
                trace?.record(Step.INPUT_NORMAL, HidDecisionTrace.Fact.NO)
                return false
            }
            trace?.record(Step.INPUT_NORMAL, HidDecisionTrace.Fact.YES, action)
            val point = if (action == HidAction.TAP) position() else null
            if (!currentAdmission(key, press, admitted)) { abandon(key, press); return true }
            presses[key] = press // Output may synchronously cancel/change the view.
            admission++
            if (action == HidAction.NAVIGATION) navigationDowns[key] = event.downTimeMs
            when (action) {
                HidAction.TAP -> contact(press.owner, edge(event, PadGestureRecognizer.Phase.DOWN, point))
                HidAction.ANCHOR -> {
                    if (functionAttachment != event.attachmentId) recognizer.cancel()
                    functionAttachment = event.attachmentId
                    recognizer.accept(FunctionGestureRecognizer.Report.DOWN, event.downTimeMs)
                }
                HidAction.NAVIGATION -> backHome.down(retired, key, press.context)
                else -> Unit
            }
            return true
        }
        if (action == null) return false
        if (old == null) {
            admissions[key]?.takeIf { it.event.downTimeMs == event.downTimeMs }?.let { abandon(key, it) }
            return true
        }
        if (old.event.downTimeMs != event.downTimeMs) return true
        val admitted = generation
        // Refresh admission even for a stationary UP. Pointer freshness may synchronously cancel.
        val point = if (event.phase == Phase.UP && old.action in listOf(HidAction.TAP, HidAction.SCROLL_DOWN, HidAction.SCROLL_UP)) position() else null
        if (admitted != generation || presses[key] !== old) return true
        presses.remove(key) // Retire before callbacks.
        if (admissions[key] === old) admissions.remove(key)
        if (contactAdmission === old) contactAdmission = null
        if (event.phase == Phase.CANCEL || old.context != context() || event.timeMs < event.downTimeMs) {
            cancelPress(old, event.timeMs)
            return true
        }
        when (old.action) {
            HidAction.TAP -> contact(old.owner, edge(event, PadGestureRecognizer.Phase.UP, point))
            HidAction.SCROLL_DOWN -> if (point?.available == true) scroll(160, point, event.timeMs)
            HidAction.SCROLL_UP -> if (point?.available == true) scroll(-160, point, event.timeMs)
            HidAction.ANCHOR -> if (functionAttachment == event.attachmentId)
                recognizer.accept(FunctionGestureRecognizer.Report.UP, event.timeMs)
            HidAction.NAVIGATION -> backHome.up(retired, event.timeMs)
        }
        return true
    }

    private fun consumeRegistrationRelease(attachmentId: Int, keyCode: Int, scanCode: Int,
        phase: Phase, downTimeMs: Long): Boolean {
        val original = registrationPress ?: return false
        if (original.attachmentId != attachmentId || original.binding.button.keyCode != keyCode ||
            original.binding.button.scanCode != scanCode || original.downTimeMs != downTimeMs) return false
        if (phase != Phase.DOWN) {
            registrationPress = null
            if (phase == Phase.UP) recognizer.accept(FunctionGestureRecognizer.Report.UP)
            else recognizer.cancel()
        }
        return true
    }

    fun consumeRetired(attachmentId: Int, keyCode: Int, scanCode: Int, phase: Phase, downTimeMs: Long): Boolean {
        if (consumeRegistrationRelease(attachmentId,keyCode,scanCode,phase,downTimeMs)) return true
        val retired = suppressed.firstOrNull { it.key.attachment == attachmentId &&
            it.key.button.keyCode == keyCode && it.key.button.scanCode == scanCode && it.downTimeMs == downTimeMs } ?: return false
        if (phase == Phase.UP) suppressed.remove(retired)
        return true
    }

    /** Correlate the captured physical press before rebuilding its persistent device identity. */
    fun consumeChangedCapture(
        attachmentId: Int, keyCode: Int, scanCode: Int, phase: Phase, downTimeMs: Long,
        descriptor: String, vendorId: Int, productId: Int,
    ): Boolean {
        val captured = capture?.event ?: return false
        val button = captured.binding.button
        if (captured.attachmentId != attachmentId || button.keyCode != keyCode ||
            button.scanCode != scanCode || captured.downTimeMs != downTimeMs) return false
        val device = button.device
        if (device.descriptor == descriptor && device.vendorId == vendorId && device.productId == productId) return false
        cancel()
        consumeRetired(attachmentId, keyCode, scanCode, phase, downTimeMs)
        return true // A callback cannot undo ownership of this already matched capture edge.
    }

    /** A removed InputDevice may already be absent from KeyEvent.device for a queued release. */
    fun consumeDetached(attachmentId: Int, keyCode: Int, scanCode: Int, phase: Phase, downTimeMs: Long): Boolean {
        fun matches(key: Key) = key.attachment == attachmentId && key.button.keyCode == keyCode && key.button.scanCode == scanCode
        val captured = capture?.event?.let { it.attachmentId == attachmentId &&
            it.binding.button.keyCode == keyCode && it.binding.button.scanCode == scanCode && it.downTimeMs == downTimeMs } == true
        val active = admissions.any { (key, press) -> matches(key) && press.event.downTimeMs == downTimeMs }
        if (captured || active) {
            cancel()
            consumeRetired(attachmentId, keyCode, scanCode, phase, downTimeMs)
            return true
        }
        return consumeRetired(attachmentId, keyCode, scanCode, phase, downTimeMs)
    }

    @Suppress("UNUSED_PARAMETER") // Removal invalidates the shared HID context, including pending Function work.
    fun cancelDevice(attachmentId: Int) {
        // Attachment IDs are transient; saved identity and assignments remain untouched.
        cancel()
        // Keep ownership of a canceled capture's late release even if Android removes/reuses its ID.
        // Fresh presses carry no old pending action; each retired release keeps its exact downTime.
    }

    fun prepareCancel(preserveRegistration: Boolean = false): InputRetirement = prepareRetirement(null, null, preserveRegistration)

    fun cancel() = prepareCancel().finishOriginal()

    private fun retire(nextCapture: Capture?, attempt: Any?) = prepareRetirement(nextCapture, attempt).finishOriginal()

    private fun prepareRetirement(nextCapture: Capture?, attempt: Any?, preserveRegistration: Boolean = false): InputRetirement {
        val at = now()
        generation++
        captureAttempt = attempt
        contactAdmission = null
        val oldCapture = capture
        capture = nextCapture
        if (oldCapture != null) suppressed.add(Retired(Key(oldCapture.event.attachmentId, oldCapture.event.binding.button), oldCapture.event.downTimeMs))
        val old = presses.values.toList()
        if (preserveRegistration && recognizer.awaitingRegistrationRelease)
            registrationPress = old.firstOrNull { it.action == HidAction.ANCHOR }?.event ?: registrationPress
        else registrationPress = null
        admissions.forEach { (key, press) -> if (press.event !== registrationPress) suppressed.add(Retired(key, press.event.downTimeMs)) }
        admissions.clear()
        presses.clear()
        val effects = mutableListOf<() -> Unit>()
        retiring = effects
        try { recognizer.cancel(preserveRegistration); if (!preserveRegistration) functionAttachment = null; backHome.cancel() }
        finally { retiring = null }
        val originalGeneration = generation
        val originalAdmission = admission
        val originalFunction = recognizer.retirementState()
        effects.add { oldCapture?.trace?.record(if (nextCapture == null) Step.CAPTURE_CANCEL else Step.CAPTURE_RETIRE, target = oldCapture.action) }
        effects.add { nextCapture?.trace?.record(Step.CAPTURE_BEGIN, target = nextCapture.action) }
        old.filter { it.action == HidAction.TAP }.forEach { press -> effects.add { cancelPress(press, at) } }
        if (oldCapture != null || nextCapture != null) effects.add(changed)
        return InputRetirement({ InputRetirement.finishAll(effects) }, {
            generation != originalGeneration || admission != originalAdmission || recognizer.retirementState() != originalFunction
        })
    }

    private fun currentAdmission(key: Key, press: Press, admitted: Long): Boolean =
        admissions[key] === press && generation == admitted && context() == press.context &&
            (press.action != HidAction.TAP || contactAdmission === press)

    private fun abandon(key: Key, press: Press) {
        if (admissions[key] !== press) return
        admissions.remove(key)
        if (contactAdmission === press) contactAdmission = null
        suppressed.add(Retired(key, press.event.downTimeMs))
    }
    private fun cancelPress(press: Press, at: Long) {
        when (press.action) {
            HidAction.TAP -> cancelContact(press.owner, at)
            HidAction.ANCHOR -> if (functionAttachment == press.event.attachmentId) recognizer.cancel()
            HidAction.NAVIGATION -> backHome.cancel(Retired(Key(press.event.attachmentId, press.event.binding.button), press.event.downTimeMs))
            else -> Unit
        }
    }
    private fun edge(event: Event, phase: PadGestureRecognizer.Phase, point: PointerPosition?) = PadGestureRecognizer.Event(
        PadGestureRecognizer.Key.HELD_CONTACT, phase, event.timeMs, event.downTimeMs,
        position = point,
    )
}
