package com.code2hack.eyeprojector.directhost.activation.snapshot

import com.code2hack.eyeprojector.directhost.PhoneSessionController
import com.code2hack.eyeprojector.directhost.activation.*

/** Snapshot/target availability is explicit here; actual Runtime and ingress are compiled whole. */
internal class PhoneHidSnapshotLease {
    fun capture(authority: HidInputAuthority) = PhoneHidCapture(PhoneHidDisposition.USABLE,
        HidWakeRequest(PhoneHidDisposition.USABLE, authority), PhoneSessionController.fixture::capture)
    fun clear(): HidInputAuthority? = null
    fun matches(authority: HidInputAuthority) = true
    fun install(authority: HidInputAuthority, source: PhoneHidSnapshotSource, prepared: NativeSnapshotReadLane.Prepared) = Unit
}
internal class PhoneHidSnapshotSource {
    fun prepare() = NativeSnapshotReadLane.Prepared()
    fun current() = true
}
internal class NativeSnapshotReadLane {
    class Prepared {
        val regionId = "fixture"
        fun close() = Unit
    }
}
