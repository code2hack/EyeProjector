package com.code2hack.eyeprojector.directhost

/** Local Binder transport boundary only; actual Phone owner/merger/router execute. */
data class ProjectionContext(val ownerEpoch: String="",val projectionToken: String="") {
    val available get() = ownerEpoch.isNotEmpty() && projectionToken.isNotEmpty()
    companion object {
        const val OWNER_EXTRA="com.code2hack.eyeprojector.projection.OWNER"
        const val TOKEN_EXTRA="com.code2hack.eyeprojector.projection.TOKEN"
    }
}
interface IPhoneInputSession {
    fun active(): Boolean
    fun edge(routeId: Long,typedEdge: ByteArray,regionId: String,word: Long)
}
abstract class IPhoneModeOwner {
    abstract fun onSession(ownerEpoch: String,projectionToken: String,session: IPhoneInputSession)
    abstract fun decide(expectedOwnerEpoch: String,expectedProjectionToken: String,packageName: String?,operation: Int,x: Double,y: Double): ByteArray?
    abstract class Stub : IPhoneModeOwner()
}
