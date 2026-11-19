package com.code2hack.eyeprojector.directhost

/** Local Binder boundary for the focused activation host runner. */
interface IPhoneInputSession {
    fun asBinder(): android.os.IBinder?
    fun active(): Boolean
    fun wake(directive: ByteArray)
    fun edge(routeId: Long,typedEdge: ByteArray,regionId: String,word: Long)
}
