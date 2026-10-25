package com.code2hack.eyeprojector.directhost.notifications

import java.util.IdentityHashMap

/** Diagnostic journal only. Opaque platform metadata is compared in memory, never rendered. */
internal class NotificationProbeReport(val runId: String, val packageName: String) {
    internal data class Receipt(
        val ingress: Any, val membership: Any, val epoch: Any, val original: Any,
        val packageName: String, val key: String, val postTime: Long,
        val origin: String, val kind: String, val baseline: String,
        val admissionCaptured: Boolean,
    )
    internal data class Pipeline(
        val ingress: Any, val selection: Any, val demand: Any, val stage: String, val permitState: String,
        val demandGoal: String, val arm: Any?, val ready: Any?, val activation: Any?, val attempt: Any?,
        val context: Any?, val carrier: Any?, val identitiesMatch: Boolean,
        val commitment: String?, val rejection: String?, val faultStage: String?, val resultPresent: Boolean?,
        val launchStatus: String?, val resultContextMatches: Boolean?, val requestedPackageMatches: Boolean?,
        val foregroundMatches: Boolean?,
    )

    private val receipts = mutableListOf<Receipt>()
    private val pipeline = mutableListOf<Pipeline>()
    private var selectedReceipt: Receipt? = null
    private var observationStart = 0
    private val labels = IdentityHashMap<Any, String>()
    private val pipelineDefaults = linkedMapOf(
        "pipelineCorrelation" to "NO_MATCHED_ORIGINAL_RECEIPT", "selectionG" to "UNOBSERVED",
        "projectedDemandD" to "UNOBSERVED", "demandGoal" to "UNOBSERVED", "arm" to "UNOBSERVED",
        "ready" to "UNOBSERVED", "activationH" to "UNOBSERVED", "attemptA" to "UNOBSERVED",
        "contextC" to "UNOBSERVED", "carrier" to "UNOBSERVED", "observedPermitState" to "UNOBSERVED",
        "pipelineStages" to "NONE", "settlementCount" to "0", "claim" to "UNOBSERVED",
        "settlement" to "UNOBSERVED", "foreground" to "UNOBSERVED",
    )
    private val values = linkedMapOf(
        "runId" to runId, "postingPackage" to packageName,
        "semanticNovelty" to "UNKNOWN", "baselineCompleteness" to "UNKNOWN",
        "nativeOriginContinuity" to "UNKNOWN", "pipelineCorrelation" to "NO_MATCHED_ORIGINAL_RECEIPT",
        "selectionG" to "UNOBSERVED", "projectedDemandD" to "UNOBSERVED",
        "ready" to "UNOBSERVED", "carrier" to "UNOBSERVED",
        "claim" to "UNOBSERVED", "settlement" to "UNOBSERVED",
        "foreground" to "UNOBSERVED", "globalHome" to "UNVERIFIED",
        "systemReceipt" to "UNOBSERVED", "postCall" to "NOT_ATTEMPTED",
    ).apply { putAll(pipelineDefaults) }

    fun put(name: String, value: String) { values[name] = value }
    fun pipeline(event: Pipeline) { pipeline.add(event) }
    fun receipt(receipt: Receipt) {
        if (receipt.packageName == packageName) receipts.add(receipt)
    }
    /** Separate readiness traffic from the explicitly armed external-event observation window. */
    fun beginEventWindow() { observationStart = receipts.size }
    fun hasPostedReceipt(): Boolean = receipts.drop(observationStart).any { it.origin == "POSTED_CALLBACK" }
    fun hasOwnedReceipt(key: String, postTime: Long): Boolean = receipts.any {
        it.origin == "POSTED_CALLBACK" && it.key == key && it.postTime == postTime
    }

    /** A package callback alone cannot identify a particular external notification/message. */
    fun describePackageReceipt(): Boolean {
        val receipt = receipts.drop(observationStart).firstOrNull { it.origin == "POSTED_CALLBACK" } ?: return false
        describe(receipt, "PACKAGE_CALLBACK_ONLY")
        return true
    }

    fun describeOwnedReceipt(key: String, postTime: Long): Boolean {
        val receipt = receipts.firstOrNull {
            it.origin == "POSTED_CALLBACK" && it.key == key && it.postTime == postTime
        } ?: return false
        describe(receipt, "EXACT_OWNED_PLATFORM_KEY_AND_VERSION")
        return true
    }

