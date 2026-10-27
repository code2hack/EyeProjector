package com.code2hack.eyeprojector.directhost

import android.content.Context
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Properties

/** A source preference for a new projection, not proof that a camera has been routed. */
internal object CameraSourceSettings {
    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences("camera-source", Context.MODE_PRIVATE)

    fun useGlasses(context: Context): Boolean = preferences(context).getBoolean("use-glasses-camera", false)

    fun setUseGlasses(context: Context, enabled: Boolean) {
        preferences(context).edit().putBoolean("use-glasses-camera", enabled).apply()
    }

    /** Transform only the START copy. Preserve every existing endpoint property and private payload. */
    fun endpointForStart(context: Context, original: ByteArray): ByteArray {
        val endpoint = Properties().apply { load(ByteArrayInputStream(original.copyOf())) }
        endpoint.setProperty("camera.source", if (useGlasses(context)) "glasses" else "phone")
        return ByteArrayOutputStream().also { endpoint.store(it, null) }.toByteArray()
    }
}
