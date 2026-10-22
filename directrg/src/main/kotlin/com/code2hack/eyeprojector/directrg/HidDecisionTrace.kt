package com.code2hack.eyeprojector.directrg

import android.graphics.Point
import android.graphics.Rect
import android.util.Log

/** Page-origin key observations only. No persistent state, policy, acknowledgments or arbitrary text. */
internal object HidDecisionTrace {
    enum class Fact { UNOBSERVED, NO, YES }
    enum class Step {
        ARRIVAL, ADAPTER_RETIRED, ADAPTER_DEVICE_MISSING, ADAPTER_DETACHED, ADAPTER_CHANGED,
        DEVICE_EXTERNAL, DEVICE_VIRTUAL, DEVICE_NOT_PAD, DEVICE_NOT_POWER, ADAPTER_INELIGIBLE,
        ADAPTER_INPUT, INPUT_RETIRED, CAPTURE_MATCH, CAPTURE_SAVE, CAPTURE_INVALID_RELEASE,
        CAPTURE_BEGIN, CAPTURE_RETIRE, CAPTURE_CANCEL, CAPTURE_RELEASE_UP, CAPTURE_RELEASE_TIME,
        CAPTURE_RELEASE_CONTEXT, INPUT_NO_CONTEXT, INPUT_DOWN_CONTEXT_PRESENT, INPUT_REPEAT,
        INPUT_STALE_BEFORE_TARGET, INPUT_STALE_AFTER_TARGET, INPUT_TARGET, INPUT_NORMAL,
        HOVER_USABLE_BEFORE, HOVER_INITIALIZED, HOVER_PAGE_HIDDEN, HOVER_CONNECTION_HIDDEN,
        HOVER_POINT, HOVER_GENERATION_CHANGED, HOVER_USABLE_AFTER, HOVER_SESSION_CHANGED, HOVER_PROJECTION_CHANGED,
        HOVER_PANEL_CHANGED, HOVER_PAGE_HIDDEN_AFTER, HOVER_CONNECTION_HIDDEN_AFTER,
        HIT_POINT, HIT_ROOT_SHOWN, HIT_PANEL_SHOWN, HIT_PANEL_ENABLED, HIT_ROOT_CLIP,
        HIT_ROOT_OUTSIDE, HIT_ROW_SHOWN, HIT_ROW_ENABLED, HIT_ROW_CLIP, HIT_ROW_POINT, HIT_RESULT,
    }

    class Event(
        private val deviceId: Int, private val keyCode: Int, private val scanCode: Int,
        private val action: Int, private val repeat: Int, private val canceled: Boolean,
        private val downTime: Long, private val eventTime: Long,
        private val sink: (String) -> Unit = { Log.d("EyeProjectorHid", it); Unit },
    ) {
        fun record(
            step: Step, fact: Fact = Fact.UNOBSERVED, target: HidAction? = null,
            point: PointerPosition? = null, bounds: Rect? = null, offset: Point? = null,
            before: Long = Long.MIN_VALUE, after: Long = Long.MIN_VALUE,
        ) {
            try {
                sink(format(step, fact, target, point, bounds, offset, before, after))
            } catch (_: Throwable) {
                // Observation/format/sink failures cannot change the original key route.
            }
        }

        private fun format(
            step: Step, fact: Fact = Fact.UNOBSERVED, target: HidAction? = null,
            point: PointerPosition? = null, bounds: Rect? = null, offset: Point? = null,
            before: Long = Long.MIN_VALUE, after: Long = Long.MIN_VALUE,
        ): String = "device=$deviceId key=$keyCode scan=$scanCode action=$action repeat=$repeat canceled=$canceled" +
            " down=$downTime time=$eventTime step=${step.name} fact=${fact.name} target=${target?.name ?: UNOBSERVED}" +
            " available=${point?.available ?: UNOBSERVED} x=${coordinate(point?.x)} y=${coordinate(point?.y)}" +
            " left=${bounds?.left ?: UNOBSERVED} top=${bounds?.top ?: UNOBSERVED}" +
            " right=${bounds?.right ?: UNOBSERVED} bottom=${bounds?.bottom ?: UNOBSERVED}" +
            " offsetX=${offset?.x ?: UNOBSERVED} offsetY=${offset?.y ?: UNOBSERVED}" +
            " generationBefore=${generation(before)} generationAfter=${generation(after)}"
    }

    /** Only diagnostic argument reads belong in this block, never functional callbacks. */
    inline fun capture(read: () -> Event?): Event? = try { read() } catch (_: Throwable) { null }

    fun observed(
        trace: Event?, step: Step, value: Boolean, target: HidAction? = null,
        bounds: Rect? = null, offset: Point? = null, before: Long = Long.MIN_VALUE, after: Long = Long.MIN_VALUE,
    ): Boolean {
        trace?.record(step, if (value) Fact.YES else Fact.NO, target = target,
            bounds = bounds, offset = offset, before = before, after = after)
        return value
    }

    private fun generation(value: Long): String = if (value == Long.MIN_VALUE) UNOBSERVED else value.toString()
    private fun coordinate(value: Float?): String = if (value?.isFinite() == true) value.toString() else UNOBSERVED
    private const val UNOBSERVED = "UNOBSERVED"
}
