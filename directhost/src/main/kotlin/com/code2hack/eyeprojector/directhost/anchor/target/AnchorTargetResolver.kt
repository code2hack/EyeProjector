package com.code2hack.eyeprojector.directhost.anchor.target

import com.code2hack.eyeprojector.directhost.uiaccess.ManagedUiSource
import com.code2hack.eyeprojector.directhost.uiaccess.UiTargetObservation
import com.code2hack.eyeprojector.directhost.uiaccess.UiTargetReadStatus
import com.code2hack.eyeprojector.directhost.uiaccess.UiTargetSnapshot
import com.code2hack.eyeprojector.directhost.uiaccess.UiWindowMetadata
import com.code2hack.eyeprojector.directhost.uiaccess.UiWindowReadStatus
import com.code2hack.eyeprojector.directhost.uiaccess.UiWindowSnapshot

/** Current observation only. Consumers must retain their existing input/context admission. */
data class ResolvedAnchorTarget(
    val source: ManagedUiSource,
    val window: UiWindowMetadata,
    val target: UiTargetObservation,
)

enum class AnchorTargetMissingReason {
    SELECTED_PACKAGE_CHANGED, FOREGROUND_PACKAGE_CHANGED, ACTIVITY_CHANGED,
    CURRENT_WINDOW_ABSENT, WINDOW_TYPE_CHANGED, TARGET_ABSENT, SELECTION_ABSENT,
}

enum class AnchorTargetUnavailableReason {
    READ_UNAVAILABLE, REQUEST_UNAVAILABLE, FOREGROUND_PACKAGE_UNOBSERVED, ACTIVITY_UNOBSERVED,
    WINDOW_NOT_CURRENT, WINDOW_READ_UNAVAILABLE, IDENTITY_UNAVAILABLE,
}

enum class AnchorTargetAmbiguity { CURRENT_WINDOW, TARGET_IDENTITY }

sealed interface AnchorTargetResolution {
    data class Unique(val current: ResolvedAnchorTarget) : AnchorTargetResolution
    data class Missing(val reason: AnchorTargetMissingReason) : AnchorTargetResolution
    data class Ambiguous(val kind: AnchorTargetAmbiguity, val candidateCount: Int) : AnchorTargetResolution
    data class Unavailable(
        val reason: AnchorTargetUnavailableReason,
        val readStatus: UiTargetReadStatus? = null,
        val windowStatus: UiWindowReadStatus? = null,
    ) : AnchorTargetResolution
}

sealed interface AnchorTargetCapture {
    data class Captured(val descriptor: AnchorTargetDescriptor, val current: ResolvedAnchorTarget) : AnchorTargetCapture
    data class Rejected(val resolution: AnchorTargetResolution) : AnchorTargetCapture
}

/**
 * Pure current-screen matching over the production reader's value snapshots. Never reads text,
 * ranks coordinates/layers/ordinals, performs input or mutates a saved descriptor. ID-less controls
 * have no supported persistent identity. Repeated ID/class pairs remain ambiguous.
 */
object AnchorTargetResolver {
    fun capture(snapshot: UiTargetSnapshot, selected: UiTargetObservation): AnchorTargetCapture {
        val lookup = currentWindow(snapshot, selected.packageName, null)
        if (lookup is WindowLookup.Rejected) return AnchorTargetCapture.Rejected(lookup.resolution)
        lookup as WindowLookup.Current
        if (lookup.window.targets.none { it == selected && it.usable() }) {
            return AnchorTargetCapture.Rejected(AnchorTargetResolution.Missing(AnchorTargetMissingReason.SELECTION_ABSENT))
        }
        val id = selected.resourceId?.takeIf { it.isNotBlank() }
            ?: return AnchorTargetCapture.Rejected(AnchorTargetResolution.Unavailable(AnchorTargetUnavailableReason.IDENTITY_UNAVAILABLE))
        val descriptor = AnchorTargetDescriptor(selected.packageName,
            lookup.source.foregroundActivity?.takeIf { it.isNotBlank() }, lookup.window.window.type,
            id, selected.className?.takeIf { it.isNotBlank() })
        return when (val resolution = resolve(descriptor, snapshot)) {
            is AnchorTargetResolution.Unique -> AnchorTargetCapture.Captured(descriptor, resolution.current)
            else -> AnchorTargetCapture.Rejected(resolution)
        }
    }

