package com.code2hack.eyeprojector.directhost.hid

import android.content.Context
import android.content.ContextServices
import android.os.Handler
import android.os.MainQueue
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import com.code2hack.eyeprojector.directhost.PhoneSessionController
import com.code2hack.eyeprojector.protocol.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import rikka.shizuku.Shizuku
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/** Real Runtime + KeyService + pin/controller/codec callers; Android/snapshot/target seams explicit. */
class CallerTest {
    private val a = InputDevice(41, "actual Android A", 12, 34, "Android A")
    private val b = InputDevice(42, "actual Android B", 56, 78, "Android B")
    private fun identity(device: InputDevice) = PhoneHidDeviceIdentity(device.descriptor, device.vendorId, device.productId)
    private fun profile(device: InputDevice) = PhoneHidPinRuntime.profileForDevice(identity(device))
    private fun binding(device: InputDevice, gesture: PhoneHidGesture = PhoneHidGesture.SINGLE) =
        PhoneHidBinding(PhoneHidButton(identity(device), 30, 90), device.name, "Key 30", gesture)
    private fun nativeBinding() = PhoneHidBinding(PhoneHidButton(PhoneHidDeviceIdentity(
        PhoneHidPinRuntime.NATIVE_PREFIX + JSONArray().put("N").put("keys"), 90, 91), 31, 0), "Native N", "Key 31")
    private fun field(owner: Any, name: String): Any? = owner.javaClass.getDeclaredField(name).let {
        it.isAccessible = true; it.get(owner)
    }
    private fun executor(owner: Any, name: String) = field(owner, name) as ExecutorService
    private fun resetSingleton() {
        PhoneHidRuntime::class.java.getDeclaredField("instance").let { it.isAccessible = true; it.set(null, null) }
    }
    private fun pump(runtime: PhoneHidRuntime) {
        repeat(8) {
            MainQueue.drain()
            executor(runtime.pin, "lane").submit {}.get(2, TimeUnit.SECONDS)
            executor(runtime, "writer").submit {}.get(2, TimeUnit.SECONDS)
            MainQueue.drain()
        }
    }
    private fun close(runtime: PhoneHidRuntime, service: PhoneHidKeyService?) {
        service?.onDestroy()
        pump(runtime)
        executor(runtime.pin, "lane").shutdownNow(); executor(runtime, "writer").shutdownNow()
        MainQueue.drain(); resetSingleton()
    }
    private fun context(entries: Map<PhoneHidAction, PhoneHidBinding>): Context {
        resetSingleton(); MainQueue.drain()
        ContextServices.inputManager.devices.clear(); Shizuku.connections.clear()
        PhoneSessionController.fixture.calls.clear()
        val context = Context()
        context.getSharedPreferences("phone_hid_bindings", 0).values["bindings"] = PhoneHidBindings.encode(entries)
        context.getSharedPreferences("pairing", 0).values["identity"] = "preserved pairing"
        return context
    }
    private fun press(service: PhoneHidKeyService, device: InputDevice?, at: Long,
                      down: Long = at, up: Boolean = false, id: Int = device?.id ?: -1): Boolean =
        service.onKeyEvent(KeyEvent(device, if (up) KeyEvent.ACTION_UP else KeyEvent.ACTION_DOWN,
            30, 90, down, at, deviceId = id))
    private val calls get() = PhoneSessionController.fixture.calls