    private fun describe(receipt: Receipt, correlation: String) {
        selectedReceipt = receipt
        values["systemReceipt"] = "OBSERVED"
        values["eventCorrelation"] = correlation
        values["receiptLabel"] = label(receipt.original, "N")
        values["ingressLabel"] = label(receipt.ingress, "I")
        values["membershipLabel"] = label(receipt.membership, "M")
        values["epochLabel"] = label(receipt.epoch, "E")
        values["eventOrigin"] = receipt.origin
        values["eventKind"] = receipt.kind
        values["eventBaseline"] = receipt.baseline
        values["admissionReceiptCaptured"] = receipt.admissionCaptured.toString()
        // Capturing an admission ticket is not acceptance, goal-ready, or a successful claim.
        refreshPipeline()
    }

    private fun label(value: Any, prefix: String): String = labels.getOrPut(value) { prefix + (labels.size + 1) }

    fun refreshPipeline() {
        // Rebuild every derived field, including dynamic settlement rows, before ANY return.
        // Keep run/setup facts and the identity-label registry separate from selected proof.
        values.keys.removeAll { it.matches(Regex("settlement[0-9]+\\..*")) }
        values.putAll(pipelineDefaults)
        val receipt = selectedReceipt ?: return
        val matching = pipeline.filter { it.ingress === receipt.original }
        if (matching.isEmpty()) { values["pipelineCorrelation"] = "NO_OBSERVATION_FOR_ORIGINAL_RECEIPT"; return }
        val original = matching.first()
        val consistent = matching.all { it.selection === original.selection && it.demand === original.demand && it.identitiesMatch }
        values["pipelineCorrelation"] = if (consistent) "EXACT_ORIGINAL_RECEIPT_AND_WORK" else "IDENTITY_MISMATCH"
        if (!consistent) return
        values["selectionG"] = label(original.selection, "G")
        values["projectedDemandD"] = label(original.demand, "D")
        values["demandGoal"] = original.demandGoal
        values["pipelineStages"] = matching.joinToString(",") { it.stage }
        matching.forEach { event ->
            values["observedPermitState"] = event.permitState
            event.arm?.let { values["arm"] = label(it, "R") }
            event.ready?.let { values["ready"] = label(it, "V") }
            event.activation?.let { values["activationH"] = label(it, "H") }
            event.attempt?.let { values["attemptA"] = label(it, "A") }
            event.context?.let { values["contextC"] = label(it, "C") }
            event.carrier?.let { values["carrier"] = label(it, "S") }
        }
        val settlements = matching.filter { it.stage == "SETTLED" }
        values["settlementCount"] = settlements.size.toString()
        settlements.forEachIndexed { index, event ->
            val prefix = "settlement${index + 1}."
            values[prefix + "carrier"] = event.carrier?.let { label(it, "S") } ?: "MISSING"
            values[prefix + "commitment"] = event.commitment ?: "MISSING"
            values[prefix + "rejection"] = event.rejection ?: "NONE"
            values[prefix + "faultStage"] = event.faultStage ?: "NONE"
            values[prefix + "resultPresent"] = event.resultPresent.toString()
            values[prefix + "launchStatus"] = event.launchStatus ?: "NO_RESULT"
            values[prefix + "resultContextMatchesReady"] = event.resultContextMatches.toString()
            values[prefix + "requestedPackageMatches"] = event.requestedPackageMatches.toString()
            values[prefix + "foregroundMatches"] = event.foregroundMatches.toString()
        }
        if (settlements.isNotEmpty()) values["settlement"] = "ORIGINAL_SUBMISSION_RETURN_AND_LANE_SETTLEMENT_OBSERVED"
        if (consistent && settlements.any { it.commitment == "COMMITTED" }) values["claim"] = "COMMITTED_FROM_MATCHING_SETTLEMENT"
        else if (consistent && settlements.any { it.commitment == "NOT_COMMITTED" }) values["claim"] = "NOT_COMMITTED_FROM_MATCHING_SETTLEMENT"
        if (consistent && settlements.any { it.commitment == "COMMITTED" && it.resultContextMatches == true && it.requestedPackageMatches == true && it.foregroundMatches == true })
            values["foreground"] = "MATCHED_ORIGINAL_CONTEXT_AND_PACKAGE_IN_TYPED_RESULT"
    }

    fun fields(): Map<String, String> = values.toMap()
}
