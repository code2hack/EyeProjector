package com.code2hack.eyeprojector.directhost

/** The accepted backend owner and the actual media Session are distinct identities. */
data class ProjectionContext(val ownerEpoch: String = "", val projectionToken: String = "") {
    val available: Boolean get() = ownerEpoch.isNotEmpty() && projectionToken.isNotEmpty()
    companion object {
        const val OWNER_EXTRA = "com.code2hack.eyeprojector.projection.OWNER"
        const val TOKEN_EXTRA = "com.code2hack.eyeprojector.projection.TOKEN"
    }
}

/** Captured once under Bridge admission; invoking it never resolves a newer Session. */
internal interface CapturedProjection {
    fun phoneHidEdge(routeId: Long, edge: com.code2hack.eyeprojector.protocol.PhoneHidEdge)
    fun launchApp(requestId: Long, packageName: String): ProjectionDisplayState
    fun projectionDisplayState(): ProjectionDisplayState
}
