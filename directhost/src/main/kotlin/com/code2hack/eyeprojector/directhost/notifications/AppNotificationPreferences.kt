package com.code2hack.eyeprojector.directhost.notifications

import android.content.Context

object AppNotificationPreferences {
    fun open(context: Context): AppNotificationStore {
        val preferences = context.getSharedPreferences("projection_app_notifications", Context.MODE_PRIVATE)
        return AppNotificationStore(object : AppNotificationStorage {
            override fun read(packageName: String): Boolean? =
                if (preferences.contains(packageName)) preferences.getBoolean(packageName, true) else null

            override fun write(packageName: String, enabled: Boolean) {
                preferences.edit().putBoolean(packageName, enabled).apply()
            }
        })
    }
}
