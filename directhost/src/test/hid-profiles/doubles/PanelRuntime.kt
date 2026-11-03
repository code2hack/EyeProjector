package com.code2hack.eyeprojector.directhost.hid

import java.io.Closeable

/** Panel composition only. Production pin/controller/store below remain actual source. */
internal class PhoneHidRuntime(val controller: PhoneHidController, val pin: PhoneHidPinRuntime,
    private val profiles: PhoneHidProfiles) {
    val profileError get() = profiles.error
    fun userControl() = true
    fun observe(callback: () -> Unit) = Closeable {}
    fun refresh() = pin.refresh()
    companion object {
        lateinit var fixture: PhoneHidRuntime
        fun get(context: android.content.Context) = fixture
    }
}
