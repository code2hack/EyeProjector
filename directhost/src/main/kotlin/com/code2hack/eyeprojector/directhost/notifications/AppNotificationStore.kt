package com.code2hack.eyeprojector.directhost.notifications

interface AppNotificationStorage {
    fun read(packageName: String): Boolean?
    fun write(packageName: String, enabled: Boolean)
}

/**
 * EyeProjector's Glasses route only, keyed by the chosen app's stable package name.
 * Missing preferences default On; explicit On and Off choices are both retained.
 * Callers deciding whether to activate an app must separately check current chosen membership.
 */
class AppNotificationStore(private val storage: AppNotificationStorage) {
    fun isEnabled(packageName: String): Boolean = storage.read(packageName) ?: true

    fun setEnabled(packageName: String, enabled: Boolean) = storage.write(packageName, enabled)
}
