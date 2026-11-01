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
        // Existing seven source actions plus five current57 PhoneWindowManager senders, firmware1.28.016.
        // Matching services.jar SHA-256: ec3ae29ad1c39c69a2b80d29b7c98979ec6b5aec70ca947db3195957a8341603.
        // Current classes2.dex SHA-256: 71738098b098c89b2718410734fac4635d2fd6780d50e48e4d3ce9be01f6b49b.
        // The five additions are unordered; names do not establish physical gestures or suppression.
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
            "com.android.action.ACTION_TWO_FINGER_SWIPE_BACK",
            "com.android.action.ACTION_TWO_FINGER_SWIPE_FORWARD",
        )
    }
}
