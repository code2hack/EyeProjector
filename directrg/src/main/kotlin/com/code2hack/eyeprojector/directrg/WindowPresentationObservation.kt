package com.code2hack.eyeprojector.directrg

/** Optional test diagnostics. These values confer no rendering, input or owner authority. */
internal fun interface WindowPresentationObserver {
    fun presented(receipt: SuccessfulWindowReceipt)
}

/** Existing renderer callback receipt after a successful swap return, not optical presentation. */
internal data class SuccessfulWindowReceipt(
    val originalSessionStartedMonoNs: Long?,
    val callbackGeneration: Long,
    val callbackMonoNs: Long,
    val before: OwnerWindowSnapshot?,
    val existingAcceptanceBranchEntered: Boolean,
    val callbackCompleted: Boolean,
    val after: OwnerWindowSnapshot?,
    val callbackReadEndMonoNs: Long,
)

/** Independent memory reads with an explicit span; never an atomic GL/main/control snapshot. */
internal data class OwnerWindowSnapshot(
    val readStartMonoNs: Long,
    val readEndMonoNs: Long,
    val windowGenerationBefore: Long,
    val windowGenerationAfter: Long,
    val currentSessionStartedMonoNs: Long?,
    val currentSessionMatchesOriginal: Boolean?,
    val outputMatchesOriginal: Boolean?,
    val windowMatchesCallback: Boolean,
    val closed: Boolean,
    val mediaState: String,
    val displayedGeneration: Long,
    val currentDisplayed: Boolean,
    val uiUsable: Boolean,
    val localPanel: Boolean,
    val inputRevision: Long,
    val actualReportedMode: String?,
    val actualReportRevision: Long?,
    val reportMatchesOriginalOwner: Boolean?,
    val reportMatchesCurrentDemand: Boolean?,
    val actualUsabilityMode: String?,
    val actualUsabilityRevision: Long?,
    val actualUsabilityInputRevision: Long?,
    val usabilityMatchesOriginalOwner: Boolean?,
    val sessionGeometryWidth: Int?,
    val sessionGeometryHeight: Int?,
    val metadataReferencesStable: Boolean,
)
