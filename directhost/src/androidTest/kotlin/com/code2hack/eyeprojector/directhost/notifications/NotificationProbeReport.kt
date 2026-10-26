package com.code2hack.eyeprojector.directhost.notifications

import java.util.IdentityHashMap

/** Diagnostic journal only. Opaque platform metadata is compared in memory, never rendered. */
internal class NotificationProbeReport(val runId: String, val packageName: String) {
    internal data class Receipt(
        val ingress: Any, val membership: Any, val epoch: Any, val original: Any,
        val packageName: String, val key: String, val postTime: Long,
        val origin: String, val kind: String, val baseline: String,
        val admissionCaptured: Boolean,
        val admissionTicket: Any? = null,
        val diagnostic: NotificationObservationTrace? = null,
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
    private var helperCallbackEntries = 0L
    private var uncorrelatedHelperFaults = 0L
    private var uncorrelatedHelperFault = NotificationDiagnosticFaultFamily.NONE
    private val labels = IdentityHashMap<Any, String>()
    private val pipelineDefaults = linkedMapOf(
        "pipelineCorrelation" to "NO_MATCHED_ORIGINAL_RECEIPT", "selectionG" to "UNOBSERVED",
        "projectedDemandD" to "UNOBSERVED", "demandGoal" to "UNOBSERVED", "arm" to "UNOBSERVED",
        "ready" to "UNOBSERVED", "activationH" to "UNOBSERVED", "attemptA" to "UNOBSERVED",
        "contextC" to "UNOBSERVED", "carrier" to "UNOBSERVED", "observedPermitState" to "UNOBSERVED",
        "pipelineStages" to "NONE", "settlementCount" to "0", "claim" to "UNOBSERVED",
        "settlement" to "UNOBSERVED", "foreground" to "UNOBSERVED",
    )
    private val traceDefaults = linkedMapOf(
        "traceCorrelation" to "NO_MATCHED_ORIGINAL_RECEIPT", "admissionTicketLabel" to "UNOBSERVED",
        "acceptDisposition" to "UNOBSERVED", "prepareDisposition" to "UNOBSERVED", "installDisposition" to "UNOBSERVED",
        "prepareFaultFamily" to "UNOBSERVED", "acceptFaultFamily" to "UNOBSERVED",
        "admissionInstalledG" to "UNOBSERVED", "admissionReturnedHandle" to "UNOBSERVED",
        "producerCaptured" to "UNOBSERVED", "enqueueAttempted" to "UNOBSERVED", "enqueueReturned" to "UNOBSERVED",
        "enqueueFaults" to "UNOBSERVED", "producerCallbackEntered" to "UNOBSERVED", "producerCallbackReturned" to "UNOBSERVED",
        "producerCallbackFaults" to "UNOBSERVED", "inactiveSkipped" to "UNOBSERVED",
        "helperEntered" to "UNOBSERVED", "helperAppended" to "UNOBSERVED", "helperFaults" to "UNOBSERVED",
        "enqueueFaultFamily" to "UNOBSERVED", "producerCallbackFaultFamily" to "UNOBSERVED", "helperFaultFamily" to "UNOBSERVED",
        "helperFaultPoint" to "UNOBSERVED",
        "traceReportFaultFamily" to "UNOBSERVED",
        "trace.packageRevisionUnit" to "PROCESS_LOCAL_POLICY_INTENT_REVISION",
        "trace.originalPackagePolicyRevision" to "UNOBSERVED",
        "trace.prepareChosen" to "UNOBSERVED", "trace.prepareOn" to "UNOBSERVED",
        "trace.comparedPackagePolicyRevision" to "UNOBSERVED",
        "trace.latestAssignment.revision" to "UNKNOWN", "trace.latestAssignment.writer" to "UNKNOWN",
        "trace.latestAssignment.changeKind" to "UNKNOWN", "trace.latestAssignment.desired" to "UNKNOWN",
        "trace.latestAssignment.changeRevision" to "UNKNOWN",
        "producerCallbackObservation" to "UNOBSERVED", "helperAppendObservation" to "UNOBSERVED",
        "enqueueSemantics" to "POST_UNIT_RETURN_NOT_QUEUE_ACCEPTANCE",
    ).apply { NotificationAdmissionCheck.entries.forEach { put("traceCheck.${it.name}", "UNOBSERVED") } }
    private val values = linkedMapOf(
        "runId" to runId, "postingPackage" to packageName,
        "semanticNovelty" to "UNKNOWN", "baselineCompleteness" to "UNKNOWN",
        "nativeOriginContinuity" to "UNKNOWN", "pipelineCorrelation" to "NO_MATCHED_ORIGINAL_RECEIPT",
        "selectionG" to "UNOBSERVED", "projectedDemandD" to "UNOBSERVED",
        "ready" to "UNOBSERVED", "carrier" to "UNOBSERVED",
        "claim" to "UNOBSERVED", "settlement" to "UNOBSERVED",
        "foreground" to "UNOBSERVED", "globalHome" to "UNVERIFIED",
        "systemReceipt" to "UNOBSERVED", "postCall" to "NOT_ATTEMPTED",
    ).apply { putAll(pipelineDefaults); putAll(traceDefaults) }

    fun put(name: String, value: String) { values[name] = value }
    fun pipeline(event: Pipeline) { pipeline.add(event) }
    /** Run-scoped only: entry precedes the first ingress getter and cannot yet identify N. */
    fun helperCallbackEntry() { helperCallbackEntries++ }
    fun uncorrelatedHelperFault(family: NotificationDiagnosticFaultFamily) {
        uncorrelatedHelperFaults++
        uncorrelatedHelperFault = family
    }
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

    /** Called on main outside product locks. A trace fact never substitutes for a pipeline row. */
    private fun refreshTrace(receipt: Receipt) {
        receipt.admissionTicket?.let { values["admissionTicketLabel"] = label(it, "T") }
        val box = receipt.diagnostic
        if (box == null) { values["traceCorrelation"] = "NO_TRACE_BOX"; return }
        val snapshot = try { box.snapshot(receipt.original) } catch (failure: Throwable) {
            values["traceCorrelation"] = "SNAPSHOT_FAULT"
            // This passive fault cannot prevent independent pipeline/result derivation.
            try { values["traceSnapshotFaultFamily"] = notificationDiagnosticFault(failure).name } catch (_: Throwable) { }
            return
        }
        if (snapshot == null) { values["traceCorrelation"] = "ORIGINAL_IDENTITY_MISMATCH"; return }
        values["traceCorrelation"] = "EXACT_ORIGINAL_RECEIPT"
        values["acceptDisposition"] = snapshot.disposition.name
        values["prepareDisposition"] = snapshot.prepare.name
        values["installDisposition"] = snapshot.install.name
        values["prepareFaultFamily"] = snapshot.prepareFault.name
        values["acceptFaultFamily"] = snapshot.acceptFault.name
        NotificationAdmissionCheck.entries.forEachIndexed { index, check ->
            snapshot.checks.getOrNull(index)?.let { values["traceCheck.${check.name}"] = it.name }
        }
        if (snapshot.originalPackageRevisionObserved)
            values["trace.originalPackagePolicyRevision"] = snapshot.originalPackageRevision.toString()
        if (snapshot.prepareValuesObserved) {
            values["trace.prepareChosen"] = snapshot.preparedChosen.toString()
            values["trace.prepareOn"] = snapshot.preparedEnabled.toString()
        }
        if (snapshot.comparedPackageRevisionObserved)
            values["trace.comparedPackagePolicyRevision"] = snapshot.comparedPackageRevision.toString()
        // Diagnostic consistency only. Never use a stale/unbound latest record as writer proof.
        val assignment = snapshot.latestAssignment
        if (snapshot.comparedPackageRevisionObserved && assignment != null &&
            assignment.assignedRevision == snapshot.comparedPackageRevision) {
            values["trace.latestAssignment.revision"] = assignment.assignedRevision.toString()
            values["trace.latestAssignment.writer"] = assignment.writerKind.name
            values["trace.latestAssignment.changeKind"] = assignment.changeKind.name
            values["trace.latestAssignment.desired"] = assignment.desired.toString()
            values["trace.latestAssignment.changeRevision"] = assignment.changeRevision.toString()
        }
        snapshot.installedSelection?.let { values["admissionInstalledG"] = label(it, "G") }
        snapshot.returnedHandle?.let { values["admissionReturnedHandle"] = label(it, "DH") }
        val delivery = snapshot.delivery
        values["producerCaptured"] = delivery.captured.toString()
        values["enqueueAttempted"] = delivery.enqueueAttempted.toString()
        values["enqueueReturned"] = delivery.enqueueReturned.toString()
        values["enqueueFaults"] = delivery.enqueueFaults.toString()
        values["producerCallbackEntered"] = delivery.callbackEntered.toString()
        values["producerCallbackReturned"] = delivery.callbackReturned.toString()
        values["producerCallbackFaults"] = delivery.callbackFaults.toString()
        values["inactiveSkipped"] = delivery.inactiveSkipped.toString()
        values["helperEntered"] = delivery.helperEntered.toString()
        values["helperAppended"] = delivery.helperAppended.toString()
        values["helperFaults"] = delivery.helperFaults.toString()
        values["enqueueFaultFamily"] = delivery.enqueueFault.name
        values["producerCallbackFaultFamily"] = delivery.callbackFault.name
        values["helperFaultFamily"] = delivery.helperFault.name
        values["helperFaultPoint"] = delivery.helperFaultPoint.name
        values["producerCallbackObservation"] = when {
            delivery.callbackEntered == 0L -> "NO_CALLBACK_OBSERVED"
            delivery.callbackFaults != 0L -> "ENTERED_WITH_FAULT"
            else -> "ENTERED"
        }
        values["helperAppendObservation"] = when {
            delivery.helperFaults != 0L && delivery.helperAppended != 0L -> "APPEND_AND_FAULT_OBSERVED"
            delivery.helperFaults != 0L && delivery.helperFaultPoint == NotificationHelperFaultPoint.APPEND_UNCONFIRMED -> "APPEND_UNCONFIRMED"
            delivery.helperFaults != 0L && delivery.helperFaultPoint == NotificationHelperFaultPoint.BEFORE_APPEND -> "BEFORE_APPEND_FAULT_OBSERVED"
            delivery.helperFaults != 0L -> "FAULT_POINT_UNOBSERVED"
            delivery.helperAppended != 0L -> "APPENDED"
            delivery.helperEntered != 0L -> "ENTERED_WITHOUT_APPEND"
            else -> "NO_HELPER_ENTRY_OBSERVED"
        }
    }

    fun refreshPipeline() {
        // Rebuild every derived field, including dynamic settlement rows, before ANY return.
        // Keep run/setup facts and the identity-label registry separate from selected proof.
        values.keys.removeAll { it.matches(Regex("settlement[0-9]+\\..*")) }
        values.remove("traceSnapshotFaultFamily")
        values.putAll(pipelineDefaults)
        values.putAll(traceDefaults)
        val receipt = selectedReceipt ?: return
        try { refreshTrace(receipt) } catch (failure: Throwable) {
            values.remove("traceSnapshotFaultFamily")
            values.putAll(traceDefaults)
            values["traceCorrelation"] = "TRACE_REPORT_FAULT"
            try { values["traceReportFaultFamily"] = notificationDiagnosticFault(failure).name } catch (_: Throwable) { }
        }
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

    fun fields(): Map<String, String> = values.toMap() + mapOf(
        "helperCallbackEntryCountRun" to helperCallbackEntries.toString(),
        "helperCallbackEntryScope" to "ALL_REGISTERED_CALLBACKS_NOT_ORIGINAL_RECEIPT_PROOF",
        "uncorrelatedHelperFaultCountRun" to uncorrelatedHelperFaults.toString(),
        "uncorrelatedHelperFaultFamilyRun" to uncorrelatedHelperFault.name,
    )
}
