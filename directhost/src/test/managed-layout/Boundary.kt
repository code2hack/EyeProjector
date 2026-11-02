package com.code2hack.eyeprojector.directhost

import com.code2hack.eyeprojector.protocol.*
import com.code2hack.eyeprojector.directhost.input.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt

/** Only display discovery/callback delivery and native injection are controlled here.
 * Mapping methods and the Session Layout arm are inserted byte-for-byte by run.py. */
internal class ManagedProjectionDisplay {
    class Failure(message: String) : RuntimeException(message)
    private val inputOperations = Any()
    val closeRequested = AtomicBoolean()
    val resizeRequested = AtomicReference<Double?>()
    val displayChanged = AtomicBoolean()
    var sourceTransitioning = false
    var observedSource: SourceObservation? = null
    private var inputMapping: InputMapping? = null
    private val capturedDisplayId: Int? = 63
    private val originalDisplayUniqueId = "original-display"
    private val originalResource = Any()
    var originalDisplay: Any? = originalResource
    val sourceDisplayId get() = capturedDisplayId!!
    private fun requireOriginalDisplay() { check(originalDisplay === originalResource) { "Original display resource lost" } }

    // ACTUAL_DISPLAY_METHODS

    /** Platform observation boundary: settle a delivered display callback, preserving its value. */
    fun settled(observation: SourceObservation) {
        if (observedSource != observation) inputMapping = null
        observedSource = observation
        resizeRequested.set(null)
        displayChanged.set(false)
        sourceTransitioning = false
    }
}

internal class LayoutFlow(val source: ManagedProjectionDisplay) : AutoCloseable {
    val controlLock = Any()
    val geometry = AtomicReference<Geometry?>(Geometry(480, 640))
    val inputControls = PhoneTouchDispatcher(controlLock) { error("Unexpected stock output") }
    val phoneInput = PhoneInputMerger(controlLock, { 1000L }, { _, _ -> {} }, { geometry.get() },
        inputControls::touch,inputControls::scroll,{ _,_-> },{ inputControls.cancel() },{})
    val events = mutableListOf<Touch>()
    var taps = 0
    val managed = ManagedProjectionInput(source)
    val published = mutableListOf<ScaleState>()
    val scale = LiveScale({}, published::add)
    var managedInputSource = source.sourceObservation()

    init {
        inputControls.bindMotionTarget(object : PhoneTouchDispatcher.MotionTarget {
            override fun geometry(next: Geometry) = managed.geometry(next)
            override fun touch(event: Touch): Boolean = managed.touch(event).also { if (it) events.add(event) }
            override fun tap(event: Tap): Boolean = managed.tap(event).also { if (it) taps++ }
            override fun scroll(event: Scroll): Boolean = managed.scroll(event)
        })
        // Encoder/layout metadata can exist before a usable input mapping has been installed.
        scale.observe(ScrcpyRecord.Session(geometry.get()!!, false), observedGeometry = source.layoutGeometry())
    }

    fun layout(observed: ManagedProjectionDisplay.SourceObservation) {
        source.settled(observed)
        val next = ManagedProjectionDisplay.Read.Layout(observed)
        for (once in 0 until 1) {
            // ACTUAL_SESSION_LAYOUT_BODY
        }
    }
    private fun recordManagedProjection() = Unit // Status journal only.
    override fun close() { inputControls.close() }

}

internal class PhoneInputTrace {
    fun retain(): java.io.Closeable? = null
    fun record(stage: String, values: Map<String, Any?>) = Unit
}
