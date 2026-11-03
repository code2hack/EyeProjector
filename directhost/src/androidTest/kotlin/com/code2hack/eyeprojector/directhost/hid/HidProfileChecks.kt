package com.code2hack.eyeprojector.directhost.hid

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Process
import com.code2hack.eyeprojector.protocol.PhoneHidAction
import com.code2hack.eyeprojector.protocol.PhoneHidGesture
import org.json.JSONArray

/** Real Android preference/codec checks with synthetic identities; no pin owner or input service is created. */
internal object HidProfileChecks {
    private const val A = "instrumentation-synthetic-A"
    private const val B = "instrumentation-synthetic-B"
    private const val PREFIX = "instrumentation_hid_profiles_"
    private const val UNRELATED = "fixture unrelated bytes: unchanged"
    private const val PAIRING = "fixture pairing bytes: unchanged"

    private fun fixture(base: Context) = object : ContextWrapper(base) {
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            super.getSharedPreferences(PREFIX + name, mode)
    }

    private fun binding(device: String, gesture: PhoneHidGesture = PhoneHidGesture.SINGLE,
                        collection: String = "keys", code: Int = 30) = PhoneHidBinding(
        PhoneHidButton(PhoneHidDeviceIdentity(PhoneHidPinRuntime.NATIVE_PREFIX +
            JSONArray().put(device).put(collection).toString(), 12, 34), code, 0),
        "Synthetic $device", "Fixture key $code", gesture)

    private fun legacy() = PhoneHidBindings.encode(mapOf(
        PhoneHidAction.MODE to binding(A),
        PhoneHidAction.BACK to binding(B, PhoneHidGesture.LONG),
        PhoneHidAction.TAP to binding(A, collection = "touch", code = 900),
    ))

    private fun open(prefs: SharedPreferences, readOnly: Boolean = false) = PhoneHidProfiles(
        { prefs.getString("profiles", null) },
        { check(!readOnly) { "Reopen must use profiles rather than remigrate legacy" }; prefs.getString("bindings", null) },
        { record ->
            check(!readOnly) { "Reopen must not write" }
            prefs.edit().putString("profiles", record).commit()
        }, PhoneHidPinRuntime::profileForDevice)

    private fun preserved(context: Context) {
        val prefs = context.getSharedPreferences("phone_hid_bindings", Context.MODE_PRIVATE)
        check(prefs.getString("bindings", null) == legacy()) { "Legacy record changed" }
        check(prefs.getString("unrelated", null) == UNRELATED)
        val pin = context.getSharedPreferences("phone_hid_pin", Context.MODE_PRIVATE)
        check(pin.all == mapOf("device_key" to A, "device_name" to "Synthetic A"))
        check(context.getSharedPreferences("pairing", Context.MODE_PRIVATE).all == mapOf("identity" to PAIRING))
    }

    private fun write(context: Context) {
        val prefs = context.getSharedPreferences("phone_hid_bindings", Context.MODE_PRIVATE)
        check(prefs.edit().clear().putString("bindings", legacy()).putString("unrelated", UNRELATED).commit())
        check(context.getSharedPreferences("phone_hid_pin", Context.MODE_PRIVATE).edit().clear()
            .putString("device_key", A).putString("device_name", "Synthetic A").commit())
        check(context.getSharedPreferences("pairing", Context.MODE_PRIVATE).edit().clear()
            .putString("identity", PAIRING).commit())
        val original = prefs.all.toMap()
        val profiles = open(prefs)
        check(profiles.error == null)
        check(prefs.getString("profiles", null) != null)
        val aKey = PhoneHidPinRuntime.profileForDevice(binding(A).button.device)
        val bKey = PhoneHidPinRuntime.profileForDevice(binding(B).button.device)
        check(aKey == PhoneHidPinRuntime.profileForPin(A) && bKey == PhoneHidPinRuntime.profileForPin(B))
        check(aKey != bKey)
        // Handles select recorded profiles, without activating fake devices in the actual pin runtime.
        val a = profiles.bindings(aKey)
        val b = profiles.bindings(bKey)
        check(a.binding(PhoneHidAction.MODE) == binding(A))
        check(a.binding(PhoneHidAction.BACK) == null)
        check(b.binding(PhoneHidAction.BACK) == binding(B, PhoneHidGesture.LONG))
        check(b.binding(PhoneHidAction.MODE) == null)
        check(a.binding(PhoneHidAction.TAP) == binding(A, collection = "touch", code = 900))
        check(b.assign(PhoneHidAction.MODE, binding(B, PhoneHidGesture.clicks(2), code = 31)))
        check(a.binding(PhoneHidAction.MODE)?.gesture == PhoneHidGesture.SINGLE)
        check(b.binding(PhoneHidAction.MODE)?.gesture == PhoneHidGesture.clicks(2))
        val saved = open(prefs, readOnly = true)
        check(saved.error == null)
        check(saved.bindings(aKey).binding(PhoneHidAction.MODE) == binding(A))
        check(saved.bindings(bKey).binding(PhoneHidAction.MODE) == binding(B, PhoneHidGesture.clicks(2), code = 31))
        check(b.resetAll())
        check(a.binding(PhoneHidAction.MODE) == binding(A))
        check(PhoneHidAction.entries.all { b.binding(it) == null })
        check(prefs.all.filterKeys { it != "profiles" } == original)
        preserved(context)
    }

    private fun read(context: Context) {
        val prefs = context.getSharedPreferences("phone_hid_bindings", Context.MODE_PRIVATE)
        val before = prefs.all.toMap()
        val profiles = open(prefs, readOnly = true)
        check(profiles.error == null)
        val a = profiles.bindings(PhoneHidPinRuntime.profileForPin(A))
        val b = profiles.bindings(PhoneHidPinRuntime.profileForPin(B))
        check(a.binding(PhoneHidAction.MODE) == binding(A))
        check(a.binding(PhoneHidAction.TAP) == binding(A, collection = "touch", code = 900))
        check(PhoneHidAction.entries.all { b.binding(it) == null }) // Reset must not remigrate B's old Back.
        check(prefs.all == before)
        preserved(context)
    }

    fun run(base: Context, arguments: Bundle, result: Bundle) {
        val context = fixture(base)
        val phase = arguments.getString("phase", "all")
        when (phase) {
            "write" -> write(context)
            "read" -> read(context)
            "all" -> { write(context); read(context) }
            else -> error("Unknown hid-profiles phase: $phase")
        }
        result.putString("hidProfileScope", "Android SharedPreferences and actual profile/binding codecs; synthetic native device identities; no pin, raw input or radio proof")
        result.putString("hidProfilePhase", phase)
        result.putString("fixturePreferencePrefix", PREFIX)
        result.putInt("fixturePid", Process.myPid())
        result.putBoolean("physicalHidPass", false)
    }
}
