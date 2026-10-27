package com.code2hack.eyeprojector.directrg

import com.code2hack.eyeprojector.protocol.*
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties
import java.util.UUID

internal data class ReceiverLaunchScope(val id: String, val trigger: String, val taskId: Int)
internal data class ReceiverTrigger(val scope: ReceiverLaunchScope, val admission: String, val activation: String? = null,
    val canceled: Boolean = false, val acknowledged: Boolean = false)
internal data class ReceiverTaskBinding(val scope: ReceiverLaunchScope, val activation: String?)
internal data class ReceiverFeatureState(val triggers: Map<String, ReceiverTrigger> = emptyMap(),
    val pendingCancel: Set<String> = emptySet(), val canceled: Set<String> = emptySet(),
    val currentTrigger: String? = null, val sequence: Long = 0, val tasks: Map<String, ReceiverTaskBinding> = emptyMap())

/** Separate from credentials. Local trigger/cancellation is saved before any corresponding transport effect. */
internal class ReceiverActivationStore(private val file: File) {
    @Volatile var state = read(); private set
    @Volatile var incarnation: ReceiverIncarnation? = null; private set
    @Volatile var directive: ActivationDirective? = null; private set
    @Volatile var presentation: PresentationReport? = null; private set

    @Synchronized fun startIncarnation(): ReceiverIncarnation {
        incarnation?.let { return it }
        save(state.copy(sequence = state.sequence + 1))
        return ReceiverIncarnation(UUID.randomUUID().toString(), state.sequence).also { incarnation = it }
    }
    @Synchronized fun registerTask(binding: ReceiverTaskBinding) {
        if (binding.scope.id in state.tasks) return
        save(state.copy(tasks = state.tasks + (binding.scope.id to binding)))
    }
    @Synchronized fun launch(scope: ReceiverLaunchScope, admission: String) {
        if (scope.trigger in state.triggers) return
        save(state.copy(currentTrigger = scope.trigger,
            triggers = state.triggers + (scope.trigger to ReceiverTrigger(scope, admission))))
    }
    fun cancellationTargetsCurrent(scope: ReceiverLaunchScope?, activation: String?): Boolean {
        val current = directive?.activationId
        val mapped = scope?.let { state.triggers[it.trigger]?.activation }
        return if (current != null) current == activation || current == mapped
            else scope?.trigger != null && scope.trigger == state.currentTrigger
    }
    @Synchronized fun cancel(scope: ReceiverLaunchScope?, activation: String?, admission: String = "") {
        val current = cancellationTargetsCurrent(scope, activation)
        val old = scope?.takeIf { it.trigger.isNotEmpty() }?.let { state.triggers[it.trigger] ?: ReceiverTrigger(it, admission) }
        val ids = listOfNotNull(old?.activation, activation).toSet()
        val triggers = if (old != null) state.triggers + (scope.trigger to old.copy(canceled = true)) else state.triggers
        save(state.copy(triggers = triggers, pendingCancel = state.pendingCancel + ids, canceled = state.canceled + ids,
            currentTrigger = state.currentTrigger?.takeUnless { it == scope?.trigger }))
        if (current) presentation = null
    }
    @Synchronized fun accept(reply: ActivationReply?) {
        if (reply == null) return
        var next = state
        reply.trigger?.let { receipt ->
            val old = next.triggers[receipt.triggerId]
            if (old != null) next = next.copy(triggers = next.triggers + (receipt.triggerId to old.copy(
                activation = old.activation ?: receipt.activationId,
                canceled = old.canceled || receipt.disposition == TriggerDisposition.CANCELED,
                acknowledged = receipt.disposition == TriggerDisposition.CANCELED)))
        }
        reply.cancelAcknowledged?.let { next = next.copy(pendingCancel = next.pendingCancel - it) }
        if (next != state) save(next)
        val d = reply.directive
        directive = d?.takeIf { it.desired && it.activationId !in state.canceled }
        if (directive?.activationId != presentation?.activationId || directive?.ownerEpoch != presentation?.ownerEpoch ||
            directive?.wakeRevision != presentation?.wakeRevision) presentation = null
    }
    @Synchronized fun report(original: ReceiverDemand, revision: Long, mode: ReceiverUsabilityMode, detail: String) {
        if (demand() != original || original.activationId in state.canceled) return
        presentation = PresentationReport(original.activationId, original.ownerEpoch, original.wakeRevision,
            original.incarnation, revision, mode, detail)
    }
    fun pendingTriggers() = state.triggers.values.filter { it.canceled && !it.acknowledged }
    fun intent(): ActivationIntent? = state.currentTrigger?.let { state.triggers[it] }?.takeIf { !it.canceled }?.let { ActivationIntent(it.scope.trigger) }
    fun currentScope(): ReceiverLaunchScope? = state.currentTrigger?.let { state.triggers[it]?.scope }
    @Synchronized fun demand(): ReceiverDemand? = directive?.let { d -> incarnation?.let { ReceiverDemand(d.activationId,d.ownerEpoch,d.wakeRevision,it,d.goal) } }
    fun permits(d: ActivationDirective) = directive == d && d.desired && d.activationId !in state.canceled

