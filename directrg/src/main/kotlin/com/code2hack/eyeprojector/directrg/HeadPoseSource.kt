package com.code2hack.eyeprojector.directrg

// Input primitive adapted from v0.0.4@03de921e2400147842ce6c56101b58d514dde77c.

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper

/** The test seam replaces raw acquisition only; every sample still traverses the real aim model. */
interface HeadPoseSource {
    val description: String
    val registered: Boolean
    fun start(consumer: (RotationSample) -> Unit): Boolean
    fun stop()
}

class SensorHeadPoseSource(context: Context) : HeadPoseSource {
    private val manager=context.getSystemService(SensorManager::class.java)
    private val sensor=manager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        ?: manager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    override val description get() = sensor?.let { "${it.name} (${it.vendor}, type=${it.type})" } ?: "No rotation sensor"
    private var listener: SensorEventListener?=null
    override val registered get() = listener!=null

    override fun start(consumer: (RotationSample) -> Unit): Boolean {
        check(Looper.myLooper()==Looper.getMainLooper())
        stop()
        val selected=sensor ?: return false
        val next=object : SensorEventListener {
            private val quaternion=FloatArray(4)
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
            override fun onSensorChanged(event: SensorEvent) {
                if (listener!==this || event.values.size<3) return
                SensorManager.getQuaternionFromVector(quaternion,event.values)
                // Copy primitives before returning; neither framework array is retained by the consumer.
                consumer(RotationSample(event.timestamp,quaternion[1],quaternion[2],quaternion[3],quaternion[0]))
            }
        }
        listener=next
        val started=try {
            manager.registerListener(next,selected,SensorManager.SENSOR_DELAY_GAME,Handler(Looper.getMainLooper()))
        } catch (_: SecurityException) { false }
        if (!started) { listener=null;manager.unregisterListener(next) }
        return started
    }

    override fun stop() {
        check(Looper.myLooper()==Looper.getMainLooper())
        val old=listener;listener=null // Fence already queued callbacks before unregistering.
        if (old!=null) manager.unregisterListener(old)
    }
}
