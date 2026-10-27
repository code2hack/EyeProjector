package com.code2hack.eyeprojector.directhost.hid

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import androidx.annotation.Keep
import org.json.JSONObject

/** Dedicated Shizuku user service. Projection Stop has no reference to this owner. */
@Keep
class ShizukuHidCaptureService @Keep constructor(private val context: Context) : IHidCapture.Stub() {
    private val thread = HandlerThread("EyeProjectorHidOwner").apply { start() }
    private val owner = Handler(thread.looper)
    private var backend: NativeHidCapture? = null
    private var listener: IHidCaptureListener? = null
    private var death: IBinder.DeathRecipient? = null
    private var epoch = 0L
    private var destroyed = false
    @Volatile private var inventoryJson = "{\"devices\":[]}"
    @Volatile private var stateJson = idle()

    init { owner.post { openBackend() } }

    override fun inventory(): String {
        owner.post { backend?.let { inventoryJson = it.inventory() } }
        return inventoryJson
    }

    override fun state(): String = stateJson

    override fun attach(next: IHidCaptureListener) {
        owner.post {
            if (destroyed) return@post
            if (listener?.asBinder() === next.asBinder()) {
                deliverState(stateJson)
                return@post
            }
            // Retire the old reader before installing a different listener. Its
            // queued callbacks cannot transfer held presses to the new client.
            if (listener != null) {
                backend?.close()
                backend = null
                ++epoch
                unlinkListener()
                stateJson = idle()
            }
            listener = next
            val binder = next.asBinder()
            val recipient = IBinder.DeathRecipient {
                owner.post {
                    if (listener?.asBinder() === binder) {
                        dropClient()
                    }
                }
            }
            death = recipient
            try {
                binder.linkToDeath(recipient, 0)
            } catch (_: Exception) {
                dropClient()
                return@post
            }
            if (backend == null) openBackend()
            deliverState(stateJson)
        }
    }

    override fun pin(deviceKey: String): String {
        owner.post {
            if (destroyed) return@post
            if (listener == null) {
                publishFailure("Attach a capture listener before pinning a device")
            } else {
                if (backend == null) openBackend()
                backend?.pin(deviceKey)
            }
        }
        return stateJson
    }

    override fun unpin(): String {
        owner.post { backend?.unpin() }
        return stateJson
    }

    override fun destroy() {
        owner.post {
            if (destroyed) return@post
            destroyed = true
            backend?.close()
            backend = null
            ++epoch
            unlinkListener()
            stateJson = idle()
            thread.quitSafely()
            // This is Shizuku's reserved destroy transaction for the separately
            // tagged HID process, after its owned grabs have been released.
            kotlin.system.exitProcess(0)
        }
    }

    private fun openBackend() {
        val openedEpoch = ++epoch
        try {
            backend = NativeHidCapture(context, { json ->
                owner.post {
                    if (!destroyed && openedEpoch == epoch) {
                        stateJson = json
                        backend?.let { inventoryJson = it.inventory() }
                        deliverState(json)
                    }
                }
            }, { json ->
                owner.post {
                    if (!destroyed && openedEpoch == epoch) {
                        try { listener?.onInput(json) } catch (_: Exception) { dropClient() }
                    }
                }
            })
            inventoryJson = backend!!.inventory()
            stateJson = backend!!.state()
        } catch (_: LinkageError) {
            publishFailure("The installed HID native backend could not be loaded")
        } catch (_: Exception) {
            publishFailure("The HID native backend could not start")
        }
    }

    private fun deliverState(json: String) {
        try { listener?.onState(json) } catch (_: Exception) { dropClient() }
    }

    private fun dropClient() {
        unlinkListener()
        backend?.close()
        backend = null
        ++epoch
        stateJson = idle()
    }

    private fun unlinkListener() {
        val current = listener
        val recipient = death
        listener = null
        death = null
        if (current != null && recipient != null) {
            try { current.asBinder().unlinkToDeath(recipient, 0) } catch (_: Exception) { }
        }
    }

    private fun publishFailure(message: String) {
        stateJson = JSONObject(stateJson).put("phase", "FAILED").put("intercepted", false)
            .put("error", message).toString()
        deliverState(stateJson)
    }

    private companion object {
        fun idle() = "{\"phase\":\"IDLE\",\"generation\":0,\"intercepted\":false,\"collectionCount\":0}"
    }
}
