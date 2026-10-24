package com.code2hack.eyeprojector.directhost.activation

import com.code2hack.eyeprojector.protocol.ReceiverIncarnation
import com.code2hack.eyeprojector.protocol.ActivationGoal
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties

internal data class TriggerBinding(val activation: String?, val admission: String, val canceled: Boolean = false)
internal data class FeatureAttempt(val activation: String, val baseline: String)
internal data class DesiredActivation(val id: String, val desired: Boolean = true, val wakeRevision: Long = 1,
    val attempt: String? = null, val ownerEpoch: String = "",
    val goal: ActivationGoal = ActivationGoal.USABLE, val demandId: String? = null)
internal data class ActivationJournal(
    val current: DesiredActivation? = null,
    val triggers: Map<String, TriggerBinding> = emptyMap(),
    val attempts: Map<String, FeatureAttempt> = emptyMap(),
    val canceled: Set<String> = emptySet(),
    val incarnation: ReceiverIncarnation? = null,
    val aliases: Map<String, String> = emptyMap(),
)

/** Feature intent precedes core ledger admission. No credentials, Ready target or projection token is stored. */
internal class ActivationStore(private val file: File) {
    fun read(): ActivationJournal {
        if (!file.exists()) return ActivationJournal()
        val p = Properties().apply { file.inputStream().use(::load) }
        val current = p.getProperty("current")?.let { DesiredActivation(it, p.getProperty("desired").toBoolean(),
            p.getProperty("wake").toLong(), p.getProperty("attempt"), p.getProperty("owner", ""),
            ActivationGoal.valueOf(p.getProperty("goal", ActivationGoal.USABLE.name)), p.getProperty("demand")) }
        val triggers = (0 until p.getProperty("triggers", "0").toInt()).associate { i ->
            p.getProperty("t.$i.id") to TriggerBinding(p.getProperty("t.$i.h"), p.getProperty("t.$i.a", ""), p.getProperty("t.$i.cancel").toBoolean()) }
        val attempts = (0 until p.getProperty("attempts", "0").toInt()).associate { i ->
            p.getProperty("a.$i.id") to FeatureAttempt(p.getProperty("a.$i.h"), p.getProperty("a.$i.base")) }
        val canceled = (0 until p.getProperty("canceled", "0").toInt()).map { p.getProperty("c.$it") }.toSet()
        val incarnation = p.getProperty("receiver")?.let { ReceiverIncarnation(it, p.getProperty("receiverSequence").toLong()) }
        val aliases = (0 until p.getProperty("aliases", "0").toInt()).associate { i -> p.getProperty("alias.$i.id") to p.getProperty("alias.$i.h") }
        return ActivationJournal(current, triggers, attempts, canceled, incarnation, aliases)
    }
    fun write(value: ActivationJournal) {
        val p = Properties()
        value.current?.let { c -> p.setProperty("current", c.id); p.setProperty("desired", c.desired.toString())
            p.setProperty("wake", c.wakeRevision.toString()); c.attempt?.let { p.setProperty("attempt", it) }; p.setProperty("owner", c.ownerEpoch); p.setProperty("goal", c.goal.name); c.demandId?.let { p.setProperty("demand", it) } }
        p.setProperty("triggers", value.triggers.size.toString())
        value.triggers.entries.forEachIndexed { i, (id,t) -> p.setProperty("t.$i.id",id); t.activation?.let { p.setProperty("t.$i.h",it) }
            p.setProperty("t.$i.a",t.admission); p.setProperty("t.$i.cancel",t.canceled.toString()) }
        p.setProperty("attempts", value.attempts.size.toString())
        value.attempts.entries.forEachIndexed { i,(id,a) -> p.setProperty("a.$i.id",id); p.setProperty("a.$i.h",a.activation); p.setProperty("a.$i.base",a.baseline) }
        p.setProperty("canceled",value.canceled.size.toString()); value.canceled.forEachIndexed { i,id -> p.setProperty("c.$i",id) }
        p.setProperty("aliases",value.aliases.size.toString())
        value.aliases.entries.forEachIndexed { i,(id,h) -> p.setProperty("alias.$i.id",id); p.setProperty("alias.$i.h",h) }
        value.incarnation?.let { p.setProperty("receiver",it.id); p.setProperty("receiverSequence",it.sequence.toString()) }
        file.parentFile?.mkdirs()
        val temporary = Files.createTempFile(file.parentFile.toPath(), "activation-", ".tmp")
        try {
            FileOutputStream(temporary.toFile()).use { p.store(it,null); it.fd.sync() }
            Files.move(temporary,file.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temporary) }
    }
}
