package com.code2hack.eyeprojector.directhost.anchor.execution

import com.code2hack.eyeprojector.directhost.anchor.preferences.AppAnchorStore
import com.code2hack.eyeprojector.directhost.uiaccess.*
import com.code2hack.eyeprojector.protocol.*

/** Only the recorded #122 provider ABI and app FIFO entry boundary, not a production bridge. */
internal interface AnchorOriginalRoute {
    val transactionId: Long
    val intent: FunctionIntent
    fun source(): ManagedUiSource?
    fun send(message: AnchorMessage)
}
internal class CapturedAnchorRequest(val original: AnchorOriginalRoute, val source: ManagedUiSource,
    val snapshot: UiTargetSnapshot)
internal fun interface AnchorRegistrationInstaller { fun install(runtime: AnchorAppRuntime) }
internal class AnchorAppRuntime(val store: AppAnchorStore, val reader: () -> UiTargetReader?) {
    lateinit var begin: (CapturedAnchorRequest) -> Unit
    lateinit var message: (AnchorMessage, AnchorOriginalRoute) -> Unit
    fun installRegistration(begin: (CapturedAnchorRequest) -> Unit, message: (AnchorMessage, AnchorOriginalRoute) -> Unit) {
        this.begin = begin; this.message = message
    }
    fun read(original: AnchorOriginalRoute, selectedPackage: String, windowId: Int? = null): UiTargetSnapshot {
        val expected = original.source()?.context ?: return UiTargetSnapshot(null, UiTargetReadStatus.SOURCE_UNAVAILABLE, 1, 1)
        return UiTargetSnapshotProvider({ context -> original.source()?.takeIf { it.context == context } }, reader) { 1L }
            .read(expected, selectedPackage, windowId)
    }
}
