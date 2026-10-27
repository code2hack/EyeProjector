package com.code2hack.eyeprojector.directhost

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceInfo
import java.io.Closeable
import org.json.JSONObject

/** Resolves Android's active headset to its current communication output; never stores its address. */
internal class ActiveHeadsetSco(private val changed: () -> Unit) : Closeable {
    private val gate = Any()
    private var closed = false
    private var requested = false
    private var adapter: BluetoothAdapter? = null
    private var headset: BluetoothHeadset? = null
    private var receiverContext: Context? = null
    @Volatile private var activeObserved = false
    @Volatile private var selectedId: Int? = null
    @Volatile private var failureClass: String? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = changed()
    }

    private val listener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            val original = proxy as? BluetoothHeadset ?: return
            val retain = synchronized(gate) {
                if (closed) false else { headset = original; true }
            }
            if (retain) changed()
            else adapter?.closeProfileProxy(BluetoothProfile.HEADSET, original)
        }

        override fun onServiceDisconnected(profile: Int) {
            synchronized(gate) { headset = null }
            activeObserved = false
            selectedId = null
            changed()
        }
    }

    fun resolve(context: Context, outputs: List<AudioDeviceInfo>): AudioDeviceInfo? {
        val request = synchronized(gate) {
            if (closed) return null
            if (requested) false else { requested = true; true }
        }
        try {
            if (request) {
                // app_process does not run ActivityThread.main's module initialization.
                // Install the real lookup facade before constructing a manager (which caches its adapter).
                val initializer = Class.forName("android.bluetooth.BluetoothFrameworkInitializer")
                val managerType = Class.forName("android.os.BluetoothServiceManager")
                if (initializer.getMethod("getBluetoothServiceManager").invoke(null) == null) {
                    initializer.getMethod("setBluetoothServiceManager", managerType)
                        .invoke(null, managerType.getConstructor().newInstance())
                }
                val bluetooth = BluetoothManager::class.java.getConstructor(Context::class.java).newInstance(context)
                val originalAdapter = bluetooth.adapter
                synchronized(gate) { adapter = originalAdapter }
                val filter = IntentFilter(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
                val activeAction = BluetoothHeadset::class.java.getField("ACTION_ACTIVE_DEVICE_CHANGED")
                    .get(null) as String
                filter.addAction(activeAction)
                context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
                synchronized(gate) { receiverContext = context }
                originalAdapter?.getProfileProxy(context, listener, BluetoothProfile.HEADSET)
            }
            val original = synchronized(gate) { if (closed) null else headset } ?: return null
            val active = BluetoothHeadset::class.java.getMethod("getActiveDevice")
                .invoke(original) as? BluetoothDevice
            activeObserved = active != null
            // Match in memory. Neither address, product name nor recording contents enter status.
            val selected = active?.let { device -> outputs.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO && it.isSink &&
                    it.address.equals(device.address, ignoreCase = true)
            } }
            selectedId = selected?.id
            return selected
        } catch (error: Throwable) {
            val cause = (error as? java.lang.reflect.InvocationTargetException)?.cause ?: error
            failureClass = cause.javaClass.name
            selectedId = null
            return null
        }
    }

    fun status(): JSONObject = JSONObject()
        .put("activeHeadsetObserved", activeObserved)
        .put("communicationOutputId", selectedId ?: JSONObject.NULL)
        .put("failureClass", failureClass ?: JSONObject.NULL)

    override fun close() {
        val originals = synchronized(gate) {
            if (closed) return
            closed = true
            Triple(adapter, headset, receiverContext).also {
                headset = null
                receiverContext = null
            }
        }
        originals.third?.let { runCatching { it.unregisterReceiver(receiver) } }
        originals.second?.let { runCatching { originals.first?.closeProfileProxy(BluetoothProfile.HEADSET, it) } }
    }
}
