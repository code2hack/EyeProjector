package com.code2hack.eyeprojector.directhost.hid

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.code2hack.eyeprojector.protocol.PhoneHidPhase
import org.json.JSONArray
import org.json.JSONObject
import rikka.shizuku.Shizuku
import java.util.concurrent.Executors

internal data class PhoneHidPhysicalDevice(
    val key: String, val name: String, val vendorId: Int, val productId: Int,
    val collectionCount: Int, val hasKeys: Boolean, val hasTouch: Boolean,
)

internal data class PhoneHidPinState(
    val devices: List<PhoneHidPhysicalDevice> = emptyList(),
    val savedKey: String? = null, val savedName: String? = null,
    val phase: String = "IDLE", val intercepted: Boolean = false,
    val collectionCount: Int = 0, val error: String? = null,
)

/** One app owner and one private Shizuku process; no projection Stop/destroy dependency. */
internal class PhoneHidPinRuntime(
    private val app: Context,
    private val controller: PhoneHidController,
    private val connectionChanged: (Boolean) -> Unit,
    private val changed: () -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val lane = Executors.newSingleThreadExecutor()
    private val preferences = app.getSharedPreferences("phone_hid_pin", Context.MODE_PRIVATE)
    @Volatile private var desiredKey: String? = null
    @Volatile private var desiredName: String? = null
    @Volatile private var loading = true
    @Volatile private var command = 0L
    private var service: PhoneHidPinService? = null // Main lane only.
    private var ownerStarting = false
    @Volatile private var ownerActive = false
    @Volatile private var ownerError: String? = null
    var state = PhoneHidPinState(); private set // Immutable main-lane UI snapshot.
    var selectedKey: String? = null; private set
    val ownsRoute: Boolean get() = loading || desiredKey != null || releasePending
    val pinnedKey: String? get() = desiredKey ?: state.savedKey
    val canSelectDevice: Boolean get() = !loading && pinnedKey == null && !releasePending
    val canPinSelected: Boolean get() = !loading && !releasePending && !state.intercepted &&
        (pinnedKey != null || state.devices.any { it.key == selectedKey })

    // Everything below, except main delivery bookkeeping, belongs to lane.
    private var remote: IHidCapture? = null
    private var binding: ServiceConnection? = null
    @Volatile private var bindingEpoch = 0L
    private var generation: Long? = null
    private var activeKey: String? = null
    private var intercepted = false
    private var captureAttempted = false
    private var devices = emptyList<PhoneHidPhysicalDevice>()
    @Volatile private var releasePending = false
    private var restored = false
    private var storedKey: String? = null
    private var storedName: String? = null
    private var preferenceError: String? = null
    private var nextAttachment = -1
    private val attachments = mutableMapOf<Int, Int>()
    // Main-lane IDs are retired before the next generation's input callback.
    private val deliveredAttachments = mutableSetOf<Int>()
    private val args by lazy {
        val revision = app.packageManager.getPackageInfo(app.packageName, 0).lastUpdateTime.let {
            (it xor (it ushr 32)).toInt()
        }
        Shizuku.UserServiceArgs(ComponentName(app,
            "com.code2hack.eyeprojector.directhost.hid.ShizukuHidCaptureService"))
            .tag("eyeprojector-hid-pin").processNameSuffix("hid_capture").daemon(false).version(revision)
    }
    private val received = Shizuku.OnBinderReceivedListener { refresh() }
    private val permission = Shizuku.OnRequestPermissionResultListener { _, _ -> refresh() }
    private val dead = Shizuku.OnBinderDeadListener { lane.execute {
        // The user-service Binder may outlive the Shizuku control Binder; lost releases it.
        lost("Shizuku is unavailable. Restore it to intercept the saved device.")
    } }

    init {
        Shizuku.addBinderReceivedListenerSticky(received)
        Shizuku.addBinderDeadListener(dead)
        Shizuku.addRequestPermissionResultListener(permission)
    }

    fun restore() {
        lane.execute {
            if (restored) return@execute
            restored = true
            storedKey = preferences.getString("device_key", null)
            storedName = preferences.getString("device_name", null)
            desiredKey = storedKey; desiredName = storedName
            loading = false
            publish(if (desiredKey == null) "IDLE" else "DISCONNECTED", false)
            if (desiredKey != null) {
                main.post { ensureOwner() }
                connect()
            }
        }
    }

    fun select(key: String) {
        if (!canSelectDevice || state.devices.none { it.key == key }) return
        selectedKey = key
        changed()
    }

    fun pinSelected() {
        if (!canPinSelected) return
        // A saved/desired pin can only be retried. Unpin/reset is the device-switch operation.
        val key = pinnedKey ?: selectedKey ?: return
        val device = state.devices.firstOrNull { it.key == key }
        val name = device?.name ?: desiredName ?: state.savedName ?: "Pinned device"
        val token = ++command
        selectedKey = key
        desiredKey = key; desiredName = name
        controller.cancelInput()
        retireDelivered()
        state = state.copy(savedKey = key, savedName = name, phase = "PINNING", intercepted = false, error = null)
        changed()
        ensureOwner()
        lane.execute {
            if (token != command) return@execute
            val saved = preferences.edit().putString("device_key", key).putString("device_name", name).commit()
            if (saved) { storedKey = key; storedName = name; preferenceError = null }
            if (token != command) return@execute
            if (!saved) {
                desiredKey = storedKey; desiredName = storedName
                preferenceError = "The pin request could not be saved. The previous saved pin is retained. Tap Pin to retry."
                publish("FAILED", intercepted && activeKey == desiredKey)
                return@execute
            }
            releasePending = false
            if (remote == null) connect() else requestPin()
        }
    }

    fun unpinAndReset() {
        val token = ++command
        val hadPin = desiredKey != null || state.intercepted || releasePending
        desiredKey = null; desiredName = null; selectedKey = null
        releasePending = true
        controller.resetAll()
        retireDelivered()
        state = state.copy(savedKey = null, savedName = null, phase = "RELEASING", error = null)
        changed()
        lane.execute {
            if (token != command) return@execute
            releasePending = true
            val saved = preferences.edit().remove("device_key").remove("device_name").commit()
            if (saved) { storedKey = null; storedName = null; preferenceError = null }
            else preferenceError = "The saved pin could not be cleared. Tap Unpin and reset again."
            if (token != command) return@execute
            try {
                val source = remote
                if (source != null) acceptState(source.unpin())
                else if (hadPin || captureAttempted || binding != null) connect()
                else { releasePending = false; publish("IDLE", false) }
            } catch (_: Exception) { lost("Device release is awaiting capture service reconnection.") }
            if (!saved) publish("FAILED", false)
        }
    }

    fun refresh() {
        lane.execute {
            if (!restored) return@execute
            if (remote == null) connect() else {
                try { acceptState(remote!!.state()) }
                catch (_: Exception) { lost("The capture service disconnected. Tap Refresh to reconnect.") }
            }
        }
    }

    fun serviceStarted(owner: PhoneHidPinService) {
        service = owner; ownerStarting = false; ownerActive = true; ownerError = null
        owner.show(notificationText())
        lane.execute {
            if (!restored) return@execute
            try {
                if (remote == null) connect()
                else if (desiredKey != null) requestPin()
                else if (releasePending) acceptState(remote!!.unpin())
                else acceptState(remote!!.state())
            } catch (_: Exception) { lost("The capture service disconnected. Tap Refresh to reconnect.") }
        }
    }

    fun serviceStopped(owner: PhoneHidPinService) {
        if (service !== owner) return
        service = null; ownerStarting = false; ownerActive = false
        retireDelivered()
        if (desiredKey != null) lane.execute {
            runCatching { remote?.unpin() }
            main.post { retireDelivered() }
            publish("DISCONNECTED", false, "The pinned owner stopped. Open EyeProjector and tap Pin to resume.")
        }
        // Android can restart this foreground service after process loss. Saved intent survives it.
    }

    private fun ensureOwner() {
        if (desiredKey == null || service != null || ownerStarting) return
        try {
            ownerStarting = true; ownerError = null
            app.startForegroundService(Intent(app, PhoneHidPinService::class.java))
        } catch (_: Exception) {
            ownerStarting = false
            ownerError = "Pinned capture could not stay running. Open EyeProjector and tap Pin to retry."
            state = state.copy(phase = "FAILED", intercepted = false, error = ownerError)
            changed()
        }
    }

    private fun connect() {
        try {
            if (!Shizuku.pingBinder() || Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                lost("Start Shizuku and authorize EyeProjector, then tap Refresh.")
                return
            }
            if (binding != null) return
            val epoch = ++bindingEpoch
            val callback = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                    lane.execute {
                        if (epoch != bindingEpoch) return@execute
                        try {
                            remote = IHidCapture.Stub.asInterface(binder)
                            binder.linkToDeath({ lane.execute {
                                if (epoch == bindingEpoch) lost("The capture service stopped. Tap Refresh to reconnect.")
                            } }, 0)
                            remote!!.attach(object : IHidCaptureListener.Stub() {
                                override fun onState(stateJson: String) { lane.execute {
                                    if (epoch == bindingEpoch) runCatching { acceptState(stateJson) }
                                        .onFailure { lost("The capture service returned an unreadable state.") }
                                } }
                                override fun onInput(inputJson: String) { lane.execute {
                                    if (epoch == bindingEpoch) runCatching { acceptInput(inputJson) }
                                } }
                            })
                            if (releasePending) acceptState(remote!!.unpin())
                            else if (desiredKey != null) requestPin()
                            else acceptState(remote!!.state())
                        } catch (_: Exception) { lost("The capture service could not attach. Tap Refresh to retry.") }
                    }
                }
                override fun onServiceDisconnected(name: ComponentName) { lane.execute {
                    if (epoch == bindingEpoch) lost("The capture service disconnected. Tap Refresh to reconnect.")
                } }
            }
            binding = callback
            publish(if (desiredKey == null) "IDLE" else "PINNING", false)
            Shizuku.bindUserService(args, callback)
        } catch (_: Exception) { lost("The capture service could not start. Check Shizuku, then tap Refresh.") }
    }

    private fun requestPin() {
        val key = desiredKey ?: return
        if (!ownerActive) return // serviceStarted resumes this request once its foreground lifetime exists.
        val token = command
        captureAttempted = true
        try {
            val response = remote!!.pin(key)
            if (token == command) acceptState(response)
        }
        catch (_: Exception) { lost("Interception could not start. Tap Refresh to retry.") }
    }

    /** Cached read only: state publication remains the caller's single responsibility. */
    private fun readInventory(): String? {
        val json = JSONObject(remote!!.inventory())
        val array = json.optJSONArray("devices") ?: JSONArray()
        devices = (0 until array.length()).map { i ->
            val item = array.getJSONObject(i)
            val collections = item.opt("collections")
            PhoneHidPhysicalDevice(item.getString("key"), item.getString("name"),
                item.getInt("vendorId"), item.getInt("productId"),
                if (collections is JSONArray) collections.length() else item.optInt("collections", 0),
                item.optBoolean("hasKeys"), item.optBoolean("hasTouch"))
        }
        return json.optString("error").takeIf { it.isNotEmpty() && it != "null" }
    }

    private fun acceptState(value: String) {
        val json = JSONObject(value)
        val nextGeneration = json.getLong("generation")
        if (generation?.let { nextGeneration < it } == true) return
        // Backend publishes state after refreshing this cache, including first attach/hotplug.
        val inventoryError = readInventory()
        val key = json.optString("deviceKey").takeIf { it.isNotEmpty() && it != "null" }
        val actual = json.getBoolean("intercepted")
        val phase = json.getString("phase")
        val newAttachment = actual && key == desiredKey && desiredKey != null &&
            (generation != nextGeneration || activeKey != key || !intercepted)
        if (generation != nextGeneration || activeKey != key || intercepted != actual) {
            attachments.clear()
            main.post { retireDelivered() }
        }
        generation = nextGeneration; activeKey = key; intercepted = actual
        if (releasePending && !actual && phase == "IDLE") {
            releasePending = false
            captureAttempted = false
        }
        // A state for a replaced pin is never presented as interception of the new saved device.
        val matches = key == desiredKey && desiredKey != null && ownerActive
        publish(if (releasePending) "RELEASING" else if (desiredKey != null && key != desiredKey && phase == "ACTIVE") "PINNING" else phase,
            actual && matches,
            json.optString("error").takeIf { it.isNotEmpty() && it != "null" } ?: inventoryError,
            json.optInt("collectionCount", 0))
        if (newAttachment) {
            val token = command
            val epoch = bindingEpoch
            main.post {
                if (token == command && epoch == bindingEpoch && desiredKey == key) controller.externalAttached()
            }
        }
    }

    private fun acceptInput(value: String) {
        val json = JSONObject(value)
        val key = json.getString("deviceKey")
        if (!ownerActive || !intercepted || key != activeKey || key != desiredKey || json.getLong("generation") != generation) return
        val source = json.getInt("sourceId")
        val attachment = attachments.getOrPut(source) { nextAttachment-- }
        val collection = json.getString("collectionKey")
        val identity = PhoneHidDeviceIdentity(NATIVE_PREFIX + JSONArray().put(key).put(collection).toString(),
            json.getInt("vendorId"), json.getInt("productId"))
        val event = PhoneHidController.Event(attachment,
            PhoneHidBinding(PhoneHidButton(identity, json.getInt("code"), 0),
                json.getString("deviceName"), json.getString("label")),
            PhoneHidPhase.valueOf(json.getString("phase")), json.getLong("downTimeMs"),
            json.getLong("eventTimeMs"), json.getInt("repeatCount"), json.getLong("pressId"))
        val token = command
        val epoch = bindingEpoch
        main.post {
            if (!ownerActive || token != command || epoch != bindingEpoch || desiredKey != key) return@post
            deliveredAttachments.add(attachment)
            controller.accept(event)
        }
    }

    private fun lost(error: String) {
        val old = binding
        val oldRemote = remote
        binding = null; remote = null; bindingEpoch++
        generation = null; activeKey = null; intercepted = false; attachments.clear()
        main.post { retireDelivered() }
        runCatching { oldRemote?.unpin() }
        if (old != null) runCatching { Shizuku.unbindUserService(args, old, false) }
        publish(if (releasePending) "RELEASING" else "DISCONNECTED", false, error)
    }

    private fun publish(phase: String, actual: Boolean, error: String? = null, count: Int = 0) {
        val token = command
        val next = PhoneHidPinState(devices, storedKey, storedName,
            if (preferenceError != null || (ownerError != null && desiredKey != null)) "FAILED" else phase,
            actual && ownerActive, count, preferenceError ?: ownerError ?: error)
        val stop = desiredKey == null && !releasePending && !actual
        main.post {
            if (token != command) return@post
            state = next
            val pinned = pinnedKey
            if (pinned != null) selectedKey = pinned
            else if (next.devices.none { it.key == selectedKey }) selectedKey = next.devices.firstOrNull()?.key
            connectionChanged(next.intercepted)
            service?.show(notificationText())
            if (stop) {
                service?.stopForeground(android.app.Service.STOP_FOREGROUND_REMOVE)
                service?.stopSelf()
            }
            changed()
        }
    }

    private fun notificationText(): String = if (state.intercepted)
        "${state.savedName ?: "Device"} intercepted · Unpin to release"
        else "${state.savedName ?: "Pinned device"}: ${state.phase.lowercase()}"

    private fun retireDelivered() {
        val old = deliveredAttachments.toList()
        deliveredAttachments.clear()
        old.forEach(controller::deviceRemoved)
        connectionChanged(false)
    }

    companion object {
        const val NATIVE_PREFIX = "\u0000eyeprojector.native-hid.1:"

        fun bindingMatchesDevice(binding: PhoneHidBinding, deviceKey: String): Boolean {
            val descriptor = binding.button.device.descriptor
            if (!descriptor.startsWith(NATIVE_PREFIX)) return false
            return try {
                JSONArray(descriptor.removePrefix(NATIVE_PREFIX)).getString(0) == deviceKey
            } catch (_: Exception) { false }
        }
    }
}
