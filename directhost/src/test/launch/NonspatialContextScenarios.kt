package com.code2hack.eyeprojector.directhost

import com.code2hack.eyeprojector.directhost.activation.snapshot.SnapshotWord
import com.code2hack.eyeprojector.protocol.*

/** Delegated #116 authority join. Native publication/FD primitives use the existing host doubles. */
fun nonspatialContextScenarios() {
    for (mode in listOf(ReceiverUsabilityMode.LOCAL_PANEL, ReceiverUsabilityMode.PROJECTED)) {
        val authority = SessionUsability(ProjectionContext("owner", "session"))
        try {
            val fact = ReceiverUsability("owner", ReceiverIncarnation("receiver", 1), 1, 4, mode)
            check(authority.publish(fact) && authority.prepareRegion())
            val original = authority.snapshot()
            val region = checkNotNull(original.regionId)
            val word = original.word
            val point = HeadPointerState(4, 1, false, mode == ReceiverUsabilityMode.PROJECTED, 0f, 0f, null, null)
            check(authority.acceptsContext(region, word, point, fact))
            check(!authority.accepts(region, word, point, fact))
            val available = point.copy(available = true, fit = Fit(0, 0, 480, 640), geometry = Geometry(480, 640))
            check(authority.accepts(region, word, available, fact))
            for (source in listOf(null, fact.copy(ownerEpoch = "other"),
                fact.copy(incarnation = ReceiverIncarnation("wrong", 1)),
                fact.copy(incarnation = ReceiverIncarnation("receiver", 0)),
                fact.copy(inputRevision = 3), fact.copy(mode = ReceiverUsabilityMode.NONE))) {
                check(!authority.acceptsContext(region, word, point, source))
            }
            check(!authority.acceptsContext("wrong-region", word, point, fact))
            check(!authority.acceptsContext(region, SnapshotWord.pack(0, SnapshotWord.mode(word)), point, fact))
            check(!authority.acceptsContext(region, SnapshotWord.pack(SnapshotWord.stamp(word) + 1, SnapshotWord.mode(word)), point, fact))
            check(!authority.acceptsContext(region, SnapshotWord.pack(SnapshotWord.stamp(word), SnapshotWord.RETIRED), point, fact))
            check(!authority.acceptsContext(region, word, point.copy(revision = 3), fact))
            check(!authority.acceptsContext(region, word, point.copy(projected = !point.projected), fact))
            val replacement = fact.copy(revision = 2, inputRevision = 5)
            check(authority.publish(replacement))
            check(!authority.acceptsContext(region, word, point, fact))
            val fresh = authority.snapshot()
            check(authority.acceptsContext(fresh.regionId!!, fresh.word, point.copy(revision = 5), replacement))
            check(authority.publish(replacement.copy(revision = 3, mode = ReceiverUsabilityMode.NONE)))
            val none = authority.snapshot()
            check(!authority.acceptsContext(none.regionId!!, none.word, point.copy(revision = 5), none.fact))
            authority.retire()
            check(!authority.acceptsContext(fresh.regionId!!, fresh.word, point.copy(revision = 5), replacement))
        } finally { authority.closeResources() }
        println("PASS actual SessionUsability $mode nonspatial context permits unavailable point; spatial path and wrong region/stamp/owner/incarnation/inputRevision/mode/point-context/NONE/retired paths reject")
    }
}

fun main() = nonspatialContextScenarios()
