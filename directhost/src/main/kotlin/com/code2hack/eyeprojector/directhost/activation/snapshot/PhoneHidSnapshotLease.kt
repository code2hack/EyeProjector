package com.code2hack.eyeprojector.directhost.activation.snapshot

import com.code2hack.eyeprojector.directhost.ProjectionContext
import com.code2hack.eyeprojector.directhost.activation.*
import com.code2hack.eyeprojector.directhost.input.PhoneHidRoute
import com.code2hack.eyeprojector.protocol.PhoneHidEdge

/** Original proxy/binding/context offer. Preparing may block and belongs only on a setup worker. */
internal interface PhoneHidSnapshotSource {
    val context: ProjectionContext
    fun current(): Boolean
    fun prepare(): NativeSnapshotReadLane.Prepared?
    fun route(down: PhoneHidEdge, regionId: String, word: Long): PhoneHidRoute?
}

/** Installed/read/cleared solely on the actual key lane, outside fresh-DOWN setup work. */
internal class PhoneHidSnapshotLease {
    private val reader = NativeSnapshotReadLane()
    private var source: PhoneHidSnapshotSource? = null
    private var authority: HidInputAuthority? = null

    fun install(original: HidInputAuthority, offer: PhoneHidSnapshotSource, prepared: NativeSnapshotReadLane.Prepared) {
        reader.installPrepared(prepared)
        authority = original; source = offer
    }

    fun matches(original: HidInputAuthority): Boolean = authority?.let {
        it.handle?.id == original.handle?.id && it.context == original.context && it.attemptId == original.attemptId &&
            it.producerVersion == original.producerVersion && it.regionGeneration == original.regionGeneration
    } == true && source?.current() == true

    /** No setup, map/unmap, Binder or effects; exactly one acquire when locally qualified. */
    fun capture(original: HidInputAuthority): PhoneHidCapture {
        val offer = source
        val region = reader.regionId
        if (!original.context.available || offer == null || region == null || !matches(original))
            return unknown(original, HidSetupReason.MISMATCH)
        val word = reader.read()
        if (SnapshotWord.stamp(word) == 0L || SnapshotWord.mode(word) == SnapshotWord.RETIRED)
            return unknown(original, if (SnapshotWord.mode(word) == SnapshotWord.RETIRED) HidSetupReason.RETIRED else HidSetupReason.UNESTABLISHED)
        val disposition = if (SnapshotWord.mode(word) == SnapshotWord.NONE) PhoneHidDisposition.NONE else PhoneHidDisposition.USABLE
        val observation = HidWakeRequest(disposition, original, region, word)
        // This closure captures only the original offer/word/region, not reader/address/mapping.
        return PhoneHidCapture(disposition, observation, if (disposition == PhoneHidDisposition.USABLE)
            { down -> offer.route(down, region, word) } else null)
    }

    fun clear(): HidInputAuthority? {
        val old = authority
        authority = null; source = null
        reader.clear()
        return old
    }

    companion object {
        fun unknown(original: HidInputAuthority, reason: HidSetupReason) =
            PhoneHidCapture(PhoneHidDisposition.UNKNOWN, HidWakeRequest(PhoneHidDisposition.UNKNOWN, original, setupReason = reason))
    }
}
