package com.code2hack.eyeprojector.directrg

import android.annotation.SuppressLint
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/** Foreground Function interception; ordinary touchpad/AI/key reports have independent owners. */
internal class NativeFunctionInput(
    private val context: Context,
    currentContext: () -> Any?,
    dispatch: (FunctionGestureRecognizer.Action) -> Unit,
    now: () -> Long = SystemClock::uptimeMillis,
    schedule: (Long, () -> Unit) -> (() -> Unit) = handlerSchedule(),
    timing: FunctionGestureRecognizer.Timing = FunctionGestureRecognizer.Timing(),
) {
    private var registered = false
    private val recognizer = FunctionGestureRecognizer(now, { if (registered) currentContext() else null }, schedule, dispatch, timing)
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!registered) return
            val report = REPORTS[intent.action] ?: return
            if (isOrderedBroadcast && intent.flags and Intent.FLAG_RECEIVER_NO_ABORT == 0) {
                resultCode = Activity.RESULT_CANCELED
                abortBroadcast()
            }
            recognizer.accept(report)
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag") // API32 has no exported flag.
    fun start() {
        if (registered) return
        val filter = IntentFilter().apply { REPORTS.keys.forEach(::addAction); priority = Int.MAX_VALUE }
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        else {
            @Suppress("DEPRECATION")
            context.registerReceiver(receiver, filter)
        }
        registered = true
    }

    fun cancel() = recognizer.cancel()

    fun stop() {
        recognizer.cancel()
        if (!registered) return
        registered = false
        context.unregisterReceiver(receiver)
    }

    companion object {
        private val REPORTS = mapOf(
            "com.android.action.ACTION_SPRITE_BUTTON_DOWN" to FunctionGestureRecognizer.Report.DOWN,
            "com.android.action.ACTION_SPRITE_BUTTON_UP" to FunctionGestureRecognizer.Report.UP,
            "com.android.action.ACTION_SPRITE_BUTTON_CLICK" to FunctionGestureRecognizer.Report.CLICK,
            "com.android.action.ACTION_SPRITE_BUTTON_DOUBLE_CLICK" to FunctionGestureRecognizer.Report.DOUBLE,
            "com.android.action.ACTION_BOLON_PAIRING" to FunctionGestureRecognizer.Report.TRIPLE,
            "com.android.action.ACTION_SPRITE_BUTTON_LONG_PRESS" to FunctionGestureRecognizer.Report.LONG,
        )

        private fun handlerSchedule(): (Long, () -> Unit) -> (() -> Unit) {
            val handler = Handler(Looper.getMainLooper())
            return { deadline, action ->
                val task = Runnable(action)
                handler.postAtTime(task, deadline)
                val cancel: () -> Unit = { handler.removeCallbacks(task) }
                cancel
            }
        }
    }
}
