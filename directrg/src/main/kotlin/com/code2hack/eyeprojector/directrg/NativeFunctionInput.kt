package com.code2hack.eyeprojector.directrg

import android.annotation.SuppressLint
import android.app.Activity
import android.content.*
import android.os.*

/** Native short is Mode; native long has no action. The retained raw reader owns physical IDs. */
internal class NativeFunctionInput(
    private val context: Context,
    private val currentContext: () -> Any?,
    private val dispatch: (Any) -> Unit,
    private val now: () -> Long = SystemClock::uptimeMillis,
    schedule: (Long, () -> Unit) -> (() -> Unit) = handlerSchedule(),
) {
    @Volatile private var registered=false
    @Volatile private var cancellation=0L
    internal class RawOriginal(val owner: Any,val revision: Long) {
        @Volatile var discarded=false;private set
        fun discard() { discarded=true }
    }
    private class RawPress(val source: Any,val id: Long,val downAt: Long,val original: RawOriginal?)
    private var rawSource: Any?=null
    private var rawPress: RawPress?=null
    private val fallback=FunctionGestureRecognizer(now,currentContext,schedule,dispatch)
    private val receiver=object: BroadcastReceiver() {
        override fun onReceive(context: Context,intent: Intent) {
            if (!registered) return
            val report=REPORTS[intent.action] ?: return
            if (isOrderedBroadcast && intent.flags and Intent.FLAG_RECEIVER_NO_ABORT == 0) {
                resultCode=Activity.RESULT_CANCELED;abortBroadcast()
            }
            if (rawSource == null) fallback.accept(report)
        }
    }
    fun rawSourceAvailable(source: Any) { if(rawSource !== source) { cancel();rawSource=source } }
    fun rawSourceLost(source: Any) { if(rawSource === source) { cancel();rawSource=null } }
    fun captureRawOriginal(): RawOriginal? = if(registered) currentContext()?.let { RawOriginal(it,cancellation) } else null
    fun rawEdge(source: Any,id: Long,down: Boolean,at: Long,received: RawOriginal?) {
        if(rawSource !== source || !registered) return
        if(down) {
            if(rawPress?.let { it.source === source && it.id == id } != true)
                rawPress=RawPress(source,id,at,received)
        } else {
            val original=rawPress?.takeIf { it.source === source && it.id == id } ?: return
            rawPress=null
            val captured=original.original ?: return
            if(at-original.downAt < 800 && at >= original.downAt && !captured.discarded && captured.revision == cancellation &&
                currentContext() == captured.owner) dispatch(captured.owner)
        }
    }
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    fun start() {
        if(registered) return
        val filter=IntentFilter().apply { REPORTS.keys.forEach(::addAction);priority=Int.MAX_VALUE }
        if(Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver,filter,Context.RECEIVER_EXPORTED)
        else { @Suppress("DEPRECATION") context.registerReceiver(receiver,filter) }
        registered=true
    }
    fun prepareCancel(): InputRetirement {
        cancellation++;rawPress=null;fallback.cancel()
        val original=cancellation
        return InputRetirement({}, { cancellation != original })
    }
    fun cancel() = prepareCancel().finishOriginal()
    fun originalSessionLost() = cancel()
    fun stop() { cancel();if(registered) { registered=false;context.unregisterReceiver(receiver) } }
    companion object {
        private val REPORTS=mapOf(
            "com.android.action.ACTION_SPRITE_BUTTON_DOWN" to FunctionGestureRecognizer.Report.DOWN,
            "com.android.action.ACTION_SPRITE_BUTTON_UP" to FunctionGestureRecognizer.Report.UP,
            "com.android.action.ACTION_SPRITE_BUTTON_CLICK" to FunctionGestureRecognizer.Report.CLICK,
            "com.android.action.ACTION_SPRITE_BUTTON_DOUBLE_CLICK" to FunctionGestureRecognizer.Report.DOUBLE,
            "com.android.action.ACTION_BOLON_PAIRING" to FunctionGestureRecognizer.Report.TRIPLE,
            "com.android.action.ACTION_SPRITE_BUTTON_LONG_PRESS" to FunctionGestureRecognizer.Report.LONG)
        private fun handlerSchedule(): (Long, () -> Unit) -> (() -> Unit) {
            val handler=Handler(Looper.getMainLooper())
            return { deadline,action -> val task=Runnable(action);handler.postAtTime(task,deadline);{ handler.removeCallbacks(task) } }
        }
    }
}