    private fun read(): ReceiverFeatureState {
        if (!file.exists()) return ReceiverFeatureState()
        val p = Properties().apply { file.inputStream().use(::load) }
        val triggers = (0 until p.getProperty("triggers", "0").toInt()).associate { i ->
            val t = p.getProperty("t.$i.id")
            t to ReceiverTrigger(ReceiverLaunchScope(p.getProperty("t.$i.scope"), t, p.getProperty("t.$i.task").toInt()),
                p.getProperty("t.$i.a"), p.getProperty("t.$i.h"), p.getProperty("t.$i.cancel").toBoolean(), p.getProperty("t.$i.ack").toBoolean()) }
        fun set(name: String) = (0 until p.getProperty(name,"0").toInt()).map { p.getProperty("$name.$it") }.toSet()
        val tasks = (0 until p.getProperty("tasks", "0").toInt()).associate { i ->
            val scope = ReceiverLaunchScope(p.getProperty("task.$i.id"), p.getProperty("task.$i.t", ""), p.getProperty("task.$i.task").toInt())
            scope.id to ReceiverTaskBinding(scope, p.getProperty("task.$i.h"))
        }
        return ReceiverFeatureState(triggers, set("pending"), set("canceled"), p.getProperty("current"), p.getProperty("sequence", "0").toLong(), tasks)
    }
    private fun save(value: ReceiverFeatureState) {
        val p = Properties()
        p.setProperty("sequence", value.sequence.toString()); value.currentTrigger?.let { p.setProperty("current",it) }
        p.setProperty("triggers",value.triggers.size.toString())
        value.triggers.values.forEachIndexed { i,t -> p.setProperty("t.$i.id",t.scope.trigger); p.setProperty("t.$i.scope",t.scope.id)
            p.setProperty("t.$i.task",t.scope.taskId.toString()); p.setProperty("t.$i.a",t.admission); t.activation?.let { p.setProperty("t.$i.h",it) }
            p.setProperty("t.$i.cancel",t.canceled.toString()); p.setProperty("t.$i.ack",t.acknowledged.toString()) }
        p.setProperty("tasks",value.tasks.size.toString())
        value.tasks.values.forEachIndexed { i,t ->
            p.setProperty("task.$i.id",t.scope.id); p.setProperty("task.$i.t",t.scope.trigger); p.setProperty("task.$i.task",t.scope.taskId.toString())
            t.activation?.let { p.setProperty("task.$i.h",it) }
        }
        fun set(name: String, values: Set<String>) { p.setProperty(name, values.size.toString()); values.forEachIndexed { i,v -> p.setProperty("$name.$i",v) } }
        set("pending",value.pendingCancel); set("canceled",value.canceled)
        file.parentFile?.mkdirs()
        val temp = Files.createTempFile(file.parentFile.toPath(), "receiver-activation-", ".tmp")
        try { FileOutputStream(temp.toFile()).use { p.store(it,null); it.fd.sync() }
            Files.move(temp,file.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temp) }
        state = value
    }
}
