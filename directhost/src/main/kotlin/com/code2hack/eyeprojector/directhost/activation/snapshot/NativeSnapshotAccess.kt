package com.code2hack.eyeprojector.directhost.activation.snapshot

import androidx.annotation.Keep
import java.nio.ByteBuffer

/** Unused source experiment. Both package-loaded processes initialize/register JNI outside ingress. */
@Keep
internal object NativeSnapshotAccess {
    init { System.loadLibrary("eyeprojector_snapshot") }
    fun prepare() = Unit
    @JvmStatic external fun initializeCell(buffer: ByteBuffer, offset: Int): Long
    @JvmStatic external fun bindCell(buffer: ByteBuffer, offset: Int): Long
    @JvmStatic external fun acquireCell(address: Long): Long
    @JvmStatic external fun releaseCell(address: Long, word: Long)
}
