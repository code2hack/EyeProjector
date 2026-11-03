package com.code2hack.eyeprojector.directhost.hid

import android.content.Context
import android.os.MainQueue
import android.widget.Button
import android.widget.TextView
import com.code2hack.eyeprojector.directhost.input.PhoneHidRoute
import com.code2hack.eyeprojector.protocol.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import rikka.shizuku.Shizuku
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.Base64
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

class ProfilesTest {
    private fun binding(device: String, collection: String = "keys", key: Int = 30,
                        gesture: PhoneHidGesture = PhoneHidGesture.SINGLE) = PhoneHidBinding(
        PhoneHidButton(PhoneHidDeviceIdentity(PhoneHidPinRuntime.NATIVE_PREFIX + JSONArray().put(device).put(collection), 12, 34), key, 0),
        "Remote $device", "Key $key", gesture)
    private fun profile(device: String) = PhoneHidPinRuntime.profileForPin(device)
    private class Disk(var record: String? = null, var legacy: String? = null) {
        var writable = true; var throwing = false; var writes = 0
        fun open() = PhoneHidProfiles({ record }, { legacy }, {
            writes++
            if (throwing) error("disk unavailable")
            if (writable) record = it
            writable
        }, PhoneHidPinRuntime::profileForDevice)
    }

    @Test fun independentRolesGesturesCollectionsAndHandlesSurviveReload() {
        val d = Disk(); val store = d.open()
        val a = store.bindings(profile("A")); val b = store.bindings(profile("B"))
        val first = binding("A", gesture = PhoneHidGesture.LONG)
        val second = binding("B", gesture = PhoneHidGesture.clicks(3))
        assertTrue(a.assign(PhoneHidAction.MODE, first))
        assertTrue(b.assign(PhoneHidAction.MODE, second))
        assertTrue(a.assign(PhoneHidAction.TAP, binding("A", collection = "touch", key = 900)))
        assertNull(a.action(second.button)); assertNull(b.action(first.button))
        val restart = d.open()
        assertEquals(first, restart.bindings(profile("A")).binding(PhoneHidAction.MODE))
        assertEquals(second, restart.bindings(profile("B")).binding(PhoneHidAction.MODE))
        assertEquals(binding("A", "touch", 900), restart.bindings(profile("A")).binding(PhoneHidAction.TAP))
        assertTrue(a.resetAll())
        assertNull(d.open().bindings(profile("A")).binding(PhoneHidAction.MODE))
        assertEquals(second, d.open().bindings(profile("B")).binding(PhoneHidAction.MODE))
        assertEquals(4, d.writes)
    }

    @Test fun migrationGroupsEveryRecordedIdentityAndRetainsExactLegacyBytes() {
        val a = binding("A", gesture = PhoneHidGesture.LONG)
        val b = binding("B", "touch", 400, PhoneHidGesture.clicks(2))
        val android = a.copy(button = a.button.copy(device = PhoneHidDeviceIdentity("actual Android descriptor", 98, 76)))
        val legacy = PhoneHidBindings.encode(mapOf(PhoneHidAction.MODE to a, PhoneHidAction.BACK to b, PhoneHidAction.HOME to android))
        val d = Disk(legacy = legacy); val migrated = d.open()
        assertNull(migrated.error); assertEquals(1, d.writes); assertEquals(legacy, d.legacy)
        assertEquals(a, migrated.bindings(profile("A")).binding(PhoneHidAction.MODE))
        assertNull(migrated.bindings(profile("A")).binding(PhoneHidAction.BACK))
        assertEquals(b, migrated.bindings(profile("B")).binding(PhoneHidAction.BACK))
        val androidKey = PhoneHidPinRuntime.profileForDevice(android.button.device)
        assertEquals(android, migrated.bindings(androidKey).binding(PhoneHidAction.HOME))
        assertNull(migrated.bindings(profile("currently pinned unrelated device")).binding(PhoneHidAction.HOME))
        assertEquals(androidKey, PhoneHidPinRuntime.profileForDevice(android.button.device.copy()))
        assertNotEquals(androidKey, PhoneHidPinRuntime.profileForDevice(android.button.device.copy(vendorId = 99)))
        assertTrue(migrated.bindings(profile("B")).resetAll())
        assertEquals(legacy, d.legacy)
        val writes = d.writes
        assertNull(d.open().bindings(profile("B")).binding(PhoneHidAction.BACK)) // Never remigrate reset data.
        assertEquals(writes, d.writes)
        assertEquals(a, d.open().bindings(profile("A")).binding(PhoneHidAction.MODE))
    }

