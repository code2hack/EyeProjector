package com.code2hack.eyeprojector.directhost.activation.snapshot

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import android.os.Process
import android.os.SystemClock
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.UUID
import rikka.shizuku.Shizuku

/** Prepared only. Root selects/reserves device execution separately from compilation. */
internal object NativeSnapshotChecks {
    fun run(context: Context, arguments: Bundle, result: Bundle) {
        val route = arguments.getString("snapshot-route", "android")
        require(route == "android" || route == "shizuku")
        val ready = CountDownLatch(1)
        val connectionLock = Any()
        var acceptingConnection = true
        var remote: INativeSnapshotProbe? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                val candidate = INativeSnapshotProbe.Stub.asInterface(binder)
                synchronized(connectionLock) {
                    if (acceptingConnection && remote == null) {
                        remote = candidate; ready.countDown()
                    }
                }
            }
            override fun onServiceDisconnected(name: ComponentName) = Unit
        }
        val installed = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
        // SDK removal uses tag/component, not the connection or service version. Each
        // invocation owns a distinct cache/server key even after an uncertain reply.
        val attemptTag = "eyeprojector-snapshot-probe-${UUID.randomUUID()}"
        val args = Shizuku.UserServiceArgs(ComponentName(context, NativeSnapshotProbeUserService::class.java))
            .tag(attemptTag).processNameSuffix("snapshot_probe").daemon(false)
            .version((installed xor (installed ushr 32)).toInt())
        val reader = NativeSnapshotReadLane() // This instrumentation thread owns every read/install/unmap.
        val binderReady = CountDownLatch(1)
        // SDK callbacks normally run on main. Only signal the instrumentation worker here;
        // removal belongs in finally, after sticky registration has finished installing.
        val binderReceived = Shizuku.OnBinderReceivedListener { binderReady.countDown() }
        var binderListenerRegistered = false
        var bound = false
        var shizukuBindInvoked = false
        var primaryFailure: Throwable? = null
        // Reuse Shizuku's existing 15-second setup wait budget for callback and service bind.
        // This bounds waits, not synchronous SDK/Binder calls.
        val setupDeadline = SystemClock.elapsedRealtimeNanos() + TimeUnit.SECONDS.toNanos(15)
        fun remainingSetupNanos() = (setupDeadline - SystemClock.elapsedRealtimeNanos()).coerceAtLeast(0)
        result.putString("snapshotRoute", route)
        result.putString("snapshotProbeTag", attemptTag)
        result.putInt("snapshotReaderPid", Process.myPid()); result.putInt("snapshotReaderUid", Process.myUid())
        result.putInt("snapshotReaderBitness", if (Process.is64Bit()) 64 else 32)
        try {
            if (route == "shizuku") {
                result.putString("snapshotShizukuPermissionStatus", "NOT_CHECKED")
                binderListenerRegistered = true
                Shizuku.addBinderReceivedListenerSticky(binderReceived)
                result.putBoolean("snapshotShizukuBinderPingBeforeWait", Shizuku.pingBinder())
                val callbackReady = binderReady.await(remainingSetupNanos(), TimeUnit.NANOSECONDS)
                result.putBoolean("snapshotShizukuCallbackReady", callbackReady)
                val binderPing = Shizuku.pingBinder()
                result.putBoolean("snapshotShizukuBinderPingAfterWait", binderPing)
                check(callbackReady) { "Shizuku Binder-received callback did not arrive within setup wait" }
                check(binderPing) { "Shizuku Binder is unavailable after received callback" }
                val permission = try {
                    Shizuku.checkSelfPermission().also {
                        result.putInt("snapshotShizukuPermissionResult", it)
                        result.putString("snapshotShizukuPermissionStatus", "RETURNED")
                    }
                } catch (failure: Throwable) {
                    result.putString("snapshotShizukuPermissionStatus", "EXCEPTION")
                    result.putString("snapshotShizukuPermissionException", failure.toString())
                    throw failure
                }
                check(permission == PackageManager.PERMISSION_GRANTED) { "Shizuku permission denied: $permission" }
                // SDK registers the original connection before its remote call, which
                // can throw after local/remote side effects. Normal return is separate.
                shizukuBindInvoked = true
                result.putBoolean("snapshotShizukuBindInvoked", true)
                Shizuku.bindUserService(args, connection)
                result.putBoolean("snapshotShizukuBindReturned", true)
            } else {
                bound = context.bindService(Intent(context, NativeSnapshotProbeService::class.java), connection, Context.BIND_AUTO_CREATE)
                check(bound)
            }
            val serviceReady = if (route == "shizuku") ready.await(remainingSetupNanos(), TimeUnit.NANOSECONDS)
                else ready.await(15, TimeUnit.SECONDS)
            check(serviceReady) { "Owned probe did not bind within setup wait" }
            val probe = synchronized(connectionLock) { checkNotNull(remote) }
            check(probe.pid() != Process.myPid()) { "Same-process result is not two-process proof" }
            val descriptor = probe.descriptor()
            val original = descriptor.context
            val region = descriptor.regionId
            reader.install(descriptor, original) // Actual mapReadOnly, not an RO Java view of an RW map.
            check(reader.read() == 0L)
            val observed = ArrayList<Long>()
            for (mode in listOf(SnapshotWord.PROJECTED, SnapshotWord.NONE, SnapshotWord.PROJECTED, SnapshotWord.LOCAL_PANEL)) {
                val receipt = probe.publishMode(mode)
                val mapped = reader.read()
                check(mapped == receipt && SnapshotWord.mode(mapped) == mode)
                observed.add(mapped)
            }
            check(observed[0] != observed[2]) { "Ready/NONE/ready reused a publication word" }
            val retired = probe.retireRegion()
            check(reader.read() == retired && SnapshotWord.mode(retired) == SnapshotWord.RETIRED)
            probe.closeRegion()
            check(reader.read() == retired) // FD close does not revoke the still-owned RO mapping.
            reader.clear(); check(SnapshotWord.mode(reader.read()) == SnapshotWord.RETIRED)
            result.putString("snapshotRoute", route)
            result.putInt("snapshotWriterPid", probe.pid()); result.putInt("snapshotWriterUid", probe.uid())
            result.putInt("snapshotWriterBitness", probe.bitness()); result.putInt("snapshotReaderBitness", if (Process.is64Bit()) 64 else 32)
            result.putInt("snapshotReaderPid", Process.myPid()); result.putInt("snapshotReaderUid", Process.myUid())
            result.putString("snapshotRegion", region); result.putString("snapshotOwner", original.ownerEpoch)
            result.putString("snapshotWords", observed.joinToString(",")); result.putString("snapshotScope", "TWO_PROCESS_RO_NATIVE_PROBE_ONLY")
        } catch (failure: Throwable) {
            primaryFailure = failure
            result.putString("snapshotPrimaryFailure", failure.toString())
            throw failure
        } finally {
            // Retire publication before any cleanup RPC. Late/duplicate callbacks only
            // reach this original connection and cannot replace its captured endpoint.
            val originalProbe = synchronized(connectionLock) { acceptingConnection = false; remote }
            var cleanupFailure: Throwable? = null
            fun cleanup(stage: String, operation: () -> Unit) {
                try {
                    operation()
                    result.putString("snapshotCleanup$stage", "RETURNED")
                } catch (failure: Throwable) {
                    result.putString("snapshotCleanup$stage", "EXCEPTION")
                    result.putString("snapshotCleanup${stage}Exception", failure.toString())
                    if (stage == "Listener") result.putString("snapshotShizukuListenerRemovalException", failure.toString())
                    val retained = primaryFailure ?: cleanupFailure
                    if (retained == null) cleanupFailure = failure
                    else if (retained !== failure) retained.addSuppressed(failure)
                }
            }
            if (binderListenerRegistered) cleanup("Listener") {
                val removed = Shizuku.removeBinderReceivedListener(binderReceived)
                result.putBoolean("snapshotShizukuListenerRemoved", removed)
                result.putString("snapshotShizukuListenerOutcome", if (removed) "REMOVED" else "NOT_FOUND")
            } else result.putString("snapshotCleanupListener", "NOT_REGISTERED")
            cleanup("ReadLane") { reader.clear() }
            if (originalProbe != null) cleanup("Region") { originalProbe.closeRegion() }
            else result.putString("snapshotCleanupRegion", "NO_HANDLE")
            if (shizukuBindInvoked) {
                cleanup("RemoteRemove") { Shizuku.unbindUserService(args, connection, true) }
                // remove=true does not clear the SDK callback cache. This separate
                // public detach can also throw before local clear; report that truthfully.
                cleanup("LocalDetach") { Shizuku.unbindUserService(args, connection, false) }
            } else {
                result.putString("snapshotCleanupRemoteRemove", "NOT_INVOKED")
                result.putString("snapshotCleanupLocalDetach", "NOT_INVOKED")
            }
            if (bound) cleanup("AndroidUnbind") { context.unbindService(connection) }
            if (primaryFailure == null) cleanupFailure?.let { throw it }
        }
    }
}
