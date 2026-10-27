package com.code2hack.eyeprojector.directhost.scale

import com.code2hack.eyeprojector.protocol.LayoutScale

interface AppScaleStorage {
    fun read(packageName: String): Double?
    fun write(packageName: String, scale: Double): Boolean
}

/** Saves requested preferences, never an inferred acknowledgment from stock geometry. */
class AppScaleStore(private val storage: AppScaleStorage) {
    fun read(packageName: String): Double {
        val saved = runCatching { storage.read(packageName) }.getOrNull() ?: return DEFAULT_SCALE
        if (valid(saved)) return saved
        runCatching { storage.write(packageName, DEFAULT_SCALE) }
        return DEFAULT_SCALE
    }

    fun write(packageName: String, scale: Double): Boolean = valid(scale) &&
        runCatching { storage.write(packageName, scale) }.getOrDefault(false)

    companion object {
        const val MIN_SCALE = 0.5
        const val MAX_SCALE = 1.5
        const val DEFAULT_SCALE = 1.0
        fun valid(scale: Double) = scale.isFinite() && scale in MIN_SCALE..MAX_SCALE &&
            LayoutScale.target(scale) != null
    }
}
