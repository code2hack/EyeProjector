package com.code2hack.eyeprojector.directhost.scale

import com.code2hack.eyeprojector.protocol.LayoutScale

interface AppScaleStorage {
    fun read(packageName: String): Double?
    fun write(packageName: String, scale: Double): Boolean
}

/** Saves requested preferences, never an inferred acknowledgment from stock geometry. */
class AppScaleStore(private val storage: AppScaleStorage) {
    fun read(packageName: String): Double = runCatching { storage.read(packageName) }.getOrNull()
        ?.takeIf { LayoutScale.target(it) != null } ?: 1.0

    fun write(packageName: String, scale: Double): Boolean = LayoutScale.target(scale) != null &&
        runCatching { storage.write(packageName, scale) }.getOrDefault(false)
}
