package com.code2hack.eyeprojector.directhost.notifications

import android.content.Context
import android.content.SharedPreferences
import java.io.Closeable

object AppNotificationPreferences {
    private val writes = Any()
    fun open(context: Context): AppNotificationStore {
        val preferences = preferences(context)
        return AppNotificationStore(object : AppNotificationStorage {
            override fun read(packageName: String): Boolean? =
                if (preferences.contains(packageName)) preferences.getBoolean(packageName, true) else null

            override fun write(packageName: String, enabled: Boolean) {
                val change = NotificationPolicy.process.begin(packageName, false, enabled)
                var success = false
                try {
                    synchronized(writes) {
                        if (NotificationPolicy.process.isCurrent(change)) preferences.edit().putBoolean(packageName, enabled).apply()
                    }
                    success = true
                }
                finally { NotificationPolicy.process.finish(change, success) }
            }
        })
    }
    fun observe(context: Context, changed: () -> Unit): Closeable {
        val preferences = preferences(context)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> changed() }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        return Closeable { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    private fun preferences(context: Context) = context.getSharedPreferences("projection_app_notifications", Context.MODE_PRIVATE)
}
