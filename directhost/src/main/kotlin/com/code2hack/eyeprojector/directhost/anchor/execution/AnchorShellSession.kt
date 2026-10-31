package com.code2hack.eyeprojector.directhost.anchor.execution

import com.code2hack.eyeprojector.directhost.anchor.preferences.AnchorSettingContext
import com.code2hack.eyeprojector.directhost.uiaccess.ManagedUiSource
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
    private val failure: (Exception) -> Unit,
    private val send: (AnchorMessage) -> Unit,
) : Closeable {
    private class Binding(val client: IAnchorAppBridge) {
        val binder: IBinder = client.asBinder()
        var death: IBinder.DeathRecipient? = null
        fun unlink() { death?.let { runCatching { binder.unlinkToDeath(it, 0) } }; death = null }
    }
    private val binding = java.util.concurrent.atomic.AtomicReference<Binding?>()
    @Volatile private var closed = false
    private val sequence = AtomicLong()
    @Volatile private var registration: Operation? = null

    fun install(client: IAnchorAppBridge?) {
        if (closed || binding.get()?.binder == client?.asBinder()) return
        val fresh = client?.let(::Binding)
        input.anchor.retire()
        val previous = binding.getAndSet(fresh)
        previous?.unlink()
        cancelRegistration()
        if (fresh != null) {
            val death = IBinder.DeathRecipient {
                if (binding.compareAndSet(fresh, null)) { cancelRegistration(); input.anchor.retire() }
            }
            fresh.death = death
            try { fresh.binder.linkToDeath(death, 0) }
            catch (_: Exception) { if (binding.compareAndSet(fresh, null)) input.anchor.retire() }
            if (closed || binding.get() !== fresh) { binding.compareAndSet(fresh, null); fresh.unlink() }
        }
    }

    fun accept(admission: AnchorAdmission) {
        val original = binding.get()
        val id = sequence.incrementAndGet()
        if (!input.anchor.isCurrent(admission)) return
        if (original == null || closed) {
            send(AnchorMessage.Feedback(id, admission.intent, AnchorFeedback.UNAVAILABLE)); return
        }
        val operation = Operation(id, admission, original)
        try { original.client.onIntent(id, admission.intent.bytes(), operation) }
        catch (_: Exception) { operation.retire(); send(AnchorMessage.Feedback(id, admission.intent, AnchorFeedback.UNAVAILABLE)) }
    }

    private inner class Operation(val id: Long, val admission: AnchorAdmission, val original: Binding) : IAnchorSession.Stub() {
        @Volatile var chooser: ReceiverUsability? = null
        @Volatile var chooserObserved = false
        private fun alive() = !closed && binding.get() === original && original.binder.isBinderAlive
        override fun source(): ByteArray? = if (alive()) source.invoke()?.bytes() else null
        override fun reconcile(settingContext: ByteArray): Boolean = alive() &&
            input.anchor.reconcile(AnchorExecutionCodec.setting(settingContext))
        override fun click(settingContext: ByteArray, resolvedTarget: ByteArray): Int {
            if (!alive()) return AnchorExecutionStatus.RETIRED.ordinal
            val setting = AnchorExecutionCodec.setting(settingContext)
            val target = AnchorExecutionCodec.target(resolvedTarget)
            val current = source.invoke() // Foreground/source query is outside the owner/control lock.
            return try { input.click(admission, setting, target, current,
                { alive() && current != null && accepts(admission.intent, current) }, ordinaryHandoff).ordinal }
            catch (error: Exception) { failure(error); AnchorExecutionStatus.INPUT_REJECTED.ordinal }
        }
        override fun retire() { if (alive()) input.anchor.retire() }
        override fun send(typedMessage: ByteArray) {
            if (!alive()) return
            val message = AnchorMessage.from(typedMessage)
            require(message.transactionId == id && message.intent == admission.intent)
            when (message) {
                is AnchorMessage.Offer -> {
                    val current = source.invoke() ?: return
                    if (!input.anchor.isCurrent(admission) || !accepts(admission.intent, current)) return
                    registration = this
                }
                is AnchorMessage.Result -> { if (registration !== this) return; registration = null }
                is AnchorMessage.Feedback -> Unit
                else -> error("Invalid app Anchor response")
            }
            send.invoke(message)
        }
    }

    /** OPEN precedes publication of exactly this chooser fact; unrelated revisions cannot adopt it. */
    fun receive(message: AnchorMessage) {
        val operation = registration ?: return
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
        val operation = registration ?: return
        val expected = operation.chooser ?: operation.admission.intent.context.usability
        val exactOpening = operation.chooser == null || operation.chooserObserved || fact == expected
        if (!exactOpening || fact.ownerEpoch != expected.ownerEpoch || fact.incarnation != expected.incarnation ||
            fact.inputRevision != expected.inputRevision || fact.mode != expected.mode) {
            cancelRegistration()
            runCatching { operation.original.client.onRegistration(
                AnchorMessage.Choice(operation.id, operation.admission.intent, AnchorChoice.CANCEL).bytes(), operation) }
        } else if (operation.chooser != null) operation.chooserObserved = true
    }

    private fun cancelRegistration() {
        val original = registration ?: return
        registration = null
        send(AnchorMessage.Result(original.id, original.admission.intent, AnchorRegistrationResult.CANCELED))
    }

    // Original dispatcher cleanup remains in Session's bounded HeldInputRelease, after early
    // admission retirement. A blocked DOWN must not stop socket/child release on the close caller.
    override fun close() { closed = true; binding.getAndSet(null)?.unlink(); registration = null }
}
