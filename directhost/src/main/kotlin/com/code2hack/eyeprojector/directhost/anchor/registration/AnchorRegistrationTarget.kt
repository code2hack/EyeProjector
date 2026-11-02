package com.code2hack.eyeprojector.directhost.anchor.registration

import com.code2hack.eyeprojector.directhost.anchor.target.AnchorTargetCapture
import com.code2hack.eyeprojector.directhost.anchor.target.AnchorTargetDescriptor
import com.code2hack.eyeprojector.directhost.anchor.target.AnchorTargetResolver
import com.code2hack.eyeprojector.directhost.anchor.target.ResolvedAnchorTarget
import com.code2hack.eyeprojector.directhost.anchor.target.AnchorTargetResolution
import com.code2hack.eyeprojector.directhost.anchor.target.AnchorTargetAmbiguity
import com.code2hack.eyeprojector.directhost.uiaccess.*
import com.code2hack.eyeprojector.protocol.AnchorIntent
import com.code2hack.eyeprojector.protocol.AnchorIntentKind
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

/** Copied evidence from the same selection pass; it never supplies a fallback target. */
data class AnchorRegistrationInspection(
    val capture: AnchorRegistrationCapture,
    val videoHover: UiPoint?,
    val sourceHover: UiPoint?,
    val currentWindowCount: Int,
    val hoverTargetCount: Int,
    val identityMatchCount: Int?,
    val selected: UiTargetObservation?,
    val resolution: AnchorTargetResolution?,
)

/** Select only under the original hover, then use #118's persistent-identity capture. */
object AnchorRegistrationTargets {
    fun capture(intent: AnchorIntent, snapshot: UiTargetSnapshot): AnchorRegistrationCapture =
        inspect(intent, snapshot).capture

    fun inspect(intent: AnchorIntent, snapshot: UiTargetSnapshot): AnchorRegistrationInspection {
        var videoHover: UiPoint? = null
        var sourceHover: UiPoint? = null
        var windowCount = 0
        var targetCount = 0
        var selected: UiTargetObservation? = null
        var resolution: AnchorTargetResolution? = null
        fun finish(capture: AnchorRegistrationCapture): AnchorRegistrationInspection {
            val count = when (val value = resolution) {
                is AnchorTargetResolution.Unique -> 1
                is AnchorTargetResolution.Missing -> 0
                is AnchorTargetResolution.Ambiguous -> value.candidateCount.takeIf {
                    value.kind == AnchorTargetAmbiguity.TARGET_IDENTITY }
                else -> null
            }
            return AnchorRegistrationInspection(capture, videoHover, sourceHover, windowCount,
                targetCount, count, selected, resolution)
        }
        fun reject(reason: AnchorRegistrationFailure) = finish(AnchorRegistrationCapture.Rejected(reason))
        val fact = intent.context.usability
        val pointer = intent.context.pointer
        val request = snapshot.request ?: return reject(AnchorRegistrationFailure.TARGET_UNAVAILABLE)
        val source = request.source
        if (intent.kind != AnchorIntentKind.REGISTER || fact.mode != ReceiverUsabilityMode.PROJECTED ||
            fact.ownerEpoch != source.context.ownerEpoch || pointer.revision != fact.inputRevision ||
            pointer.geometry != source.geometry.video) return reject(AnchorRegistrationFailure.CONTEXT_CHANGED)
        val video = pointer.target() ?: return reject(AnchorRegistrationFailure.HOVER_ABSENT)
        videoHover = UiPoint(video.x.toDouble(), video.y.toDouble())
        if (snapshot.status != UiTargetReadStatus.READ || source.foregroundPackage != request.selectedPackage)
            return reject(AnchorRegistrationFailure.TARGET_UNAVAILABLE)

        // The same integer video point as ordinary managed input, then #117's logical-display
        // transform (rotation, physical letterbox, logical pixels). No root/window/inset offset.
        val hover = source.geometry.videoToSource(UiPoint(video.x.toDouble(), video.y.toDouble()))
        sourceHover = hover
        val windows = snapshot.windows.filter {
            it.window.displayId == source.displayId &&
                (request.windowId == null || request.windowId == it.window.windowId) &&
                it.status != UiWindowReadStatus.OTHER_PACKAGE && (it.window.active || it.window.focused)
        }
        windowCount = windows.size
        if (windows.size != 1 || windows.single().status != UiWindowReadStatus.READ)
            return reject(AnchorRegistrationFailure.TARGET_UNAVAILABLE)
        val window = windows.single()
        val targets = window.targets.filter {
            it.windowId == window.window.windowId && it.packageName == request.selectedPackage &&
                it.visible && it.enabled && it.bounds.hasArea &&
                hover.x >= it.bounds.left && hover.x < it.bounds.right &&
                hover.y >= it.bounds.top && hover.y < it.bounds.bottom
        }
        targetCount = targets.size
        if (targets.isEmpty()) return reject(AnchorRegistrationFailure.HOVER_ABSENT)
        // Ancestors and descendants are one hit branch; siblings/overlays compete. These
        // edges come from this traversal, never numeric order, area, depth or saved paths.
        val selectedBranches = targets.filter { parent -> targets.none { child ->
            child !== parent && parent.ordinal in child.ancestorOrdinals
        } }
        if (selectedBranches.isEmpty()) return reject(AnchorRegistrationFailure.HOVER_ABSENT)
        if (selectedBranches.size > 1) return reject(AnchorRegistrationFailure.HOVER_AMBIGUOUS)
        // Advertised actions do not infer an activation mode or activate the target.
        val element = selectedBranches.single()
        // An ID-less label may be part of an identified parent region. Bind the nearest
        // actual containing ancestor, never another branch or a persisted tree position.
        val chosen = if (!element.resourceId.isNullOrBlank()) element else
            element.ancestorOrdinals.asReversed().firstNotNullOfOrNull { ancestor ->
                targets.singleOrNull { it.ordinal == ancestor && !it.resourceId.isNullOrBlank() }
            } ?: element
        selected = chosen
        return when (val captured = AnchorTargetResolver.capture(snapshot, chosen)) {
            is AnchorTargetCapture.Captured -> {
                resolution = AnchorTargetResolution.Unique(captured.current)
                finish(AnchorRegistrationCapture.Selected(AnchorRegistrationTarget(captured.descriptor, captured.current)))
            }
            is AnchorTargetCapture.Rejected -> {
                resolution = captured.resolution
                reject(AnchorRegistrationFailure.TARGET_UNAVAILABLE)
            }
        }
    }
}
