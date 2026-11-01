package com.code2hack.eyeprojector.directrg

/** Optional same-instrumentation test driver. Calls run on the instrumentation thread. */
internal interface NativeObservationAutomation {
    val activityLaunched: Boolean
    val activityFinished: Boolean
    val syntheticInputInjected: Boolean
    fun installed(queue: NativeInputObservationQueue)
    fun tick()
    fun close()
}
