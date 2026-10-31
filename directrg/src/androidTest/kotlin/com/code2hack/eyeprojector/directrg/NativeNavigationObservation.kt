package com.code2hack.eyeprojector.directrg

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock

/** Opt-in test metadata only. Never aborts a broadcast, changes its result, or reads extras. */
internal class NativeNavigationObservation(
    private val context: Context,
    private val queue: NativeInputObservationQueue,
) {
    private var registrationAttempted = false
    @Volatile private var accepting = false
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!accepting) return
            queue.capture("broadcast", null, SystemClock.uptimeMillis(), SystemClock.elapsedRealtimeNanos()) {
                mapOf("actionName" to intent.action, "intentFlags" to intent.flags,
                    "ordered" to isOrderedBroadcast, "initialSticky" to isInitialStickyBroadcast,
                    "resultCodeAtReceipt" to if (isOrderedBroadcast) resultCode else null,
                    "observerAborted" to false, "observerChangedResult" to false,
                    "deliveryScope" to "Subscribed source actions only; missing receipt is inconclusive; earlier abort may shadow ordered channels; unordered receipt does not establish interception")
            }
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag") // Same API32/API33 split as the existing receivers.
    fun start() {
        val filter = IntentFilter().apply { ACTIONS.forEach(::addAction); priority = Int.MAX_VALUE }
        registrationAttempted = true
        accepting = true
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        else {
            @Suppress("DEPRECATION")
            context.registerReceiver(receiver, filter)
        }
    }

    fun sample() {
        queue.capture("interactive_sample", null, SystemClock.uptimeMillis(), SystemClock.elapsedRealtimeNanos()) {
            val start = SystemClock.elapsedRealtimeNanos()
            val interactive = context.getSystemService(PowerManager::class.java).isInteractive
            mapOf("interactive" to interactive, "readStartElapsedNs" to start,
                "readEndElapsedNs" to SystemClock.elapsedRealtimeNanos(),
                "scope" to "Sampled PowerManager state; not optical output, keyguard or firmware action time")
        }
    }

    fun stop() {
        accepting = false
        if (registrationAttempted) {
            context.unregisterReceiver(receiver)
            registrationAttempted = false
        }
    }

    companion object {
        // Existing NativeFunctionInput REPORTS and NativePadLongTouch ACTION, plus three policy10 senders.
        // Matching services.jar SHA-256: 6bdadf1db66ed93d1d065a4669218766fc6082563fd5ab77ca50d64a4612b1ff.
        // PhoneWindowManager sends the three additions unordered; names do not establish physical gestures.
        // No speculative native navigation actions or wildcard broadcast discovery.
        val ACTIONS = listOf(
            "com.android.action.ACTION_SPRITE_BUTTON_DOWN",
            "com.android.action.ACTION_SPRITE_BUTTON_UP",
            "com.android.action.ACTION_SPRITE_BUTTON_CLICK",
            "com.android.action.ACTION_SPRITE_BUTTON_DOUBLE_CLICK",
            "com.android.action.ACTION_BOLON_PAIRING",
            "com.android.action.ACTION_SPRITE_BUTTON_LONG_PRESS",
            "com.android.action.ACTION_AI_START",
            "com.android.action.ACTION_SETTINGS_KEY",
            "com.android.action.ACTION_TWO_FINGER_SINGLE_TAP",
            "com.android.action.ACTION_TWO_FINGER_DOUBLE_TAP",
        )
    }
}