    @Test fun sharedSavedAndroidCandidatesSurviveRealRuntimeKeyServiceAndTypedCodec() {
        Shizuku.binder = Backend()
        val expected = mapOf(PhoneHidAction.BACK to PhoneHidGesture.SINGLE,
            PhoneHidAction.HOME to PhoneHidGesture.LONG, PhoneHidAction.MODE to PhoneHidGesture.clicks(2))
        val context = context(expected.mapValues { binding(a, it.value) })
        val legacy = context.getSharedPreferences("phone_hid_bindings", 0).getString("bindings", null)
        val runtime = PhoneHidRuntime.get(context); val service = PhoneHidKeyService()
        try {
            service.onServiceConnected(); pump(runtime)
            assertTrue(runtime.androidControlsActive)
            assertTrue(press(service, a, 1000)); assertTrue(press(service, a, 1020, 1000, true))
            assertEquals(listOf("capture", "submit"), calls.map { it.first })
            val down = calls.first().third!!; val up = calls.last().third!!
            assertEquals(expected, down.candidates()); assertEquals(expected, up.candidates())
            assertEquals(down, PhoneHidEdge.from(down.bytes()))
            assertEquals(up, PhoneHidEdge.from(up.bytes())); assertTrue(down.samePress(up))
            assertTrue(down.bytes().size > PhoneHidEdge.PAYLOAD_SIZE)
            assertEquals(expected, runtime.controller.bindings.assignments(binding(a).button).mapValues { it.value.gesture })
            val panel = PhoneHidPanel(context, runtime) {}
            val rows = panel.children.filterIsInstance<Button>().map { it.text.toString() }
            assertTrue(rows.any { it.startsWith("Back:") && it.endsWith("Short press") })
            assertTrue(rows.any { it.startsWith("Home:") && it.endsWith("Long press") })
            assertTrue(rows.any { it.startsWith("Mode:") && it.endsWith("2 clicks") })
            assertEquals(legacy, context.getSharedPreferences("phone_hid_bindings", 0).getString("bindings", null))
        } finally { close(runtime, service) }
    }

    @Test fun soleMigratedAndroidProfileWorksThroughRealRuntimeAndKeyServiceWithoutKeyAutoSelection() {
        Shizuku.binder = Backend()
        val context = context(mapOf(PhoneHidAction.TAP to binding(a)))
        val legacy = context.getSharedPreferences("phone_hid_bindings", 0).getString("bindings", null)
        val runtime = PhoneHidRuntime.get(context); val service = PhoneHidKeyService()
        try {
            service.onServiceConnected()
            assertFalse(press(service, a, 100)) // Native intent is still loading; no fallback admission yet.
            pump(runtime)
            assertTrue(runtime.androidControlsActive); assertEquals(profile(a), runtime.controller.profileKey)
            assertTrue(press(service, a, 1000)); val route = calls.single().second
            assertFalse(press(service, b, 1005)); assertEquals(profile(a), runtime.controller.profileKey)
            assertFalse(press(service, a.copy(vendorId = 99), 1006))
            assertTrue(press(service, a, 1020, 1000, true))
            assertEquals(listOf("capture", "submit"), calls.map { it.first })
            assertSame(route, calls.last().second)
            assertEquals(binding(a), runtime.controller.bindings.binding(PhoneHidAction.TAP))
            val panel = PhoneHidPanel(context, runtime) {}
            assertTrue(panel.children.filterIsInstance<TextView>().any { it.text.toString().contains("Android key fallback") })
            assertEquals(legacy, context.getSharedPreferences("phone_hid_bindings", 0).getString("bindings", null))
            assertEquals("preserved pairing", context.getSharedPreferences("pairing", 0).getString("identity", null))
        } finally { close(runtime, service) }
    }

