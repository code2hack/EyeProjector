package com.code2hack.eyeprojector.directhost

import com.code2hack.eyeprojector.directhost.activation.snapshot.NativeSnapshotDescriptor
import com.code2hack.eyeprojector.directhost.activation.snapshot.NativeSnapshotWriter
import com.code2hack.eyeprojector.directhost.activation.snapshot.SnapshotWord
import com.code2hack.eyeprojector.protocol.HeadPointerState
import com.code2hack.eyeprojector.protocol.ReceiverUsability
import com.code2hack.eyeprojector.protocol.ReceiverUsabilityMode
import java.util.concurrent.atomic.AtomicReference

internal data class SessionUsabilitySnapshot(val context: ProjectionContext, val fact: ReceiverUsability? = null,
    val retired: Boolean = false, val regionId: String? = null, val word: Long = SnapshotWord.RETIRED.toLong()) {
    val usable: Boolean get() = !retired && fact?.mode in setOf(ReceiverUsabilityMode.LOCAL_PANEL, ReceiverUsabilityMode.PROJECTED)
}

/** Session-owned authority. Gate orders fact/native word; it never contains FD, stock or TLS work. */
internal class SessionUsability(private val context: ProjectionContext) {
    private val gate = Any()
    private val current = AtomicReference(SessionUsabilitySnapshot(context))
    private var region: NativeSnapshotWriter? = null // gate-owned; resources close outside gate.
    private val retiredRegions = mutableListOf<NativeSnapshotWriter>()
    fun snapshot(): SessionUsabilitySnapshot = current.get()

    /** Controlled setup only: allocation/load/map happens before entering the publication gate. */
    fun prepareRegion(): Boolean {
        if (synchronized(gate) { current.get().retired || region != null }) return false
        val fresh = NativeSnapshotWriter.create(context)
        val installed = synchronized(gate) {
            val old = current.get()
            if (old.retired || region != null) false else {
                old.fact?.let { fresh.publish(mode(it)) }
                region = fresh
                current.set(old.copy(regionId = fresh.regionId, word = fresh.publishedWord()))
                true
            }
        }
        if (!installed) fresh.close()
        return installed
    }
    fun descriptor(): NativeSnapshotDescriptor? {
        val original = synchronized(gate) { region?.takeUnless { current.get().retired } } ?: return null
        // Original writer has an independent FD/resource lock; NONE/RETIRED never waits here.
        return runCatching { original.descriptor() }.getOrNull()
    }
    fun publish(fact: ReceiverUsability): Boolean = synchronized(gate) {
        val old = current.get()
        if (old.retired || fact.ownerEpoch != context.ownerEpoch) return@synchronized false
        val before = old.fact
        if (before != null && (fact.incarnation.sequence < before.incarnation.sequence ||
            fact.incarnation.sequence == before.incarnation.sequence &&
            (fact.incarnation.id != before.incarnation.id || fact.revision <= before.revision))) return@synchronized false
        val changed = before == null || !sameInput(before, fact)
        val writer = region
        if (writer != null && changed && writer.publish(mode(fact)) == NativeSnapshotWriter.Publication.ROTATE_REQUIRED) {
            region = null; retiredRegions.add(writer)
        }
        current.set(old.copy(fact = fact, regionId = writer?.regionId ?: old.regionId,
            word = writer?.publishedWord() ?: old.word))
        true
    }
    fun needsRegion(): Boolean = synchronized(gate) { !current.get().retired && region == null }
    fun retire() = synchronized(gate) {
        val old = current.get()
        if (!old.retired) {
            region?.retire()
            current.set(old.copy(retired = true, word = region?.publishedWord() ?: SnapshotWord.RETIRED.toLong()))
        }
    }
    /** Only the native resource worker drains exhausted regions; publication never waits for FD work. */
    fun closeRetiredRegions() {
        val owned = synchronized(gate) { retiredRegions.toList().also { retiredRegions.clear() } }
        owned.forEach { it.close() }
    }
    fun closeResources() {
        val owned = synchronized(gate) {
            retire()
            (retiredRegions.toList() + listOfNotNull(region)).also { retiredRegions.clear(); region = null }
        }
        owned.forEach { it.close() }
    }
    /** Merger control lock -> this short gate. No publisher ever acquires the control lock. */
    fun accepts(regionId: String, word: Long, point: HeadPointerState, pointerSource: ReceiverUsability?): Boolean = synchronized(gate) {
        val now = current.get(); val fact = now.fact ?: return@synchronized false
        !now.retired && now.regionId == regionId && now.word == word && SnapshotWord.stamp(word) != 0L &&
            mode(fact) == SnapshotWord.mode(word) && mode(fact) in SnapshotWord.LOCAL_PANEL..SnapshotWord.PROJECTED &&
            pointerSource != null && sameInput(fact, pointerSource) && compatible(fact, point)
    }
    fun acceptsPointer(original: ReceiverUsability?, point: HeadPointerState): Boolean = synchronized(gate) {
        val now = current.get(); val fact = now.fact ?: return@synchronized false
        !now.retired && original != null && sameInput(fact, original) && (!point.available || compatible(fact, point))
    }
    private fun compatible(fact: ReceiverUsability, point: HeadPointerState) = point.available &&
        point.revision == fact.inputRevision && when (fact.mode) {
            ReceiverUsabilityMode.LOCAL_PANEL -> !point.projected
            ReceiverUsabilityMode.PROJECTED -> point.projected
            ReceiverUsabilityMode.NONE -> false
        }
    private fun sameInput(a: ReceiverUsability, b: ReceiverUsability) = a.ownerEpoch == b.ownerEpoch &&
        a.incarnation == b.incarnation && a.inputRevision == b.inputRevision && a.mode == b.mode
    private fun mode(fact: ReceiverUsability) = when (fact.mode) {
        ReceiverUsabilityMode.NONE -> SnapshotWord.NONE
        ReceiverUsabilityMode.LOCAL_PANEL -> SnapshotWord.LOCAL_PANEL
        ReceiverUsabilityMode.PROJECTED -> SnapshotWord.PROJECTED
    }
}
