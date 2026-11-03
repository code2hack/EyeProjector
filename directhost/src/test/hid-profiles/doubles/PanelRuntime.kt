package com.code2hack.eyeprojector.directhost.hid

import java.io.Closeable

/** Panel composition only. Production pin/controller/store below remain actual source. */
internal class PhoneHidRuntime(val controller: PhoneHidController, val pin: PhoneHidPinRuntime,
    private val profiles: PhoneHidProfiles) {
    val profileError get() = profiles.error
    val androidProfiles = emptyList<Pair<String, String>>()
    val androidProfileKey: String? = null
    val androidControlsActive = false
    val selectedProfileName get() = pin.selectedName
    fun selectAndroidProfile(key: String) = Unit
    fun resetSelectedProfile() = pin.resetSelectedProfile()
    fun openSettings() = true
    fun userControl() = true
    fun observe(callback: () -> Unit) = Closeable {}
    fun refresh() = pin.refresh()
    companion object {
        lateinit var fixture: PhoneHidRuntime
        fun get(context: android.content.Context) = fixture
    }
}
