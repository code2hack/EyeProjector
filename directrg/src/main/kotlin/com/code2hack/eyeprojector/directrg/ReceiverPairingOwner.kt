package com.code2hack.eyeprojector.directrg

import android.content.Context
import com.code2hack.eyeprojector.protocol.*
import java.io.File
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean

/** One app/service pairing listener, shared with the visible panel including after Forget. */
internal class ReceiverPairingOwner private constructor(context: Context) {
    private val observers = CopyOnWriteArraySet<() -> Unit>()
    private val started = AtomicBoolean()
    val pairing = LanPairing(PeerStore(File(context.filesDir, "session")), PeerRole.GLASSES, { observers.forEach { it() } })
    fun start() { if (started.compareAndSet(false, true)) pairing.start() }
    fun observe(observer: () -> Unit) { observers.add(observer) }
    fun remove(observer: () -> Unit) { observers.remove(observer) }
    fun close() { pairing.close(); synchronized(Companion) { if (instance === this) instance = null } }
    companion object {
        @Volatile private var instance: ReceiverPairingOwner? = null
        fun get(context: Context) = instance ?: synchronized(this) {
            instance ?: ReceiverPairingOwner(context.applicationContext).also { instance = it }
        }
    }
}
