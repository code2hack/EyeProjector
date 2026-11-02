package com.code2hack.eyeprojector.directhost.anchor.execution

// Signatures of IAnchorRawControl.aidl only. These tests exercise callers, not Binder transport.
internal interface IAnchorRawControl {
    fun admitUserControl(): Int
    fun edge(routeId: Long, typedEdge: ByteArray, regionId: String, word: Long)
}
