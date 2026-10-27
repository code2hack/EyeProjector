package android.hardware.camera2.params

import android.util.Size
import java.util.concurrent.CopyOnWriteArrayList

/** Class-specific catalogs; querying one consumer class never supplies the other one's sizes. */
class StreamConfigurationMap {
    val outputs = LinkedHashMap<Class<*>, Array<Size>>()
    val durations = LinkedHashMap<Pair<Class<*>, Size>, Long>()
    val outputSizesExceptions = LinkedHashMap<Class<*>, Exception>()
    val minFrameDurationExceptions = LinkedHashMap<Pair<Class<*>, Size>, Exception>()
    val outputSizeReads = CopyOnWriteArrayList<Class<*>>()
    val minFrameDurationReads = CopyOnWriteArrayList<Pair<Class<*>, Size>>()

    fun <T> getOutputSizes(klass: Class<T>): Array<Size>? {
        outputSizeReads.add(klass)
        outputSizesExceptions[klass]?.let { throw it }
        return outputs[klass]?.copyOf()
    }

    fun <T> getOutputMinFrameDuration(klass: Class<T>, size: Size): Long {
        val original = klass to size
        minFrameDurationReads.add(original)
        minFrameDurationExceptions[original]?.let { throw it }
        return durations[original] ?: 0L
    }
}
