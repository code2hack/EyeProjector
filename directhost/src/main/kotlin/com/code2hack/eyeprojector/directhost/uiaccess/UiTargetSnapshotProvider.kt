package com.code2hack.eyeprojector.directhost.uiaccess

import com.code2hack.eyeprojector.directhost.ProjectionContext

/** The caller supplies observations from the captured original Session, not the latest default route. */
fun interface ManagedUiSourceProvider {
    fun current(expected: ProjectionContext): ManagedUiSource?
}

class UiTargetSnapshotProvider(
    private val source: ManagedUiSourceProvider,
    private val reader: () -> UiTargetReader?,
    private val uptime: () -> Long,
) {
    fun read(expected: ProjectionContext, selectedPackage: String, windowId: Int? = null): UiTargetSnapshot {
        val start = uptime()
        var request: UiTargetRequest? = null
        return try {
            val before = source.current(expected)?.takeIf { it.context == expected }
                ?: return UiTargetSnapshot(null, UiTargetReadStatus.SOURCE_UNAVAILABLE, start, uptime())
            request = UiTargetRequest(before, selectedPackage, windowId)
            val snapshot = reader()?.read(request)
                ?: return UiTargetSnapshot(request, UiTargetReadStatus.ACCESS_REQUIRED, start, uptime())
            // A display/scale/session replacement during traversal belongs to a different observation.
            if (source.current(expected) != before) return UiTargetSnapshot(request,
                UiTargetReadStatus.SOURCE_CHANGED, start, uptime())
            snapshot
        } catch (error: Exception) {
            UiTargetSnapshot(request, UiTargetReadStatus.FAILED, start, uptime(), failureClass = error.javaClass.name)
        }
    }
}
