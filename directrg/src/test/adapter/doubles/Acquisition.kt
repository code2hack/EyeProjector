package com.code2hack.eyeprojector.directrg

import android.content.Context

interface HeadPoseSource {
    val description: String
    val registered: Boolean
    fun start(consumer: (RotationSample) -> Unit): Boolean
    fun stop()
}

/** Replaces raw sensor acquisition only; actual model/sample/frame/expiry code runs unchanged. */
class SensorHeadPoseSource(context: Context) : HeadPoseSource {
    companion object {
        lateinit var latest: SensorHeadPoseSource
    }
    init { latest = this }
    private var consumer: ((RotationSample) -> Unit)? = null
    override val description = "host-acquisition-double"
    override val registered get() = consumer != null
    override fun start(consumer: (RotationSample) -> Unit): Boolean { this.consumer = consumer; return true }
    override fun stop() { consumer = null }
    fun emit(sample: RotationSample) { consumer?.invoke(sample) }
}
