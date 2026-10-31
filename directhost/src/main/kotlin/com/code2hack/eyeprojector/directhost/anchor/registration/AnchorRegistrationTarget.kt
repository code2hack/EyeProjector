package com.code2hack.eyeprojector.directhost.anchor.registration

import com.code2hack.eyeprojector.directhost.anchor.target.AnchorTargetCapture
import com.code2hack.eyeprojector.directhost.anchor.target.AnchorTargetDescriptor
import com.code2hack.eyeprojector.directhost.anchor.target.AnchorTargetResolver
import com.code2hack.eyeprojector.directhost.anchor.target.ResolvedAnchorTarget
import com.code2hack.eyeprojector.directhost.uiaccess.*
import com.code2hack.eyeprojector.protocol.FunctionIntent
import com.code2hack.eyeprojector.protocol.FunctionIntentKind
import com.code2hack.eyeprojector.protocol.ReceiverUsabilityMode

/** Pending values, never a saved coordinate, live node or execution contact. */
data class AnchorRegistrationTarget(
    val descriptor: AnchorTargetDescriptor,
    val current: ResolvedAnchorTarget,
) {
    /** Video pixels; the Glasses applies the ORIGINAL receiver Fit to draw the highlight. */
    val highlight: UiRectangle get() = current.source.geometry.sourceToVideo(current.target.bounds)
}

enum class AnchorRegistrationFailure { CONTEXT_CHANGED, TARGET_UNAVAILABLE, HOVER_ABSENT, HOVER_AMBIGUOUS }

sealed interface AnchorRegistrationCapture {
    data class Selected(val target: AnchorRegistrationTarget) : AnchorRegistrationCapture
    data class Rejected(val reason: AnchorRegistrationFailure) : AnchorRegistrationCapture
}

/** Select only under the original hover, then use #118's persistent-identity capture. */
object AnchorRegistrationTargets {
    fun capture(intent: FunctionIntent, snapshot: UiTargetSnapshot): AnchorRegistrationCapture {
        fun reject(reason: AnchorRegistrationFailure) = AnchorRegistrationCapture.Rejected(reason)
        val fact = intent.context.usability
        val pointer = intent.context.pointer
        val request = snapshot.request ?: return reject(AnchorRegistrationFailure.TARGET_UNAVAILABLE)
        val source = request.source
        if (intent.kind != FunctionIntentKind.REGISTER || fact.mode != ReceiverUsabilityMode.PROJECTED ||
            fact.ownerEpoch != source.context.ownerEpoch || pointer.revision != fact.inputRevision ||
            pointer.geometry != source.geometry.video) return reject(AnchorRegistrationFailure.CONTEXT_CHANGED)
        val video = pointer.target() ?: return reject(AnchorRegistrationFailure.HOVER_ABSENT)
        if (snapshot.status != UiTargetReadStatus.READ || source.foregroundPackage != request.selectedPackage)
            return reject(AnchorRegistrationFailure.TARGET_UNAVAILABLE)

        // The same integer video point as ordinary managed input, then #117's logical-display
        // transform (rotation, physical letterbox, logical pixels). No root/window/inset offset.
        val hover = source.geometry.videoToSource(UiPoint(video.x.toDouble(), video.y.toDouble()))
        val windows = snapshot.windows.filter {
            it.window.displayId == source.displayId &&
                (request.windowId == null || request.windowId == it.window.windowId) &&
                it.status != UiWindowReadStatus.OTHER_PACKAGE && (it.window.active || it.window.focused)
        }
        if (windows.size != 1 || windows.single().status != UiWindowReadStatus.READ)
            return reject(AnchorRegistrationFailure.TARGET_UNAVAILABLE)
        val window = windows.single()
        val targets = window.targets.filter {
            it.windowId == window.window.windowId && it.packageName == request.selectedPackage &&
                it.visible && it.enabled && it.bounds.hasArea &&
                hover.x >= it.bounds.left && hover.x < it.bounds.right &&
                hover.y >= it.bounds.top && hover.y < it.bounds.bottom
        }
        if (targets.isEmpty()) return reject(AnchorRegistrationFailure.HOVER_ABSENT)
        if (targets.size > 1) return reject(AnchorRegistrationFailure.HOVER_AMBIGUOUS)
        // Advertised actions do not infer an activation mode or activate the target.
        return when (val captured = AnchorTargetResolver.capture(snapshot, targets.single())) {
            is AnchorTargetCapture.Captured -> AnchorRegistrationCapture.Selected(
                AnchorRegistrationTarget(captured.descriptor, captured.current))
            is AnchorTargetCapture.Rejected -> reject(AnchorRegistrationFailure.TARGET_UNAVAILABLE)
        }
    }
}
