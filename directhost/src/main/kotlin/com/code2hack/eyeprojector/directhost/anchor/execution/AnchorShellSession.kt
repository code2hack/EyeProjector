package com.code2hack.eyeprojector.directhost.anchor.execution

import com.code2hack.eyeprojector.directhost.anchor.preferences.AnchorSettingContext
import com.code2hack.eyeprojector.directhost.uiaccess.ManagedUiSource
import com.code2hack.eyeprojector.directhost.input.FunctionWork
import com.code2hack.eyeprojector.protocol.*
import android.os.IBinder
import java.io.Closeable
import java.util.concurrent.atomic.AtomicLong

/** Original shell Session's Binder entrypoint. External source/Binder work never holds input locks. */
internal class AnchorShellSession(
    private val input: AnchorSessionInput,
    private val source: () -> ManagedUiSource?,
    private val accepts: (FunctionIntent, ManagedUiSource) -> Boolean,
    private val ordinaryHandoff: ((() -> Boolean) -> Boolean),
    private val stateWork: FunctionWork,
    private val failure: (Exception) -> Unit,
    private val send: (AnchorMessage) -> Unit,
    private val rawEdge: (AnchorControlAdmission, Long, PhoneHidEdge, String, Long) -> Unit = { _, _, _, _, _ -> },
) : Closeable {
    private var controlContext = com.code2hack.eyeprojector.directhost.ProjectionContext()
    private val controlPort by lazy { object : IAnchorControlSession.Stub() {
        override fun captureRawControl(disposition: IntArray): IAnchorRawControl? {
            if (closed) { disposition[0] = AnchorControlDisposition.RETIRED.ordinal; return null }
            val original = AnchorControlAdmission()
            input.anchor.receiveControl(original, function = true)
            disposition[0] = original.disposition.ordinal // Same original result; never query a later Hold.
            original.sourceBinding = binding to binding.get()
            original.sourceRevision = sourceRevision to sourceRevision.get()
            return object : IAnchorRawControl.Stub() {
                override fun admitUserControl(): Int {
                    if (closed) return AnchorControlDisposition.RETIRED.ordinal
                    original.ordinary()
                    return input.consumeHeldControl(original).ordinal
                }
                override fun edge(routeId: Long, typedEdge: ByteArray, regionId: String, word: Long) {
                    val edge = runCatching { PhoneHidEdge.from(typedEdge) }.getOrNull() ?: return
                    if (closed) return
                    if (edge.action != PhoneHidAction.FUNCTION) original.ordinary()
                    rawEdge(original, routeId, edge, regionId, word)
                }
            }
        }
        override fun admitUserControl(recognitionOnly: Boolean): Int {
            if (closed) return AnchorControlDisposition.RETIRED.ordinal
            val original = AnchorControlAdmission()
            input.anchor.receiveControl(original, recognitionOnly)
            if (original.terminal || recognitionOnly) return original.disposition.ordinal
            return input.consumeHeldControl(original).ordinal
        }
    } }

    fun controlContext(original: com.code2hack.eyeprojector.directhost.ProjectionContext) { controlContext = original }
    private class Binding(val client: IAnchorAppBridge) {
        val binder: IBinder = client.asBinder()
        var death: IBinder.DeathRecipient? = null
        fun unlink() { death?.let { runCatching { binder.unlinkToDeath(it, 0) } }; death = null }
    }
    private val binding = java.util.concurrent.atomic.AtomicReference<Binding?>()
    private val sourceRevision = AtomicLong()
    @Volatile private var closed = false
    private val sequence = AtomicLong()
    private val registration = java.util.concurrent.atomic.AtomicReference<Operation?>()
    private val localControls = java.util.concurrent.ConcurrentHashMap.newKeySet<LocalControl>()

    fun install(client: IAnchorAppBridge?) {
        if (closed || binding.get()?.binder == client?.asBinder()) return
        val fresh = client?.let(::Binding)
        input.anchor.retire()
        val previous = binding.getAndSet(fresh)
        previous?.unlink()
        localControls.filter { it.original === previous }.forEach { it.retire(deferred = true) }
        cancelRegistration(registration.get()?.takeIf { it.original === previous }, deferred = true)
        if (fresh != null) {
            val death = IBinder.DeathRecipient {
                if (binding.compareAndSet(fresh, null)) {
                    localControls.filter { it.original === fresh }.forEach { it.retire(deferred = true) }
                    cancelRegistration(registration.get()?.takeIf { it.original === fresh }, deferred = true)
                    input.anchor.retire()
                }
            }
            fresh.death = death
            try {
                fresh.binder.linkToDeath(death, 0)
                if (!closed && binding.get() === fresh)
                    fresh.client.onSession(controlContext.ownerEpoch, controlContext.projectionToken, controlPort)
            }
            catch (_: Exception) { if (binding.compareAndSet(fresh, null)) {
                localControls.filter { it.original === fresh }.forEach { it.retire(deferred = true) }
                input.anchor.retire()
            } }
            if (closed || binding.get() !== fresh) { binding.compareAndSet(fresh, null); fresh.unlink() }
        }
    }

    fun accept(admission: AnchorAdmission) {
        val original = binding.get()
        val id = sequence.incrementAndGet()
        if (!input.anchor.isCurrent(admission)) return
        if (admission.intent.kind == FunctionIntentKind.REGISTER &&
            input.anchor.consumeHeldControl(admission, captureNormal = false) != AnchorControlDisposition.NORMAL) return
        if (original == null || closed) {
            send(AnchorMessage.Feedback(id, admission.intent, AnchorFeedback.UNAVAILABLE)); return
        }
        val operation = Operation(id, admission, original)
        try { original.client.onIntent(id, admission.intent.bytes(), operation) }
        catch (_: Exception) { operation.retire(); send(AnchorMessage.Feedback(id, admission.intent, AnchorFeedback.UNAVAILABLE)) }
    }

    /** Capture before the Session work queue; a later app binding cannot adopt this control. */
    fun localControl(control: AnchorControlAdmission, current: () -> Boolean,
        complete: (AnchorControlDisposition) -> Unit): () -> Unit {
        val original = binding.get()
        control.sourceBinding = binding to original
        control.sourceRevision = sourceRevision to sourceRevision.get()
        val operation = LocalControl(original, control, current, complete)
        localControls.add(operation)
        return {
            val original = operation.original
            if (closed || binding.get() !== original) operation.retire()
            else if (original == null) operation.apply()
            else try { original.client.onControl(operation) }
            catch (error: Exception) { operation.retire(); failure(error) }
        }
    }

    private inner class LocalControl(val original: Binding?, val control: AnchorControlAdmission,
        val current: () -> Boolean, val complete: (AnchorControlDisposition) -> Unit) : IAnchorUserControl.Stub() {
        private val finished = java.util.concurrent.atomic.AtomicBoolean()
        override fun apply() {
            if (!finished.compareAndSet(false, true)) return
            localControls.remove(this)
            val result = if (closed || binding.get() !== original || !current()) AnchorControlDisposition.RETIRED
                else input.consumeHeldControl(control)
            complete(result)
        }
        fun retire(deferred: Boolean = false) {
            if (!finished.compareAndSet(false, true)) return
            localControls.remove(this)
            // Teardown never waits for a peer socket write. The original reply is terminal
            // before scheduling, so a late Binder callback cannot publish NORMAL.
            val finish: () -> Unit = { complete(AnchorControlDisposition.RETIRED) }
            if (deferred) stateWork.submit { try { finish() } catch (error: Exception) { failure(error) } }
            else finish()
        }
    }

    private inner class Operation(val id: Long, val admission: AnchorAdmission, val original: Binding) : IAnchorSession.Stub() {
        @Volatile var chooser: ReceiverUsability? = null
        @Volatile var chooserObserved = false
        private fun attached() = !closed && binding.get() === original
        private fun alive() = attached() && original.binder.isBinderAlive
        private val stateOutput = AnchorHoldOutput(stateWork) { revision, state ->
            if (!closed) try { send.invoke(AnchorMessage.HeldState(id, admission.intent, revision, state)) }
            catch (error: Exception) { failure(error) }
        }
        private fun currentControl() = admission.intent.kind != FunctionIntentKind.REGISTER ||
            input.anchor.isCurrent(admission.control)
        override fun source(): ByteArray? = if (alive() && currentControl()) source.invoke()?.bytes() else null
        override fun reconcile(settingContext: ByteArray): Boolean = alive() &&
            input.anchor.reconcile(AnchorExecutionCodec.setting(settingContext),
                admission.control.takeIf { admission.intent.kind == FunctionIntentKind.REGISTER })
        override fun click(settingContext: ByteArray, resolvedTarget: ByteArray): Int {
            if (!alive()) return AnchorExecutionStatus.RETIRED.ordinal
            val setting = AnchorExecutionCodec.setting(settingContext)
            val target = AnchorExecutionCodec.target(resolvedTarget)
            val current = source.invoke() // Foreground/source query is outside the owner/control lock.
            return try { input.click(admission, setting, target, current,
                { attached() && current != null && accepts(admission.intent, current) }, ordinaryHandoff).ordinal }
            catch (error: Exception) { failure(error); AnchorExecutionStatus.INPUT_REJECTED.ordinal }
        }
        override fun heldSetting(): ByteArray? =
            if (alive()) input.anchor.heldSetting(admission)?.let(AnchorExecutionCodec::setting) else null
        override fun consumeHeldControl(): Int {
            if (!alive()) return AnchorControlDisposition.RETIRED.ordinal
            return try { input.anchor.consumeHeldControl(admission).ordinal }
            catch (error: Exception) { failure(error); AnchorControlDisposition.RETIRED.ordinal }
        }
        override fun releaseHeld(expectedSetting: ByteArray, settingCurrent: Boolean): Int {
            if (!alive()) return AnchorReleaseResult.RETIRED.ordinal
            val expected = expectedSetting.takeIf { it.isNotEmpty() }?.let(AnchorExecutionCodec::setting)
            val current = if (expected != null && settingCurrent) source.invoke() else null
            return try { input.anchor.releaseHeld(admission, expected, settingCurrent, current,
                { attached() && current != null && accepts(admission.intent, current) }).ordinal }
            catch (error: Exception) { failure(error); AnchorReleaseResult.INPUT_REJECTED.ordinal }
        }
        override fun hold(settingContext: ByteArray, resolvedTarget: ByteArray): Int {
            if (!alive()) return AnchorExecutionStatus.RETIRED.ordinal
            val setting = AnchorExecutionCodec.setting(settingContext)
            val target = AnchorExecutionCodec.target(resolvedTarget)
            val current = source.invoke()
            return try { input.hold(admission, setting, target, current,
                { attached() && current != null && accepts(admission.intent, current) }, ordinaryHandoff, stateOutput).ordinal }
            catch (error: Exception) { failure(error); AnchorExecutionStatus.INPUT_REJECTED.ordinal }
        }
        override fun retire() { if (alive() && currentControl()) input.anchor.retire(admission) }
        override fun send(typedMessage: ByteArray) {
            if (!alive()) return
            val message = AnchorMessage.from(typedMessage)
            require(message.transactionId == id && message.intent == admission.intent)
            when (message) {
                is AnchorMessage.Offer -> {
                    val current = source.invoke() ?: return
                    if (!input.anchor.isCurrent(admission) || !accepts(admission.intent, current)) return
                    registration.set(this)
                }
                is AnchorMessage.Result -> if (!registration.compareAndSet(this, null)) return
                is AnchorMessage.Feedback -> Unit
                else -> error("Invalid app Anchor response")
            }
            send.invoke(message)
        }
    }

    /** OPEN precedes publication of exactly this chooser fact; unrelated revisions cannot adopt it. */
    fun receive(message: AnchorMessage) {
        val operation = registration.get() ?: return
        if (operation.id != message.transactionId || operation.admission.intent != message.intent ||
            binding.get() !== operation.original || closed) return
        when (message) {
            is AnchorMessage.Open -> {
                val original = operation.admission.intent.context.usability
                val current = source() ?: return
                if (operation.chooser != null || !accepts(operation.admission.intent, current) ||
                    message.chooser.ownerEpoch != original.ownerEpoch || message.chooser.incarnation != original.incarnation) return
                operation.chooser = message.chooser
            }
            is AnchorMessage.Choice -> if (message.choice != AnchorChoice.CANCEL && !operation.chooserObserved) return
            else -> error("Invalid receiver Anchor message")
        }
        operation.original.client.onRegistration(message.bytes(), operation)
    }

    fun usability(fact: ReceiverUsability) {
        val operation = registration.get() ?: return
        val expected = operation.chooser ?: operation.admission.intent.context.usability
        val exactOpening = operation.chooser == null || operation.chooserObserved || fact == expected
        if (!exactOpening || fact.ownerEpoch != expected.ownerEpoch || fact.incarnation != expected.incarnation ||
            fact.inputRevision != expected.inputRevision || fact.mode != expected.mode) {
            cancelRegistration(operation)
            runCatching { operation.original.client.onRegistration(
                AnchorMessage.Choice(operation.id, operation.admission.intent, AnchorChoice.CANCEL).bytes(), operation) }
        } else if (operation.chooser != null) operation.chooserObserved = true
    }

    private fun cancelRegistration(original: Operation?, deferred: Boolean = false) {
        if (original == null || !registration.compareAndSet(original, null)) return
        val message = AnchorMessage.Result(original.id, original.admission.intent, AnchorRegistrationResult.CANCELED)
        val finish: () -> Unit = { send(message) }
        // Capture/detach exactly once before output. Replacement and death never occupy a
        // teardown caller with peer transport work, even when its Session writer is blocked.
        if (deferred) stateWork.submit { try { finish() } catch (error: Exception) { failure(error) } }
        else finish()
    }

    // Original dispatcher cleanup remains in Session's bounded HeldInputRelease, after early
    // admission retirement. A blocked DOWN must not stop socket/child release on the close caller.
    override fun close() {
        closed = true
        sourceRevision.incrementAndGet()
        binding.getAndSet(null)?.unlink()
        localControls.toList().forEach { it.retire(deferred = true) }
        registration.set(null)
    }
}
