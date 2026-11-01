package com.code2hack.eyeprojector.directhost

import com.code2hack.eyeprojector.directhost.anchor.preferences.AppAnchor
import com.code2hack.eyeprojector.directhost.anchor.preferences.AppAnchorCodec
import com.code2hack.eyeprojector.directhost.anchor.preferences.AppAnchorSetting
import com.code2hack.eyeprojector.directhost.anchor.preferences.AppAnchorStore
import com.code2hack.eyeprojector.directhost.anchor.preferences.AnchorSettingContext
import java.util.Base64

/** Frozen conditional181 API. Call only from the ledger's verified-retirement restore callback. */
internal class AnchorAdbPhoneAnchorAdapter(private val store: AppAnchorStore) {
    data class Restore(val snapshot: AnchorAdbPhoneAnchorLedger.Snapshot?, val applied: Boolean,
                       val superseded: Boolean, val committedRevision: Long?)
    fun snapshot() = snapshot(store.snapshot(PACKAGE_NAME))

    fun restore(expected: AnchorAdbPhoneAnchorLedger.Snapshot, prior: AnchorAdbPhoneAnchorLedger.Snapshot): Restore {
        val current = AppAnchorSetting(AnchorSettingContext(expected.instanceId, PACKAGE_NAME, expected.revision), decode(expected.encoded))
        val committed = store.compareAndSet(current, decode(prior.encoded)) ?: return Restore(null, false, false, null)
        val readback = store.snapshot(PACKAGE_NAME)
        val superseded = readback.context != committed
        return Restore(if (superseded) null else snapshot(readback), true, superseded, committed.revision)
    }

    private fun snapshot(value: AppAnchorSetting) = AnchorAdbPhoneAnchorLedger.Snapshot(value.context.instanceId,
        value.context.revision, value.anchor?.let { Base64.getEncoder().encodeToString(AppAnchorCodec.encode(it)) }, value.anchor?.mode?.name)
    private fun decode(value: String?): AppAnchor? = value?.let { AppAnchorCodec.decode(Base64.getDecoder().decode(it)) }
    companion object { const val PACKAGE_NAME = "com.code2hack.eyeprojector.hostprobe" }
}
