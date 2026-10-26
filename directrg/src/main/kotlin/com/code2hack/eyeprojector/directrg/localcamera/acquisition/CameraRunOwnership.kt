package com.code2hack.eyeprojector.directrg.localcamera.acquisition

/**
 * Serialized original-resource accounting for the actual adapter. Supported rejection, actual
 * arrival, parent-fence retirement and indeterminate outcome stay distinct. No timer, retry,
 * synthetic callback or borrowed-output release lives here.
 */
internal class CameraRunOwnership<D : Any, S : Any>(
    private val closeDevice: (D) -> Unit,
    private val closeSession: (S) -> Unit,
    private val changed: (CameraRunStage, CameraOwnershipSnapshot) -> Unit,
    private val failed: (String, Exception) -> Unit,
    private val safe: (CameraOutputFence, CameraOwnershipSnapshot) -> Unit,
) {
    var device: D? = null
        private set
    var session: S? = null
        private set
    private var state = CameraOwnershipSnapshot()
    private var driving = false
    private var dirty = false
    fun snapshot() = state
    private fun publish(stage: CameraRunStage) { changed(stage, state) }

    fun beforeOpenInvocation() {
        state = state.copy(openInvoked = true, openPending = true,
            openDisposition = CameraArrivalDisposition.PENDING)
        publish(CameraRunStage.OPEN_REQUESTED)
    }
    fun beforeConfigurationInvocation() {
        state = state.copy(configurationPending = true,
            configurationDisposition = CameraArrivalDisposition.PENDING)
        publish(CameraRunStage.CONFIGURATION_REQUESTED)
    }
    fun indeterminate(operation: CameraCallOperation) {
        if (operation == CameraCallOperation.OPEN && state.openPending) {
            state = state.copy(openDisposition = CameraArrivalDisposition.INDETERMINATE)
            publish(CameraRunStage.OUTCOME_INDETERMINATE)
        } else if (operation == CameraCallOperation.CONFIGURE && state.configurationPending) {
            state = state.copy(configurationDisposition = CameraArrivalDisposition.INDETERMINATE)
            publish(CameraRunStage.OUTCOME_INDETERMINATE)
        }
        // A callback-before-catch never re-arms an already resolved/retired leg.
    }
    fun rejected(operation: CameraCallOperation, provenance: String): Boolean {
        if (operation == CameraCallOperation.OPEN && state.openPending && device == null) {
            state = state.copy(openPending = false,
                openDisposition = CameraArrivalDisposition.REJECTED_BEFORE_ACCEPTANCE,
                openRejectionProvenance = provenance)
        } else if (operation == CameraCallOperation.CONFIGURE && state.configurationPending && session == null) {
            state = state.copy(configurationPending = false,
                configurationDisposition = CameraArrivalDisposition.REJECTED_BEFORE_ACCEPTANCE,
                configurationRejectionProvenance = provenance)
        } else return false
        publish(CameraRunStage.REJECTED_BEFORE_ACCEPTANCE)
        requestStop()
        return true
    }

    fun deviceArrived(original: D): Boolean {
        if (state.outputsSafe || (device != null && device !== original)) {
            if (device !== original) try { closeDevice(original) } catch (error: Exception) { failed("late-device-close", error) }
            else state = state.copy(openArrivalObserved = true)
            publish(CameraRunStage.STALE_CALLBACK)
            return false
        }
        device = original
        state = state.copy(openPending = false, hasDevice = true, openArrivalObserved = true,
            openDisposition = CameraArrivalDisposition.ARRIVED)
        drive()
        return !state.closing && !state.deviceClosedObserved
    }

    fun sessionArrived(original: S): Boolean {
        if (state.outputsSafe) {
            if (session == null && state.configurationDisposition == CameraArrivalDisposition.RETIRED_BY_ORIGINAL_PARENT_FENCE) {
                session = original
                state = state.copy(hasSession = true, configurationArrivalObserved = true,
                    sessionRetirement = SessionRetirement.PARENT_DEVICE_ON_CLOSED)
                closeOriginalSession()
                publish(CameraRunStage.LATE_CONFIGURATION_ARRIVED)
            } else if (session !== original) {
                try { closeSession(original) } catch (error: Exception) { failed("late-session-close", error) }
                publish(CameraRunStage.STALE_CALLBACK)
            }
            return false
        }
        if (session != null && session !== original) {
            try { closeSession(original) } catch (error: Exception) { failed("late-session-close", error) }
            publish(CameraRunStage.STALE_CALLBACK); return false
        }
        session = original
        state = state.copy(configurationPending = false, hasSession = true, configurationArrivalObserved = true,
            configurationDisposition = CameraArrivalDisposition.ARRIVED)
        retireByParent()
        drive()
        return !state.closing && state.sessionRetirement == null
    }

    fun configurationFailed(original: S) {
        if ((session != null && session !== original) ||
            (state.outputsSafe && state.configurationDisposition != CameraArrivalDisposition.RETIRED_BY_ORIGINAL_PARENT_FENCE)) {
            publish(CameraRunStage.STALE_CALLBACK); return
        }
        session = original
        // Camera2 defines the failed session as closed; no session onClosed is fabricated.
        state = state.copy(configurationPending = false, hasSession = true, configurationArrivalObserved = true,
            configurationDisposition = if (state.outputsSafe) state.configurationDisposition else CameraArrivalDisposition.ARRIVED,
            sessionRetirement = state.sessionRetirement ?: SessionRetirement.CONFIGURATION_FAILED)
        publish(CameraRunStage.CONFIGURATION_FAILED)
        if (!state.outputsSafe) requestStop()
    }

    fun deviceClosed(original: D) {
        if (!state.openInvoked || (device != null && device !== original) ||
            (state.outputsSafe && device !== original) || state.deviceClosedObserved) {
            publish(CameraRunStage.STALE_CALLBACK); return
        }
        device = original
        state = state.copy(openPending = false, hasDevice = true, deviceClosedObserved = true,
            openDisposition = if (state.openArrivalObserved) state.openDisposition
                else CameraArrivalDisposition.RETIRED_BY_ORIGINAL_PARENT_FENCE,
            configurationPending = false,
            configurationDisposition = if (state.configurationPending)
                CameraArrivalDisposition.RETIRED_BY_ORIGINAL_PARENT_FENCE else state.configurationDisposition)
        retireByParent()
        publish(CameraRunStage.DEVICE_CLOSED)
        requestStop()
    }

    fun sessionClosed(original: S) {
        if ((session != null && session !== original) || state.sessionClosedObserved ||
            (state.outputsSafe && session == null && state.configurationDisposition != CameraArrivalDisposition.RETIRED_BY_ORIGINAL_PARENT_FENCE)) {
            publish(CameraRunStage.STALE_CALLBACK); return
        }
        session = original
        state = state.copy(configurationPending = false, hasSession = true, sessionClosedObserved = true,
            configurationArrivalObserved = true,
            configurationDisposition = if (state.configurationDisposition == CameraArrivalDisposition.RETIRED_BY_ORIGINAL_PARENT_FENCE)
                state.configurationDisposition else CameraArrivalDisposition.ARRIVED,
            sessionRetirement = state.sessionRetirement ?: if (state.deviceClosedObserved)
                SessionRetirement.PARENT_DEVICE_ON_CLOSED else SessionRetirement.SESSION_ON_CLOSED)
        publish(CameraRunStage.SESSION_CLOSED)
        if (!state.outputsSafe) requestStop()
    }

    private fun retireByParent() {
        if (state.deviceClosedObserved && session != null && state.sessionRetirement == null)
            state = state.copy(sessionRetirement = SessionRetirement.PARENT_DEVICE_ON_CLOSED)
    }
    fun requestStop() {
        if (!state.closing) { state = state.copy(closing = true); publish(CameraRunStage.STOP_REQUESTED) }
        drive()
    }
    private fun closeOriginalSession() {
        val original = session ?: return
        if (state.sessionCloseInvoked || state.sessionClosedObserved ||
            state.sessionRetirement == SessionRetirement.CONFIGURATION_FAILED) return
        state = state.copy(sessionCloseInvoked = true)
        publish(CameraRunStage.SESSION_CLOSE_INVOKED) // Local onceclaim before the original call.
        try {
            closeSession(original)
            state = state.copy(sessionCloseCallReturned = true)
            publish(CameraRunStage.SESSION_CLOSE_RETURNED)
        } catch (error: Exception) { failed("session-close-unconfirmed", error) }
    }
    private fun drive() {
        if (driving) { dirty = true; return }
        if (!state.closing || state.outputsSafe) return
        driving = true
        try {
            do {
                dirty = false
                closeOriginalSession()
                val originalDevice = device
                if (originalDevice != null && !state.deviceClosedObserved && !state.deviceCloseInvoked) {
                    state = state.copy(deviceCloseInvoked = true)
                    publish(CameraRunStage.DEVICE_CLOSE_INVOKED) // Not return or onClosed proof.
                    try {
                        closeDevice(originalDevice)
                        state = state.copy(deviceCloseCallReturned = true)
                        publish(CameraRunStage.DEVICE_CLOSE_RETURNED)
                    } catch (error: Exception) { failed("device-close-unconfirmed", error) }
                }
                val noOpen = !state.openInvoked && device == null
                val rejectedOpen = state.openDisposition == CameraArrivalDisposition.REJECTED_BEFORE_ACCEPTANCE && device == null
                val fenced = noOpen || rejectedOpen || state.deviceClosedObserved
                if (!state.openPending && !state.configurationPending && fenced &&
                    (session == null || state.sessionRetirement != null) && !state.outputsSafe) {
                    state = state.copy(outputsSafe = true)
                    publish(CameraRunStage.OUTPUTS_SAFE)
                    safe(when {
                        noOpen -> CameraOutputFence.NO_OPEN_INVOKED
                        rejectedOpen -> CameraOutputFence.KNOWN_OPEN_REJECTED_BEFORE_ACCEPTANCE
                        else -> CameraOutputFence.ORIGINAL_DEVICE_ON_CLOSED
                    }, state)
                }
            } while (dirty && !state.outputsSafe)
        } finally { driving = false }
    }
}