    @Test fun mixedLegacyIdentitiesRequireExplicitSelectorAndPersistOneExactChoiceOnRestart() {
        Shizuku.binder = Backend()
        val context = context(mapOf(PhoneHidAction.TAP to binding(a), PhoneHidAction.BACK to binding(b, PhoneHidGesture.clicks(2))))
        var runtime = PhoneHidRuntime.get(context); var service = PhoneHidKeyService()
        try {
            service.onServiceConnected(); pump(runtime)
            assertNull(runtime.androidProfileKey)
            assertFalse(press(service, a, 1000)); assertFalse(press(service, b, 1000)); assertTrue(calls.isEmpty())
            val panel = PhoneHidPanel(context, runtime) {}
            val selector = panel.children.filterIsInstance<Spinner>().last()
            assertEquals(0, selector.selectedItemPosition)
            val position = runtime.androidProfiles.indexOfFirst { it.first == profile(b) } + 1
            selector.onItemSelectedListener!!.onItemSelected(selector, null, position, position.toLong())
            pump(runtime)
            assertEquals(profile(b), runtime.androidProfileKey); assertEquals(profile(b), runtime.controller.profileKey)
            assertFalse(press(service, a, 2000)); assertTrue(press(service, b, 2000))
            assertEquals(PhoneHidAction.BACK, calls.single().third!!.action)
            assertEquals(PhoneHidGesture.clicks(2), calls.single().third!!.gesture)
            assertTrue(press(service, b, 2020, 2000, true))
            assertEquals(profile(b), context.getSharedPreferences("phone_hid_bindings", 0).getString("android_profile", null))
        } finally { close(runtime, service) }
        runtime = PhoneHidRuntime.get(context); service = PhoneHidKeyService(); calls.clear()
        try {
            service.onServiceConnected(); pump(runtime)
            assertEquals(profile(b), runtime.androidProfileKey)
            assertFalse(press(service, a, 3000)); assertTrue(press(service, b.copy(id = 99), 3000))
            assertEquals(profile(b), runtime.controller.profileKey)
        } finally { close(runtime, service) }
    }

    @Test fun nativePinAndReleasePreemptThenRestoreSameFallbackAndRetireOriginalTerminals() {
        val backend = Backend(); Shizuku.binder = backend
        val context = context(mapOf(PhoneHidAction.TAP to binding(a), PhoneHidAction.SCROLL_DOWN to nativeBinding()))
        val runtime = PhoneHidRuntime.get(context); val service = PhoneHidKeyService()
        try {
            service.onServiceConnected(); pump(runtime)
            assertTrue(press(service, a, 1000)); val original = calls.single().second
            runtime.pin.refresh(); pump(runtime); runtime.pin.select("N")
            assertEquals(profile(a), runtime.controller.profileKey) // Native inventory/preview never replaces fallback intent.
            runtime.pin.pinSelected()
            assertFalse(runtime.androidControlsActive)
            assertTrue(calls.any { it.first == "cancel" && it.second === original })
            assertTrue(press(service, a, 1010, 1000, true)) // Already consumed DOWN keeps its original UP while pinning.
            assertFalse(press(service, a, 1100))
            val owner = PhoneHidPinService(); owner.onCreate(); pump(runtime)
            assertTrue(runtime.pin.state.intercepted)
            assertEquals(PhoneHidPinRuntime.profileForPin("N"), runtime.controller.profileKey)
            val generation = backend.generation; val listener = backend.listener
            listener.onInput(backend.input(2000)); pump(runtime)
            assertEquals(PhoneHidAction.SCROLL_DOWN, calls.last().third!!.action)
            runtime.pin.unpin(); assertFalse(runtime.androidControlsActive); pump(runtime)
            assertTrue(runtime.androidControlsActive); assertEquals(profile(a), runtime.controller.profileKey)
            val count = calls.size
            listener.onInput(backend.input(2020, 2000, true, generation)); pump(runtime)
            assertEquals(count, calls.size)
            assertTrue(press(service, a.copy(id = 77), 3000))
            assertEquals(PhoneHidAction.TAP, calls.last().third!!.action)
        } finally { close(runtime, service) }
    }

