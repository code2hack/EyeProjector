package rikka.shizuku

import android.content.ComponentName
import android.content.ServiceConnection
import com.code2hack.eyeprojector.directhost.activation.snapshot.ProbeBoundary
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ConcurrentHashMap

/** Explicit SDK boundary, not the real SDK/provider/Binder implementation.
 * Mirrors worker->main sticky dispatch and non-atomic ready-sample/holder insertion.
 * Raw Binder and callback-ready state are intentionally independent.
 */
object Shizuku {
    fun interface OnBinderReceivedListener { fun onBinderReceived() }
    class UserServiceArgs(val component: ComponentName) {
        var tagValue = ""; var daemonValue = true; var suffix = ""; var versionValue = 0
        fun tag(value: String) = apply { tagValue = value }
        fun processNameSuffix(value: String) = apply { suffix = value }
        fun daemon(value: Boolean) = apply { daemonValue = value }
        fun version(value: Int) = apply { versionValue = value }
    }
    @Volatile var rawBinder = false
    @Volatile var attached = false
    @Volatile var metadataReady = false
    @Volatile var permission = 0
    var permissionFailure: Throwable? = null
    var afterStickySample: (() -> Unit)? = null
    var afterRegistration: (() -> Unit)? = null
    var beforeBind: (() -> Unit)? = null
    var afterBindSideEffects: (() -> Unit)? = null
    var synchronousConnection = false
    var remoteRemoveFailure: Throwable? = null
    var localDetachFailure: Throwable? = null
    var listenerRemoveFailure: Throwable? = null
    var registered = CountDownLatch(1)
    var pingSampled = CountDownLatch(1)
    val listeners = CopyOnWriteArrayList<OnBinderReceivedListener>()
    val removed = CopyOnWriteArrayList<OnBinderReceivedListener>()
    val callbackThreads = CopyOnWriteArrayList<String>()
    var permissionCalls = 0
    var bindCalls = 0
    var unbindCalls = 0
    var detachCalls = 0
    var bindBeforeReady = false
    var lastArgs: UserServiceArgs? = null
    var lastConnection: ServiceConnection? = null
    val localConnections = ConcurrentHashMap<String, MutableSet<ServiceConnection>>()
    val remoteServices = ConcurrentHashMap.newKeySet<String>()
    var currentEndpoint = 0
    val remoteEndpoints = ConcurrentHashMap<String, Int>()
    val cleanupArgs = CopyOnWriteArrayList<UserServiceArgs>()
    val cleanupConnections = CopyOnWriteArrayList<ServiceConnection>()
    fun pingBinder(): Boolean = rawBinder.also { pingSampled.countDown() }
    fun checkSelfPermission(): Int {
        check(Thread.currentThread().name != "fixture-main") { "Permission query on main" }
        permissionCalls++
        permissionFailure?.let { throw it }
        return permission
    }
    private fun post(listener: OnBinderReceivedListener) {
        ProbeBoundary.main.execute {
            callbackThreads.add(Thread.currentThread().name)
            listener.onBinderReceived()
        }
    }
    fun addBinderReceivedListenerSticky(listener: OnBinderReceivedListener) {
        // As in SDK13.1.5, sample readiness before installing the listener holder.
        if (attached) post(listener)
        afterStickySample?.invoke()
        listeners.add(listener)
        registered.countDown()
        afterRegistration?.invoke()
    }
    fun publishAttached() {
        rawBinder = true
        metadataReady = true // bindApplication metadata precedes callback scheduling.
        for (listener in listeners) post(listener)
        attached = true
    }
    fun removeBinderReceivedListener(listener: OnBinderReceivedListener): Boolean {
        removed.add(listener)
        listenerRemoveFailure?.let { throw it }
        return listeners.remove(listener) // Already posted callbacks remain queued.
    }
    fun bindUserService(args: UserServiceArgs, connection: ServiceConnection) {
        check(Thread.currentThread().name != "fixture-main") { "Service bind on main" }
        bindCalls++; bindBeforeReady = !metadataReady; lastArgs = args
        lastConnection = connection
        localConnections.getOrPut(args.tagValue) { ConcurrentHashMap.newKeySet() }.add(connection)
        beforeBind?.invoke()
        remoteServices.add(args.tagValue)
        remoteEndpoints[args.tagValue] = currentEndpoint
        if (synchronousConnection) ProbeBoundary.connectNow(args.component.context, connection)
        else ProbeBoundary.connect(args.component.context, connection)
        afterBindSideEffects?.invoke()
    }
    fun unbindUserService(args: UserServiceArgs, connection: ServiceConnection, remove: Boolean) {
        check(args === lastArgs && connection === lastConnection)
        cleanupArgs.add(args); cleanupConnections.add(connection)
        if (remove) {
            unbindCalls++
            remoteRemoveFailure?.let { throw it }
            // Static requireService resolves the CURRENT SDK endpoint. A returned call
            // on a replacement cannot prove removal at the original endpoint.
            if (remoteEndpoints[args.tagValue] == currentEndpoint) {
                remoteServices.remove(args.tagValue); remoteEndpoints.remove(args.tagValue)
            }
        } else {
            detachCalls++
            localDetachFailure?.let { throw it } // Optional server unregister can fail before local clear.
            localConnections.remove(args.tagValue)?.clear()
        }
    }
    fun reset() {
        rawBinder = false; attached = false; metadataReady = false; permission = 0; permissionFailure = null
        afterStickySample = null; afterRegistration = null; beforeBind = null
        afterBindSideEffects = null; synchronousConnection = false
        remoteRemoveFailure = null; localDetachFailure = null; listenerRemoveFailure = null
        registered = CountDownLatch(1); pingSampled = CountDownLatch(1)
        listeners.clear(); removed.clear(); callbackThreads.clear()
        permissionCalls = 0; bindCalls = 0; unbindCalls = 0; bindBeforeReady = false; lastArgs = null
        detachCalls = 0; lastConnection = null; localConnections.clear(); remoteServices.clear()
        cleanupArgs.clear(); cleanupConnections.clear()
        currentEndpoint = 0; remoteEndpoints.clear()
    }
}
