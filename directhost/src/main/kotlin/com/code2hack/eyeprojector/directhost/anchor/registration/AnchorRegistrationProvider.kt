package com.code2hack.eyeprojector.directhost.anchor.registration

import com.code2hack.eyeprojector.directhost.ProjectionContext
import com.code2hack.eyeprojector.directhost.anchor.execution.AnchorAppRuntime
import com.code2hack.eyeprojector.directhost.anchor.execution.AnchorExecutionStatus
import com.code2hack.eyeprojector.directhost.anchor.execution.AnchorOriginalRoute
import com.code2hack.eyeprojector.directhost.anchor.execution.AnchorRegistrationInstaller
import com.code2hack.eyeprojector.directhost.anchor.execution.CapturedAnchorRequest
import com.code2hack.eyeprojector.directhost.anchor.execution.AnchorRegistrationDiagnostic
import com.code2hack.eyeprojector.directhost.anchor.preferences.AnchorMode
import com.code2hack.eyeprojector.directhost.uiaccess.ManagedUiSource
import com.code2hack.eyeprojector.protocol.*

/** Installed by #122's runtime; all handlers, reads and complete-value saves use its one FIFO. */
internal class AnchorRegistrationProvider : AnchorRegistrationInstaller {
    private data class Token(val route: Any, val context: ProjectionContext, val id: Long, val intent: AnchorIntent)
    private data class Pending(val token: Token, val route: AnchorOriginalRoute, val source: ManagedUiSource,
        var chooser: ReceiverUsability? = null)

    private lateinit var runtime: AnchorAppRuntime
    private lateinit var coordinator: AnchorRegistrationCoordinator<Token>
    private var pending: Pending? = null

    override fun install(runtime: AnchorAppRuntime) {
        this.runtime = runtime
        coordinator = AnchorRegistrationCoordinator(runtime.store)
        runtime.installRegistration(::begin, ::message, ::lost)
    }

    private fun begin(request: CapturedAnchorRequest) {
        val original = request.original
        val token = Token(original.identity, request.source.context, original.transactionId, original.intent)
        if (pending?.let { it.token == token && it.source == request.source } == true) return
        cancelPending()
        if (request.snapshot.request?.source != request.source || original.source() != request.source) {
            runtime.coordinator.reportRegistration(original, AnchorRegistrationDiagnostic.Stage.SOURCE_CHANGED,
                request.source, request.snapshot, detail = "CAPTURE_SOURCE_CHANGED")
            original.feedback(AnchorExecutionStatus.UNAVAILABLE)
            return
        }
        val inspection = coordinator.beginDetailed(token, original.intent, request.snapshot)
        runtime.coordinator.reportRegistration(original, AnchorRegistrationDiagnostic.Stage.CAPTURE,
            request.source, request.snapshot, inspection)
        val capture = inspection.capture
        if (capture !is AnchorRegistrationCapture.Selected) {
            original.feedback(AnchorExecutionStatus.UNAVAILABLE)
            return
        }
        pending = Pending(token, original, request.source)
        val bounds = capture.target.highlight
        original.send(AnchorMessage.Offer(token.id, token.intent,
            AnchorHighlight(bounds.left, bounds.top, bounds.right, bounds.bottom)))
    }

    private fun message(message: AnchorMessage, original: AnchorOriginalRoute) {
        val selected = pending ?: return
        // A numeric id alone is Session-local. Recheck the original route's context and intent.
        if (message.transactionId != selected.token.id || message.intent != selected.token.intent ||
            original.transactionId != selected.token.id || original.intent != selected.token.intent ||
            original.identity != selected.token.route) return
        AnchorAppRuntime.flow("registration_message transaction=${message.transactionId} type=${message.javaClass.simpleName} choice=${(message as? AnchorMessage.Choice)?.choice}")
        when (message) {
            is AnchorMessage.Open -> {
                val chooser = message.chooser
                val originalFact = selected.token.intent.context.usability
                if (selected.chooser == chooser) return
                if (selected.chooser != null || chooser.mode != ReceiverUsabilityMode.LOCAL_PANEL ||
                    chooser.ownerEpoch != originalFact.ownerEpoch || chooser.incarnation != originalFact.incarnation) {
                    cancelPending()
                    return
                }
                // This exact fact arrived through the shared original-transaction OPEN handshake.
                selected.chooser = chooser
            }
            is AnchorMessage.Choice -> {
                if (message.choice == AnchorChoice.CANCEL) { cancelPending(); return }
                if (selected.chooser == null) { cancelPending(); return }
                pending = null
                val outcome = coordinator.choose(selected.token, when (message.choice) {
                    AnchorChoice.CLICK -> AnchorMode.CLICK
                    AnchorChoice.HOLD -> AnchorMode.HOLD
                    AnchorChoice.CANCEL -> error("Cancel already handled")
                })
                runtime.coordinator.reportRegistration(selected.route, AnchorRegistrationDiagnostic.Stage.COMPLETION,
                    selected.source, detail = outcome.javaClass.simpleName, failure = coordinator.lastFailure)
                result(selected.route, when (outcome) {
                    is AnchorRegistrationCompletion.Saved -> when (outcome.anchor.mode) {
                        AnchorMode.CLICK -> AnchorRegistrationResult.SAVED_CLICK
                        AnchorMode.HOLD -> AnchorRegistrationResult.SAVED_HOLD
                    }
                    AnchorRegistrationCompletion.Canceled -> AnchorRegistrationResult.CANCELED
                    AnchorRegistrationCompletion.ContextChanged, AnchorRegistrationCompletion.SaveFailed -> AnchorRegistrationResult.UNAVAILABLE
                })
            }
            else -> Unit
        }
    }

    private fun cancelPending() {
        val selected = pending ?: return
        pending = null
        coordinator.cancel(selected.token)
        runtime.coordinator.reportRegistration(selected.route, AnchorRegistrationDiagnostic.Stage.COMPLETION,
            selected.source, detail = "Canceled")
        result(selected.route, AnchorRegistrationResult.CANCELED)
    }

    private fun lost(original: AnchorOriginalRoute) {
        val selected = pending?.takeIf { it.token.route == original.identity } ?: return
        pending = null
        coordinator.cancel(selected.token)
        runtime.coordinator.reportRegistration(selected.route, AnchorRegistrationDiagnostic.Stage.SOURCE_CHANGED,
            selected.source, detail = "ORIGINAL_CALLBACK_RESOURCE_LOST")
    }

    private fun result(original: AnchorOriginalRoute, outcome: AnchorRegistrationResult) {
        original.send(AnchorMessage.Result(original.transactionId, original.intent, outcome))
    }
}