    fun resolve(descriptor: AnchorTargetDescriptor, snapshot: UiTargetSnapshot): AnchorTargetResolution {
        val lookup = currentWindow(snapshot, descriptor.packageName, descriptor.activityName)
        if (lookup is WindowLookup.Rejected) return lookup.resolution
        lookup as WindowLookup.Current
        val window = lookup.window
        if (window.window.type != descriptor.windowType) {
            return AnchorTargetResolution.Missing(AnchorTargetMissingReason.WINDOW_TYPE_CHANGED)
        }
        val candidates = window.targets.filter { target ->
            target.packageName == descriptor.packageName && target.windowId == window.window.windowId &&
                target.usable() && target.resourceId == descriptor.resourceId &&
                (descriptor.className == null || target.className == descriptor.className)
        }
        return when (candidates.size) {
            0 -> AnchorTargetResolution.Missing(AnchorTargetMissingReason.TARGET_ABSENT)
            1 -> AnchorTargetResolution.Unique(ResolvedAnchorTarget(lookup.source, window.window, candidates.single()))
            else -> AnchorTargetResolution.Ambiguous(AnchorTargetAmbiguity.TARGET_IDENTITY, candidates.size)
        }
    }

    private sealed interface WindowLookup {
        data class Current(val source: ManagedUiSource, val window: UiWindowSnapshot) : WindowLookup
        data class Rejected(val resolution: AnchorTargetResolution) : WindowLookup
    }

    private fun currentWindow(snapshot: UiTargetSnapshot, packageName: String, activityName: String?): WindowLookup {
        fun missing(reason: AnchorTargetMissingReason) = WindowLookup.Rejected(AnchorTargetResolution.Missing(reason))
        fun unavailable(reason: AnchorTargetUnavailableReason) = WindowLookup.Rejected(AnchorTargetResolution.Unavailable(reason))
        if (snapshot.status != UiTargetReadStatus.READ) return WindowLookup.Rejected(
            AnchorTargetResolution.Unavailable(AnchorTargetUnavailableReason.READ_UNAVAILABLE, readStatus = snapshot.status))
        val request = snapshot.request ?: return unavailable(AnchorTargetUnavailableReason.REQUEST_UNAVAILABLE)
        if (request.selectedPackage != packageName) return missing(AnchorTargetMissingReason.SELECTED_PACKAGE_CHANGED)
        val source = request.source
        val foreground = source.foregroundPackage?.takeIf { it.isNotBlank() }
            ?: return unavailable(AnchorTargetUnavailableReason.FOREGROUND_PACKAGE_UNOBSERVED)
        if (foreground != packageName) return missing(AnchorTargetMissingReason.FOREGROUND_PACKAGE_CHANGED)
        if (activityName != null) {
            val actual = source.foregroundActivity?.takeIf { it.isNotBlank() }
                ?: return unavailable(AnchorTargetUnavailableReason.ACTIVITY_UNOBSERVED)
            if (actual != activityName) return missing(AnchorTargetMissingReason.ACTIVITY_CHANGED)
        }
        val scoped = snapshot.windows.filter { it.window.displayId == source.displayId &&
            (request.windowId == null || it.window.windowId == request.windowId) }
        val current = scoped.filter { it.status != UiWindowReadStatus.OTHER_PACKAGE &&
            (request.windowId != null || it.window.active || it.window.focused) }
        if (current.isEmpty()) return missing(AnchorTargetMissingReason.CURRENT_WINDOW_ABSENT)
        if (current.size > 1) return WindowLookup.Rejected(
            AnchorTargetResolution.Ambiguous(AnchorTargetAmbiguity.CURRENT_WINDOW, current.size))
        val window = current.single()
        if (!window.window.active && !window.window.focused) return unavailable(AnchorTargetUnavailableReason.WINDOW_NOT_CURRENT)
        if (window.status != UiWindowReadStatus.READ) return WindowLookup.Rejected(
            AnchorTargetResolution.Unavailable(AnchorTargetUnavailableReason.WINDOW_READ_UNAVAILABLE, windowStatus = window.status))
        return WindowLookup.Current(source, window)
    }

    // UiTargetReader already restricts observations to actionable controls and omits hidden/disabled
    // nodes. Recheck usability without interpreting advertised actions as Click versus Hold intent.
    private fun UiTargetObservation.usable() = visible && enabled && bounds.hasArea
}