    @Test fun serviceDisconnectIdentityLossAndReconnectKeepOriginalRoutesAndOneStableProfile() {
        Shizuku.binder = Backend()
        val runtime = PhoneHidRuntime.get(context(mapOf(PhoneHidAction.TAP to binding(a))))
        val oldService = PhoneHidKeyService(); var service = oldService
        try {
            oldService.onServiceConnected(); pump(runtime)
            assertTrue(press(oldService, a, 1000)); val first = calls.single().second
            oldService.onUnbind(null); pump(runtime)
            assertFalse(runtime.androidControlsActive)
            assertTrue(calls.any { it.first == "cancel" && it.second === first })
            service = PhoneHidKeyService(); service.onServiceConnected(); pump(runtime)
            val reattached = a.copy(id = 88)
            assertTrue(press(service, reattached, 2000)); val second = calls.last().second
            val count = calls.size
            assertTrue(press(service, null, 2020, 1000, true, a.id)); assertEquals(count, calls.size)
            assertNotSame(first, second)
            service.onInputDeviceChanged(reattached.id)
            assertTrue(calls.any { it.first == "cancel" && it.second === second })
            assertTrue(press(service, null, 2030, 2000, true, reattached.id))
            assertFalse(press(service, reattached.copy(descriptor = "different recorded identity"), 3000))
            assertEquals(profile(a), runtime.androidProfileKey)
            assertTrue(press(service, reattached, 4000))
        } finally { close(runtime, service) }
    }

    @Test fun failedSelectionRetainsPreviousIntentAndResetOnlyAffectsActualSelectedFallback() {
        for (throws in listOf(false, true)) {
            Shizuku.binder = Backend()
            val context = context(mapOf(PhoneHidAction.TAP to binding(a), PhoneHidAction.BACK to binding(b)))
            val runtime = PhoneHidRuntime.get(context); val service = PhoneHidKeyService()
            try {
                service.onServiceConnected(); pump(runtime)
                runtime.selectAndroidProfile(profile(a)); pump(runtime)
                val preferences = context.getSharedPreferences("phone_hid_bindings", 0)
                val legacy = preferences.getString("bindings", null)
                preferences.writable = false; preferences.throwing = throws
                runtime.selectAndroidProfile(profile(b)); pump(runtime)
                assertEquals(profile(a), runtime.androidProfileKey)
                assertEquals(profile(a), preferences.getString("android_profile", null)); assertNotNull(runtime.profileError)
                assertFalse(press(service, b, 1000))
                preferences.writable = true; preferences.throwing = false
                preferences.commitOutcomes.addAll(listOf(true, false))
                runtime.selectAndroidProfile(profile(b)); runtime.selectAndroidProfile(profile(a)); pump(runtime)
                assertEquals(profile(b), runtime.androidProfileKey) // Retain last successful ordered write, even if the next choice fails.
                assertEquals(profile(b), preferences.getString("android_profile", null))
                runtime.selectAndroidProfile(profile(b)); pump(runtime)
                val panel = PhoneHidPanel(context, runtime) {}
                val reset = panel.children.filterIsInstance<Button>().single { it.text == "Reset selected profile" }
                assertTrue(reset.isEnabled); reset.performClick(); pump(runtime)
                assertNull(runtime.controller.bindings.binding(PhoneHidAction.BACK))
                runtime.selectAndroidProfile(profile(a)); pump(runtime)
                assertEquals(binding(a), runtime.controller.bindings.binding(PhoneHidAction.TAP))
                assertEquals(legacy, preferences.getString("bindings", null))
            } finally { close(runtime, service) }
        }
    }