    @Test fun versionOneMigrationPreservesRecordedDeviceAndNeverRewritesOriginal() {
        val a = binding("A"); val b = binding("B")
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            fun string(value: String) { val data = value.toByteArray(Charsets.UTF_8); out.writeInt(data.size); out.write(data) }
            out.writeInt(2)
            listOf("ANCHOR" to a, "NAVIGATION" to b).forEach { (name, v) ->
                string(name); string(v.button.device.descriptor); out.writeInt(v.button.device.vendorId)
                out.writeInt(v.button.device.productId); out.writeInt(v.button.keyCode); out.writeInt(v.button.scanCode)
                string(v.deviceName); string(v.keyLabel)
            }
        }
        val old = PhoneHidBindings.LEGACY_PREFIX + Base64.getEncoder().encodeToString(bytes.toByteArray())
        val d = Disk(legacy = old); val migrated = d.open()
        assertNull(migrated.error); assertEquals(old, d.legacy)
        assertEquals(a, migrated.bindings(profile("A")).binding(PhoneHidAction.MODE))
        assertEquals(b, migrated.bindings(profile("B")).binding(PhoneHidAction.BACK))
        assertNull(migrated.bindings(profile("A")).binding(PhoneHidAction.HOME))
    }

    @Test fun migrationAndWritesReportFalseAndThrowingIoWithoutLosingMapsOrLegacy() {
        val a = binding("A"); val b = binding("B")
        for (throws in listOf(false, true)) {
            val d = Disk(legacy = PhoneHidBindings.encode(mapOf(PhoneHidAction.TAP to a)))
            val legacy = d.legacy; d.writable = false; d.throwing = throws
            val s = d.open()
            assertTrue(s.error!!.startsWith("Migrated")); assertNull(d.record); assertEquals(legacy, d.legacy)
            assertEquals(a, s.bindings(profile("A")).binding(PhoneHidAction.TAP))
            assertFalse(s.bindings(profile("B")).assign(PhoneHidAction.TAP, b))
            assertNull(s.bindings(profile("B")).binding(PhoneHidAction.TAP))
            d.writable = true; d.throwing = false
            assertTrue(s.bindings(profile("B")).assign(PhoneHidAction.TAP, b))
            assertNull(s.error); val saved = d.record
            d.writable = false
            assertFalse(s.bindings(profile("A")).resetAll())
            assertEquals(saved, d.record); assertEquals(legacy, d.legacy)
            assertEquals(a, d.open().bindings(profile("A")).binding(PhoneHidAction.TAP))
            assertEquals(b, d.open().bindings(profile("B")).binding(PhoneHidAction.TAP))
        }
    }

    @Test fun malformedProfilesAndAmbiguousNativeDescriptorsStayRecoverableAndReportFailure() {
        for (legacy in listOf("bad legacy bytes", PhoneHidBindings.encode(mapOf(PhoneHidAction.TAP to
            binding("A").copy(button = binding("A").button.copy(device = PhoneHidDeviceIdentity(PhoneHidPinRuntime.NATIVE_PREFIX + "[1,2]", 12, 34))))))) {
            val d = Disk(legacy = legacy); assertNotNull(d.open().error)
            assertEquals(legacy, d.legacy); assertEquals(0, d.writes); assertNull(d.record)
        }
        val good = Disk(); good.open().bindings(profile("A")).assign(PhoneHidAction.TAP, binding("A"))
        for (record in listOf("bad", good.record + "suffix")) {
            val d = Disk(record = record); assertNotNull(d.open().error)
            assertEquals(record, d.record); assertEquals(0, d.writes)
        }
        val missing = PhoneHidDeviceIdentity("", 0, 0)
        assertEquals(PhoneHidPinRuntime.profileForDevice(missing), PhoneHidPinRuntime.profileForDevice(missing.copy()))
        assertNotEquals(profile("A"), PhoneHidPinRuntime.profileForDevice(missing))
    }

    private class Rig(val store: PhoneHidProfiles) {
        var now = 1000L
        val jobs = mutableListOf<Pair<() -> Unit, () -> Unit>>()
        val timers = mutableListOf<Pair<Long, () -> Unit>>()
        val calls = mutableListOf<Pair<String, PhoneHidRoute>>()
        var onCancel: () -> Unit = {}
        private var next = 0
        val controller = PhoneHidController(store.bindings(null), object : PhoneHidRoutes {
            override fun capture(down: PhoneHidEdge) = PhoneHidRoute(++next).also { calls.add("capture" to it) }
            override fun submit(route: PhoneHidRoute, edge: PhoneHidEdge) { calls.add("submit" to route) }
            override fun cancel(route: PhoneHidRoute, eventTimeMs: Long) { calls.add("cancel" to route); onCancel() }
        }, { op, done -> jobs.add(op to done) }, { now }, schedule = { at, action ->
            timers.add(at to action); {} // Deliberately deliver canceled callbacks too.
        }, profileBindings = store::bindings)
        fun event(device: String, at: Long, phase: PhoneHidPhase = PhoneHidPhase.DOWN, down: Long = at,
                  attachment: Int = if (device == "A") -1 else -2) = PhoneHidController.Event(
            attachment, PhoneHidBinding(PhoneHidButton(PhoneHidDeviceIdentity(
                PhoneHidPinRuntime.NATIVE_PREFIX + JSONArray().put(device).put("keys"), 12, 34), 30, 0),
                "Remote $device", "Key 30"), phase, down, at)
        fun flush() { while (jobs.isNotEmpty()) { val (op, done) = jobs.removeAt(0); op(); done() } }
        fun tick(to: Long) {
            now = to
            val due = timers.filter { it.first <= to }; timers.removeAll(due.toSet())
            due.forEach { it.second() }
        }
    }

    @Test fun queuedLearningSaveAndResetAlwaysTargetOriginalProfileAcrossSwitches() {
        val d = Disk(); val s = d.open(); val r = Rig(s); val c = r.controller
        s.bindings(profile("B")).assign(PhoneHidAction.MODE, binding("B", gesture = PhoneHidGesture.LONG))
        c.selectProfile(profile("A")); c.openPage(); c.select(PhoneHidAction.MODE)
        assertTrue(c.accept(r.event("A", 1000)))
        assertTrue(c.accept(r.event("A", 1010, PhoneHidPhase.UP, 1000)))
        r.tick(1510); assertEquals(1, c.saving)
        c.selectProfile(profile("B")); r.flush()
        assertEquals(PhoneHidGesture.LONG, c.bindings.binding(PhoneHidAction.MODE)!!.gesture)
        assertEquals(PhoneHidGesture.SINGLE, s.bindings(profile("A")).binding(PhoneHidAction.MODE)!!.gesture)
        c.selectProfile(profile("A")); c.resetAll(); c.selectProfile(profile("B")); r.flush()
        assertNull(s.bindings(profile("A")).binding(PhoneHidAction.MODE))
        assertEquals(PhoneHidGesture.LONG, c.bindings.binding(PhoneHidAction.MODE)!!.gesture)
        assertEquals(PhoneHidGesture.LONG, d.open().bindings(profile("B")).binding(PhoneHidAction.MODE)!!.gesture)
    }

    @Test fun pendingLearningTimersAndHeldOrReleasedRoutesRetireBeforeReplacement() {
        for (action in listOf(PhoneHidAction.TAP, PhoneHidAction.SCROLL_DOWN, PhoneHidAction.MODE)) {
            for (released in listOf(false, true)) {
                val s = Disk().open(); val r = Rig(s); val c = r.controller
                s.bindings(profile("A")).assign(action, binding("A"))
                s.bindings(profile("B")).assign(action, binding("B"))
                c.selectProfile(profile("A")); assertTrue(c.accept(r.event("A", 1000)))
                val old = r.calls.single().second
                if (released) assertTrue(c.accept(r.event("A", 1010, PhoneHidPhase.UP, 1000)))
                r.onCancel = { assertEquals(profile("A"), c.profileKey) }
                c.selectProfile(profile("B"))
                val cancelExpected = !released || action == PhoneHidAction.MODE
                assertEquals(cancelExpected, r.calls.any { it.first == "cancel" && it.second === old })
                val count = r.calls.size
                assertTrue(c.accept(r.event("A", 1020, PhoneHidPhase.UP, 1000)))
                assertEquals(count, r.calls.size)
                assertTrue(c.accept(r.event("B", 2000)))
                assertNotSame(old, r.calls.last().second)
            }
        }
        val s = Disk().open(); val r = Rig(s); val c = r.controller
        c.selectProfile(profile("A")); c.openPage(); c.select(PhoneHidAction.MODE)
        c.accept(r.event("A", 1000)); c.accept(r.event("A", 1010, PhoneHidPhase.UP, 1000))
        c.selectProfile(profile("B")); c.select(PhoneHidAction.MODE)
        r.tick(1600); r.flush()
        assertNull(s.bindings(profile("A")).binding(PhoneHidAction.MODE))
        assertNull(s.bindings(profile("B")).binding(PhoneHidAction.MODE))
        c.accept(r.event("B", 2000)); c.accept(r.event("B", 2010, PhoneHidPhase.UP, 2000))
        r.tick(2510); r.flush()
        assertEquals(binding("B"), c.bindings.binding(PhoneHidAction.MODE))
    }

    @Test fun cancelReentryCannotAdmitOldInputOrReplaceANewerProfileTransition() {
        val s = Disk().open(); val r = Rig(s); val c = r.controller
        for (device in listOf("A", "B", "C")) s.bindings(profile(device)).assign(PhoneHidAction.TAP, binding(device))
        c.selectProfile(profile("A")); c.accept(r.event("A", 1000))
        r.onCancel = {
            r.onCancel = {}
            assertTrue(c.accept(r.event("A", 1100))) // Cancel callback cannot create a successor route.
            c.selectProfile(profile("C"))
        }
        c.selectProfile(profile("B"))
        assertEquals(profile("C"), c.profileKey)
        assertEquals(listOf("capture", "cancel"), r.calls.map { it.first })
        assertTrue(c.accept(r.event("A", 1120, PhoneHidPhase.UP, 1100)))
        assertTrue(c.accept(r.event("C", 2000)))
        assertEquals(listOf("capture", "cancel", "capture"), r.calls.map { it.first })
        assertEquals(binding("C"), c.bindings.binding(PhoneHidAction.TAP))
    }

    private class Backend : IHidCapture {
        var generation = 0L; var key: String? = null; var active = false
        lateinit var listener: IHidCaptureListener
        var pins = mutableListOf<String>()
        override fun attach(listener: IHidCaptureListener) { this.listener = listener }
        override fun state(): String = JSONObject().put("generation", generation).put("deviceKey", key)
            .put("intercepted", active).put("phase", if (active) "ACTIVE" else "IDLE").put("collectionCount", if (active) 2 else 0).toString()
        override fun inventory(): String = JSONObject().put("devices", JSONArray().apply {
            listOf("A", "B").forEach { put(JSONObject().put("key", it).put("name", "Remote $it")
                .put("vendorId", 12).put("productId", 34).put("collections", 2).put("hasKeys", true)) }
        }).toString()
        override fun pin(key: String): String { generation++; this.key = key; active = true; pins.add(key); return state() }
        override fun unpin(): String { generation++; key = null; active = false; return state() }
        fun input(device: String, at: Long, phase: PhoneHidPhase = PhoneHidPhase.DOWN, down: Long = at,
                  epoch: Long = generation): String = JSONObject().put("deviceKey", device).put("generation", epoch)
            .put("sourceId", 91).put("collectionKey", "keys").put("vendorId", 12).put("productId", 34)
            .put("code", 30).put("label", "Key 30").put("deviceName", "Remote $device")
            .put("phase", phase.name).put("downTimeMs", down).put("eventTimeMs", at)
            .put("repeatCount", 0).put("pressId", down).toString()
    }
    private fun executor(pin: PhoneHidPinRuntime): ExecutorService {
        val field = PhoneHidPinRuntime::class.java.getDeclaredField("lane"); field.isAccessible = true
        return field.get(pin) as ExecutorService
    }
    private fun pump(pin: PhoneHidPinRuntime) {
        repeat(8) { executor(pin).submit {}.get(2, TimeUnit.SECONDS); MainQueue.drain() }
    }
    private fun newPin(context: Context, rig: Rig) = PhoneHidPinRuntime(context, rig.controller, {}, {})

    @Test fun actualPinSwitchUnpinResetRestartReconnectAndStaleCallbacksPreserveProfilesAndPrefs() {
        val context = Context()
        val prefs = context.getSharedPreferences("phone_hid_bindings", 0)
        val legacy = PhoneHidBindings.encode(mapOf(PhoneHidAction.TAP to binding("A")))
        prefs.values["bindings"] = legacy; prefs.values["unrelated"] = "keep"
        context.getSharedPreferences("pairing", 0).values["identity"] = "keep pairing"
        fun openStore() = PhoneHidProfiles({ prefs.getString("profiles", null) }, { prefs.getString("bindings", null) },
            { prefs.edit().putString("profiles", it).commit() }, PhoneHidPinRuntime::profileForDevice)
        val store = openStore(); store.bindings(profile("B")).assign(PhoneHidAction.MODE, binding("B", gesture = PhoneHidGesture.LONG))
        val rig = Rig(store); val pin = newPin(context, rig); val backend = Backend(); Shizuku.binder = backend
        try {
            pin.restore(); pump(pin); pin.refresh(); pump(pin)
            pin.select("A"); pin.pinSelected(); pin.serviceStarted(PhoneHidPinService()); pump(pin)
            assertTrue(pin.state.intercepted); assertEquals(profile("A"), rig.controller.profileKey)
            backend.listener.onInput(backend.input("A", 1000)); pump(pin)
            assertEquals("capture", rig.calls.last().first)
            val oldRoute = rig.calls.last().second
            val oldGeneration = backend.generation; val oldListener = backend.listener
            PhoneHidRuntime.fixture = PhoneHidRuntime(rig.controller, pin, store)
            val notificationOwner = PhoneHidPinService()
            notificationOwner.show("Pinned A")
            val notificationAction = android.app.Notifications.manager.latest!!.actions.single()
            assertEquals("Unpin", notificationAction.text)
            notificationOwner.onStartCommand(notificationAction.pending.intent, 0, 1); pump(pin)
            assertFalse(pin.state.intercepted)
            assertTrue(rig.calls.any { it.first == "cancel" && it.second === oldRoute })
            assertEquals(binding("A"), store.bindings(profile("A")).binding(PhoneHidAction.TAP))
            pin.select("B"); pin.pinSelected(); pump(pin)
            assertTrue(pin.state.intercepted); assertEquals("B", pin.pinnedKey)
            assertEquals(profile("B"), rig.controller.profileKey)
            val count = rig.calls.size
            oldListener.onInput(backend.input("A", 1100, PhoneHidPhase.UP, 1000, oldGeneration)); pump(pin)
            assertEquals(count, rig.calls.size)
            rig.controller.openPage(); rig.controller.select(PhoneHidAction.MODE)
            backend.listener.onInput(backend.input("B", 2000)); pump(pin)
            backend.listener.onInput(backend.input("B", 2800, PhoneHidPhase.UP, 2000)); pump(pin); rig.flush()
            assertEquals(PhoneHidGesture.LONG, store.bindings(profile("B")).binding(PhoneHidAction.MODE)!!.gesture)
            assertEquals(binding("A"), store.bindings(profile("A")).binding(PhoneHidAction.TAP))
            pin.resetSelectedProfile(); rig.flush()
            assertNull(store.bindings(profile("B")).binding(PhoneHidAction.MODE))
            assertEquals(binding("A"), store.bindings(profile("A")).binding(PhoneHidAction.TAP))
            assertEquals("B", context.getSharedPreferences("phone_hid_pin", 0).getString("device_key", null))
            assertEquals(legacy, prefs.getString("bindings", null)); assertEquals("keep", prefs.getString("unrelated", null))
            assertEquals("keep pairing", context.getSharedPreferences("pairing", 0).getString("identity", null))
        } finally { executor(pin).shutdownNow() }
        val restartStore = openStore(); val restartRig = Rig(restartStore); val restarted = newPin(context, restartRig)
        val nextBackend = Backend(); Shizuku.binder = nextBackend
        try {
            restarted.restore(); pump(restarted); restarted.serviceStarted(PhoneHidPinService()); pump(restarted)
            assertEquals("B", restarted.pinnedKey); assertTrue(restarted.state.intercepted)
            assertEquals(profile("B"), restartRig.controller.profileKey)
            assertNull(restartRig.controller.bindings.binding(PhoneHidAction.MODE))
            assertEquals(binding("A"), restartStore.bindings(profile("A")).binding(PhoneHidAction.TAP))
            // Actual disconnect callback/rebind retries exactly the saved single pin.
            Shizuku.connections.last().onServiceDisconnected(android.content.ComponentName(context, PhoneHidPinService::class.java))
            pump(restarted); assertFalse(restarted.state.intercepted)
            restarted.refresh(); pump(restarted); assertTrue(restarted.state.intercepted)
            assertEquals(listOf("B", "B"), nextBackend.pins)
        } finally { executor(restarted).shutdownNow() }
    }

    @Test fun actualPinFailuresKeepSavedIntentAndProfilesAndPanelSeparatesActions() {
        for (throws in listOf(false, true)) {
            val context = Context(); val store = Disk().open(); val r = Rig(store)
            store.bindings(profile("A")).assign(PhoneHidAction.TAP, binding("A"))
            val prefs = context.getSharedPreferences("phone_hid_pin", 0)
            val pin = newPin(context, r); val backend = Backend(); Shizuku.binder = backend
            try {
                pin.restore(); pump(pin); pin.refresh(); pump(pin); pin.select("A")
                prefs.writable = false; prefs.throwing = throws
                pin.pinSelected(); pump(pin)
                assertEquals("FAILED", pin.state.phase); assertNotNull(pin.state.error); assertNull(prefs.getString("device_key", null))
                assertTrue(backend.pins.isEmpty())
                prefs.writable = true; prefs.throwing = false
                pin.pinSelected(); pin.serviceStarted(PhoneHidPinService()); pump(pin)
                assertTrue(pin.state.intercepted)
                val panel = PhoneHidPanel(context, PhoneHidRuntime(r.controller, pin, store)) {}
                assertTrue(panel.children.filterIsInstance<TextView>().any { it.text == "Selected profile: Remote A" })
                val buttons = panel.children.filterIsInstance<Button>()
                assertEquals(1, buttons.count { it.text == "Unpin device" })
                assertEquals(1, buttons.count { it.text == "Reset selected profile" })
                prefs.writable = false; prefs.throwing = throws
                buttons.single { it.text == "Unpin device" }.performClick(); pump(pin)
                assertEquals("FAILED", pin.state.phase); assertNotNull(pin.state.error)
                assertEquals("A", prefs.getString("device_key", null))
                assertFalse(pin.state.intercepted)
                assertEquals(binding("A"), store.bindings(profile("A")).binding(PhoneHidAction.TAP))
            } finally { executor(pin).shutdownNow() }
        }
    }
}
