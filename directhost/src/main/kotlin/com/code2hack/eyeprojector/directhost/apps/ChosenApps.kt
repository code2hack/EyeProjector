package com.code2hack.eyeprojector.directhost.apps

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import com.code2hack.eyeprojector.directhost.notifications.NotificationPolicy

/** One private preference file, shared by every Phone/projected-home consumer. No default apps. */
object ChosenApps {
    private const val FILE = "chosen_projection_apps"
    private const val KEY = "ordered_apps"

    fun open(context: Context): ChosenAppStore = ChosenAppStore(PreferenceStorage(preferences(context))) { packageName, chosen ->
        val change = NotificationPolicy.process.begin(packageName, true, chosen)
        object : ChosenAppMutation {
            override fun isCurrent() = NotificationPolicy.process.isCurrent(change)
            override fun finished(success: Boolean) = NotificationPolicy.process.finish(change, success)
        }
    }

    fun observe(context: Context, onChange: () -> Unit): Closeable {
        val preferences = preferences(context)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY || key == null) onChange()
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        return Closeable { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    private fun preferences(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private class PreferenceStorage(private val preferences: SharedPreferences) : ChosenAppStorage {
        override fun read(): List<ChosenApp> {
            val array = JSONArray(preferences.getString(KEY, "[]"))
            return (0 until array.length()).map { index ->
                val item = array.getJSONObject(index)
                ChosenApp(item.getString("package"), item.getString("label"))
            }
        }

        override fun write(apps: List<ChosenApp>) {
            val array = JSONArray()
            apps.forEach { array.put(JSONObject().put("package", it.packageName).put("label", it.label)) }
            preferences.edit().putString(KEY, array.toString()).apply()
        }
    }
}
