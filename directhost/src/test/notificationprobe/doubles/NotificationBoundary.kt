package com.code2hack.eyeprojector.directhost.notifications

import android.content.Context
import android.os.Looper
import java.io.Closeable

internal enum class NotificationAccessApproval { GRANTED, DENIED, UNKNOWN }
internal enum class NotificationListenerBaseline { DISCONNECTED, NO_CHOSEN_APPS, INITIALIZING, OBSERVED, UNAVAILABLE }
internal data class NotificationAccessStatus(val approval: NotificationAccessApproval, val connected: Boolean,
    val baseline: NotificationListenerBaseline, val chosenAvailable: Boolean, val launchFailed: Boolean = false)
internal enum class NotificationEventOrigin { POSTED_CALLBACK, REMOVED_CALLBACK }
internal enum class NotificationEventKind { NEW_RECORD, UPDATED_OR_REPOSTED, DUPLICATE_OR_REPLAY, REMOVED_CURRENT }
internal enum class NotificationBaselineState { READY, AWAITING, UNAVAILABLE }
internal open class NotificationRecord(open val packageName: String, open val key: String, open val postTime: Long) {
    override fun toString(): String = error("RAW_RECORD_MUST_NOT_BE_RENDERED")
}
internal class NotificationReceiptScope(val connection: Any = Any(), val membership: Any = Any())
internal class NotificationEventResult(val record: NotificationRecord, val origin: NotificationEventOrigin,
    val kind: NotificationEventKind, val baseline: NotificationBaselineState = NotificationBaselineState.READY, val epoch: Any = Any())
internal class NotificationIngressObservation(val event: NotificationEventResult, val scope: NotificationReceiptScope = NotificationReceiptScope(), val admission: Any? = Any())

/** Observation-only model: no classifier, selection, permit or launch algorithm is reproduced. */
internal class PhoneNotificationRuntime {
    var currentStatus = NotificationAccessStatus(NotificationAccessApproval.GRANTED, true, NotificationListenerBaseline.OBSERVED, true)
    private val status = linkedSetOf<() -> Unit>()
    private val events = linkedSetOf<(NotificationIngressObservation) -> Unit>()
    private val pipeline = linkedSetOf<(NotificationPipelineObservation) -> Unit>()
    var statusCloses = 0
    var eventCloses = 0
    var pipelineCloses = 0
    var eventDeliveries = 0
    var eventCloseFailure: RuntimeException? = null
    var statusCloseFailure: RuntimeException? = null
    var pipelineCloseFailure: RuntimeException? = null
    var onRefresh: (() -> Unit)? = null
    val eventRegistrations get() = events.size
    val statusRegistrations get() = status.size
    val pipelineRegistrations get() = pipeline.size
    fun status(): NotificationAccessStatus { Looper.requireMain(); return currentStatus }
    fun observeStatus(observer: () -> Unit): Closeable {
        Looper.requireMain(); status.add(observer)
        return Closeable { Looper.requireMain(); statusCloses++; status.remove(observer); statusCloseFailure?.let { throw it } }
    }
    fun observeEvents(observer: (NotificationIngressObservation) -> Unit): Closeable {
        Looper.requireMain(); events.add(observer)
        return Closeable { Looper.requireMain(); eventCloses++; events.remove(observer); eventCloseFailure?.let { throw it } }
    }
    fun observePipeline(observer: (NotificationPipelineObservation) -> Unit): Closeable {
        Looper.requireMain(); pipeline.add(observer)
        return Closeable { Looper.requireMain(); pipelineCloses++; pipeline.remove(observer); pipelineCloseFailure?.let { throw it } }
    }
    fun refresh() { Looper.requireMain(); status.toList().forEach { it() }; onRefresh?.invoke() }
    fun emit(observation: NotificationIngressObservation) {
        Looper.requireMain(); events.toList().forEach { eventDeliveries++; it(observation) }
    }
    fun emitPipeline(observation: NotificationPipelineObservation) { Looper.requireMain(); pipeline.toList().forEach { it(observation) } }
    companion object { fun get(context: Context): PhoneNotificationRuntime { context.runtimeReads++; return context.runtime } }
}
internal object AppNotificationPreferences {
    class Store(private val context: Context) {
        fun isEnabled(packageName: String): Boolean { Looper.requireMain(); return context.enabled[packageName] ?: true }
    }
    fun open(context: Context) = Store(context)
}
