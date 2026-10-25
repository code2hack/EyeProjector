package com.code2hack.eyeprojector.directhost.activation.snapshot

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import java.util.concurrent.Executors
import rikka.shizuku.Shizuku

/** Explicit Android service and mapped-memory/JNI boundary. NO real FD, native, RO or Binder proof. */
object ProbeBoundary {
    lateinit var main: java.util.concurrent.ExecutorService
    var androidBinds = 0; var androidUnbinds = 0; var deliversService = true
    var clearCalls = 0; var installCalls = 0; var readsAfterClose = 0; var retireCalls = 0
    var descriptorFailure: Throwable? = null
    var clearFailure: Throwable? = null
    var clearFailureAtCall = 1
    var androidUnbindFailure: Throwable? = null
    var closeFailure: Throwable? = null
    var closeFailureAtCall = 1
    var endpoint: Endpoint? = null
    fun reset() {
        main = Executors.newSingleThreadExecutor { Thread(it, "fixture-main").apply { isDaemon = true } }
        androidBinds = 0; androidUnbinds = 0; deliversService = true
        clearCalls = 0; installCalls = 0; readsAfterClose = 0; retireCalls = 0
        descriptorFailure = null; clearFailure = null; clearFailureAtCall = 1; androidUnbindFailure = null
        closeFailure = null; closeFailureAtCall = 1; endpoint = null
    }
    fun connectNow(context: Context, connection: ServiceConnection) {
        if (deliversService) {
            val value = Endpoint(); endpoint = value
            connection.onServiceConnected(ComponentName(context, NativeSnapshotProbeService::class.java), value)
        }
    }
    fun connect(context: Context, connection: ServiceConnection) {
        if (deliversService) main.execute { connectNow(context, connection) }
    }
}
class NativeSnapshotProbeService
class NativeSnapshotProbeUserService
data class OriginalContext(val ownerEpoch: String)
data class Descriptor(val context: OriginalContext, val regionId: String, val endpoint: Endpoint)
interface INativeSnapshotProbe : IBinder {
    fun pid(): Int; fun uid(): Int; fun bitness(): Int
    fun descriptor(): Descriptor
    fun publishMode(mode: Int): Long
    fun retireRegion(): Long
    fun closeRegion()
    object Stub { fun asInterface(binder: IBinder) = binder as INativeSnapshotProbe }
}
class Endpoint : INativeSnapshotProbe {
    var word = 0L
    var closed = false
    var closeCalls = 0
    val descriptor = Descriptor(OriginalContext("original-probe-owner"), "original-region", this)
    override fun pid() = 200
    override fun uid() = if (Shizuku.bindCalls > 0) 2000 else 10479
    override fun bitness() = 64
    override fun descriptor(): Descriptor {
        ProbeBoundary.descriptorFailure?.let { throw it }
        return descriptor
    }
    override fun publishMode(mode: Int): Long {
        check(!closed)
        word = SnapshotWord.pack(SnapshotWord.stamp(word) + 1, mode); return word
    }
    override fun retireRegion(): Long {
        ProbeBoundary.retireCalls++
        word = SnapshotWord.pack(SnapshotWord.stamp(word), SnapshotWord.RETIRED); return word
    }
    override fun closeRegion() {
        closeCalls++
        if (closeCalls >= ProbeBoundary.closeFailureAtCall) ProbeBoundary.closeFailure?.let { throw it }
        closed = true
    }
}
class NativeSnapshotReadLane {
    private var descriptor: Descriptor? = null
    fun install(value: Descriptor, expected: OriginalContext) {
        check(value.context === expected && value.regionId == "original-region")
        ProbeBoundary.installCalls++; descriptor = value
    }
    fun read(): Long {
        val value = descriptor ?: return SnapshotWord.RETIRED.toLong()
        if (value.endpoint.closed) ProbeBoundary.readsAfterClose++
        return value.endpoint.word
    }
    fun clear() {
        ProbeBoundary.clearCalls++
        if (ProbeBoundary.clearCalls >= ProbeBoundary.clearFailureAtCall) ProbeBoundary.clearFailure?.let { throw it }
        descriptor = null
    }
}
