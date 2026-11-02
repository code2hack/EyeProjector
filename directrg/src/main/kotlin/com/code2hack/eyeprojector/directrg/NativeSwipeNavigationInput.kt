package com.code2hack.eyeprojector.directrg

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build

/** Distinct firmware swipe broadcasts; ordinary pad keys and Function keep their own owners. */
internal class NativeSwipeNavigationInput(
    private val context: Context,
    private val dispatch: (ContextualNavigationAction) -> Unit,
) {
    private inner class Registration : BroadcastReceiver() {
        var ready = false

        override fun onReceive(context: Context, intent: Intent) {
            if (registration !== this || !ready) return
            val action = when (intent.action) {
                BACK -> ContextualNavigationAction.BACK
                FORWARD -> ContextualNavigationAction.HOME
                else -> return
            }
            // The current firmware sender is unordered and supplies no gesture ID/payload.
            // This callback cannot abort brightness handling or correlate other input sources.
            dispatch(action)
        }
    }

    private var registration: Registration? = null

    @SuppressLint("UnspecifiedRegisterReceiverFlag") // API32 has no exported flag.
    fun start() {
        if (registration != null) return
        val original = Registration()
        registration = original
        val filter = IntentFilter().apply { addAction(BACK); addAction(FORWARD) }
        try {
            if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(original, filter, Context.RECEIVER_EXPORTED)
            else {
                @Suppress("DEPRECATION")
                context.registerReceiver(original, filter)
            }
            original.ready = true
        } catch (failure: RuntimeException) {
            if (registration === original) registration = null
            // A failed framework registration may still have installed its local receiver.
            try { context.unregisterReceiver(original) }
            catch (cleanup: RuntimeException) { failure.addSuppressed(cleanup) }
            throw failure
        }
    }

    fun stop() {
        val original = registration ?: return
        registration = null // Invalidate queued delivery before framework cleanup or a restart.
        context.unregisterReceiver(original)
    }

    private companion object {
        const val BACK = "com.android.action.ACTION_TWO_FINGER_SWIPE_BACK"
        const val FORWARD = "com.android.action.ACTION_TWO_FINGER_SWIPE_FORWARD"
    }
}
