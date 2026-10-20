package com.code2hack.eyeprojector.directrg

import android.app.Activity
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.SystemClock

/** Existing hardware identity mapping, including documented swipe companion keys. */
internal fun nativePadKey(deviceName: String?, source: Int, keyCode: Int): PadGestureRecognizer.Key? {
    if (deviceName != "ROKID,PSOC-TP-R" || source and 0x101 != 0x101) return null
    return when (keyCode) {
        83 -> PadGestureRecognizer.Key.CONTACT
        66 -> PadGestureRecognizer.Key.TAP
        291, 4 -> PadGestureRecognizer.Key.DOUBLE
        22 -> PadGestureRecognizer.Key.FORWARD
        21 -> PadGestureRecognizer.Key.BACKWARD
        292 -> PadGestureRecognizer.Key.FORWARD_ALIAS
        293 -> PadGestureRecognizer.Key.BACKWARD_ALIAS
        20, 19 -> PadGestureRecognizer.Key.SWIPE_TAIL
        else -> null
    }
}

/** Foreground long classification cancels an ambiguous pulse; it does not invent release. */
internal class NativePadLongTouch(private val context: Context, private val classified: (Long) -> Unit) {
    private var registered = false
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION) return
            if (isOrderedBroadcast && intent.flags and Intent.FLAG_RECEIVER_NO_ABORT == 0) {
                resultCode = Activity.RESULT_CANCELED
                abortBroadcast()
            }
            classified(SystemClock.uptimeMillis())
        }
    }
    @SuppressLint("UnspecifiedRegisterReceiverFlag") // API32 has no exported flag; API33+ uses RECEIVER_EXPORTED below.
    fun start() {
        if (registered) return
        val filter = IntentFilter(ACTION).apply { priority = Int.MAX_VALUE }
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        else {
            @Suppress("DEPRECATION")
            context.registerReceiver(receiver, filter)
        }
        registered = true
    }
    fun stop() {
        if (!registered) return
        context.unregisterReceiver(receiver); registered = false
    }
    companion object { const val ACTION = "com.android.action.ACTION_AI_START" }
}