    @Test fun oldLearningTimerAndAdmittedSaveStayWithOriginalProfileAcrossActualCallerSwitch() {
        val backend = Backend(); Shizuku.binder = backend
        val runtime = PhoneHidRuntime.get(context(mapOf(PhoneHidAction.TAP to binding(a), PhoneHidAction.BACK to binding(b))))
        val service = PhoneHidKeyService()
        val main = field(runtime, "main") as Handler
        try {
            service.onServiceConnected(); pump(runtime); runtime.selectAndroidProfile(profile(a)); pump(runtime)
            runtime.controller.openPage(); runtime.controller.select(PhoneHidAction.MODE)
            press(service, a, 1000); press(service, a, 1010, 1000, true)
            val oldTimers = main.pending.toList()
            runtime.selectAndroidProfile(profile(b)); pump(runtime)
            SystemClock.now = 1600
            oldTimers.forEach { it.task.run() }; pump(runtime)
            assertNull(runtime.controller.bindings.binding(PhoneHidAction.MODE))
            runtime.controller.select(PhoneHidAction.MODE)
            val gate = CountDownLatch(1); val entered = CountDownLatch(1)
            executor(runtime, "writer").submit { entered.countDown(); assertTrue(gate.await(2, TimeUnit.SECONDS)) }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            press(service, b, 2000); press(service, b, 2800, 2000, true) // Completed original B learning save is queued.
            runtime.pin.refresh()
            executor(runtime.pin, "lane").submit {}.get(2, TimeUnit.SECONDS); MainQueue.drain()
            runtime.pin.select("N"); runtime.pin.pinSelected()
            assertEquals(PhoneHidPinRuntime.profileForPin("N"), runtime.controller.profileKey)
            gate.countDown(); pump(runtime)
            runtime.pin.unpin(); pump(runtime)
            assertEquals(profile(b), runtime.controller.profileKey)
            assertEquals(PhoneHidGesture.LONG, runtime.controller.bindings.binding(PhoneHidAction.MODE)!!.gesture)
            runtime.selectAndroidProfile(profile(a)); pump(runtime)
            assertNull(runtime.controller.bindings.binding(PhoneHidAction.MODE))
            assertEquals(binding(a), runtime.controller.bindings.binding(PhoneHidAction.TAP))
        } finally { close(runtime, service) }
    }

    @Test fun selectionReadFailureReportsWithoutReplacingLegacyOrInventingAnotherProfile() {
        Shizuku.binder = Backend()
        val context = context(mapOf(PhoneHidAction.TAP to binding(a)))
        val preferences = context.getSharedPreferences("phone_hid_bindings", 0)
        val legacy = preferences.getString("bindings", null)
        preferences.readFailureKey = "android_profile"
        val runtime = PhoneHidRuntime.get(context); val service = PhoneHidKeyService()
        try {
            service.onServiceConnected(); pump(runtime)
            assertNull(runtime.androidProfileKey); assertNotNull(runtime.profileError)
            assertFalse(press(service, a, 1000)); assertTrue(calls.isEmpty())
            assertEquals(legacy, preferences.getString("bindings", null))
            preferences.readFailureKey = null
            runtime.selectAndroidProfile(profile(a)); pump(runtime)
            assertNull(runtime.profileError); assertTrue(press(service, a, 2000))
        } finally { close(runtime, service) }
    }

    private class Backend : IHidCapture {
        var generation = 0L; var active = false
        lateinit var listener: IHidCaptureListener
        override fun attach(listener: IHidCaptureListener) { this.listener = listener }
        override fun state() = JSONObject().put("generation", generation).put("deviceKey", if (active) "N" else null)
            .put("intercepted", active).put("phase", if (active) "ACTIVE" else "IDLE").put("collectionCount", 1).toString()
        override fun inventory() = JSONObject().put("devices", JSONArray().put(JSONObject().put("key", "N")
            .put("name", "Native N").put("vendorId", 90).put("productId", 91).put("collections", 1).put("hasKeys", true))).toString()
        override fun pin(key: String): String { assertEquals("N", key); generation++; active = true; return state() }
        override fun unpin(): String { generation++; active = false; return state() }
        fun input(at: Long, down: Long = at, up: Boolean = false, epoch: Long = generation) = JSONObject()
            .put("deviceKey", "N").put("generation", epoch).put("sourceId", 22).put("collectionKey", "keys")
            .put("vendorId", 90).put("productId", 91).put("code", 31).put("label", "Key 31").put("deviceName", "Native N")
            .put("phase", if (up) "UP" else "DOWN").put("downTimeMs", down).put("eventTimeMs", at)
            .put("repeatCount", 0).put("pressId", down).toString()
    }
}
