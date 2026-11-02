package com.code2hack.eyeprojector.directhost.anchor.execution

import com.code2hack.eyeprojector.directhost.anchor.registration.AnchorRegistrationCapture
import com.code2hack.eyeprojector.directhost.anchor.registration.AnchorRegistrationInspection
import com.code2hack.eyeprojector.directhost.anchor.target.AnchorTargetResolution
import com.code2hack.eyeprojector.directhost.uiaccess.ManagedUiSource
import com.code2hack.eyeprojector.directhost.uiaccess.UiTargetSnapshot
import com.code2hack.eyeprojector.protocol.FunctionIntent

/** Original FIFO observation only. No live node, Binder, target text or input permission. */
internal data class AnchorRegistrationDiagnostic(
    val transactionId: Long,
    val intent: FunctionIntent,
    val stage: Stage,
    val source: ManagedUiSource? = null,
    val snapshot: UiTargetSnapshot? = null,
    val inspection: AnchorRegistrationInspection? = null,
    val detail: String? = null,
    val failure: Exception? = null,
) {
    enum class Stage { CONTROL_DISPOSITION, SOURCE_RETIRED, FOREGROUND_UNAVAILABLE, SETTING_RETIRED, SNAPSHOT_RETIRED,
        PROVIDER_UNAVAILABLE, CAPTURE, SOURCE_CHANGED, COMPLETION, EXCEPTION }

    /** Metadata only; serialization/logging is dispatched off the original app FIFO. */
    fun render(): String {
        val pointer = intent.context.pointer
        val resolution = when (val value = inspection?.resolution) {
            is AnchorTargetResolution.Unavailable -> value.reason.name
            is AnchorTargetResolution.Missing -> value.reason.name
            is AnchorTargetResolution.Ambiguous -> value.kind.name
            is AnchorTargetResolution.Unique -> "UNIQUE"
            null -> null
        }
        val capture = when (val value = inspection?.capture) {
            is AnchorRegistrationCapture.Rejected -> value.reason.name
            is AnchorRegistrationCapture.Selected -> "SELECTED"
            null -> null
        }
        fun quoted(value: Any?): String = value?.toString()?.let { text ->
            "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r") + "\""
        } ?: "null"
        val fields = linkedMapOf<String, Any?>(
            "stage" to stage, "transaction" to transactionId, "functionSource" to intent.source,
            "gesture" to intent.gestureId, "functionSequence" to intent.sequence,
            "owner" to intent.context.usability.ownerEpoch,
            "incarnation" to intent.context.usability.incarnation,
            "inputRevision" to intent.context.usability.inputRevision,
            "sourceOwner" to source?.context?.ownerEpoch, "sourceToken" to source?.context?.projectionToken,
            "display" to source?.displayId, "displayGeneration" to source?.displayGeneration,
            "displayUniqueId" to source?.displayUniqueId, "package" to source?.foregroundPackage,
            "geometry" to source?.geometry, "pointerX" to pointer.x, "pointerY" to pointer.y,
            "pointerAvailable" to pointer.available, "pointerProjected" to pointer.projected,
            "fit" to pointer.fit, "videoGeometry" to pointer.geometry,
            "readStatus" to snapshot?.status, "readFailureType" to snapshot?.failureClass,
            "readFailureIdentity" to snapshot?.failure?.let(System::identityHashCode),
            "readSource" to snapshot?.request?.source, "readPackage" to snapshot?.request?.selectedPackage,
            "readWindow" to snapshot?.request?.windowId, "capture" to capture, "resolution" to resolution,
            "videoHover" to inspection?.videoHover, "sourceHover" to inspection?.sourceHover,
            "windowCount" to snapshot?.windows?.size, "currentWindowCount" to inspection?.currentWindowCount,
            "hoverTargetCount" to inspection?.hoverTargetCount, "identityMatchCount" to inspection?.identityMatchCount,
            "selectedWindow" to inspection?.selected?.windowId, "resourceId" to inspection?.selected?.resourceId,
            "className" to inspection?.selected?.className, "bounds" to inspection?.selected?.bounds,
            "windows" to snapshot?.windows?.map { listOf(it.window.displayId, it.window.windowId,
                it.window.type, it.window.active, it.window.focused, it.status, it.targets.size,
                it.failureClass, it.failure?.let(System::identityHashCode)) },
            "detail" to detail, "exceptionType" to failure?.javaClass?.name,
            "exceptionIdentity" to failure?.let(System::identityHashCode),
        )
        return fields.entries.joinToString(" ") { (key, value) -> "$key=${quoted(value)}" }
    }
}
