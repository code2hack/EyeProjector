package com.code2hack.eyeprojector.directhost.scale

import android.content.Context

object AppScalePreferences {
    fun open(context: Context): AppScaleStore {
        val preferences = context.getSharedPreferences("projection_app_scales", Context.MODE_PRIVATE)
        return AppScaleStore(object : AppScaleStorage {
            override fun read(packageName: String): Double? = preferences.getString(packageName, null)?.toDoubleOrNull()
            override fun write(packageName: String, scale: Double): Boolean =
                preferences.edit().putString(packageName, scale.toString()).commit()
        })
    }
}
