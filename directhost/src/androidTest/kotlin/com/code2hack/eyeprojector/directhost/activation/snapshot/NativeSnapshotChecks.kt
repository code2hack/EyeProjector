package com.code2hack.eyeprojector.directhost.activation.snapshot

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import android.os.Process
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import rikka.shizuku.Shizuku

/** Prepared only. Root selects/reserves device execution separately from compilation. */
internal object NativeSnapshotChecks {
    fun run(context: Context, arguments: Bundle, result: Bundle) {
        val route = arguments.getString("snapshot-route", "android")
        require(route == "android" || route == "shizuku")
        val ready = CountDownLatch(1)
        var remote: INativeSnapshotProbe? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                remote = INativeSnapshotProbe.Stub.asInterface(binder); ready.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName) = Unit
        }
        val installed = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
        val args = Shizuku.UserServiceArgs(ComponentName(context, NativeSnapshotProbeUserService::class.java))
            .tag("eyeprojector-snapshot-probe").processNameSuffix("snapshot_probe").daemon(false)
            .version((installed xor (installed ushr 32)).toInt())
        val reader = NativeSnapshotReadLane() // This instrumentation thread owns every read/install/unmap.
        var bound = false
        try {
            if (route == "shizuku") {
                check(Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED)
                Shizuku.bindUserService(args, connection); bound = true
            } else {
                bound = context.bindService(Intent(context, NativeSnapshotProbeService::class.java), connection, Context.BIND_AUTO_CREATE)
                check(bound)
            }
            check(ready.await(15, TimeUnit.SECONDS)) { "Owned probe did not bind" }
            val probe = checkNotNull(remote)
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
        } finally {
            reader.clear()
            runCatching { remote?.closeRegion() }
            if (bound) {
                if (route == "shizuku") Shizuku.unbindUserService(args, connection, true)
                else context.unbindService(connection)
            }
        }
    }
}
