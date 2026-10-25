package com.code2hack.eyeprojector.directhost.activation.snapshot

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Process
import androidx.annotation.Keep
import com.code2hack.eyeprojector.directhost.ProjectionContext
import java.util.UUID

/** Only this debug probe owns this region; no actual Session or HID path selects it. */
@Keep
open class NativeSnapshotProbeEndpoint : INativeSnapshotProbe.Stub() {
    private val writer = NativeSnapshotWriter.create(ProjectionContext("snapshot-probe-${Process.myPid()}", UUID.randomUUID().toString()))
    private var last = 0L
    private var closed = false
    @Synchronized override fun descriptor(): NativeSnapshotDescriptor { check(!closed); return writer.descriptor() }
    @Synchronized override fun publishMode(mode: Int): Long {
        check(!closed)
        check(writer.publish(mode) == NativeSnapshotWriter.Publication.PUBLISHED)
        // This is a control receipt. Only the app's separate RO read establishes mapped visibility.
        last = SnapshotWord.pack(SnapshotWord.stamp(last) + 1, mode)
        return last
    }
    @Synchronized override fun retireRegion(): Long {
        check(!closed); writer.retire()
        last = SnapshotWord.pack(SnapshotWord.stamp(last), SnapshotWord.RETIRED)
        return last
    }
    @Synchronized override fun closeRegion() { if (!closed) { closed = true; writer.close() } }
    override fun pid() = Process.myPid()
    override fun uid() = Process.myUid()
    override fun bitness() = if (Process.is64Bit()) 64 else 32
    override fun destroy() { closeRegion(); Process.killProcess(Process.myPid()) }
}

class NativeSnapshotProbeService : Service() {
    private lateinit var endpoint: NativeSnapshotProbeEndpoint
    override fun onCreate() { super.onCreate(); endpoint = NativeSnapshotProbeEndpoint() }
    override fun onBind(intent: Intent?): android.os.IBinder = endpoint
    override fun onDestroy() { endpoint.closeRegion(); super.onDestroy() }
}

/** Uses the same installed-package Context/ClassLoader entry route as the real Shizuku host. */
@Keep
class NativeSnapshotProbeUserService @Keep constructor(@Suppress("UNUSED_PARAMETER") context: Context) : NativeSnapshotProbeEndpoint()
