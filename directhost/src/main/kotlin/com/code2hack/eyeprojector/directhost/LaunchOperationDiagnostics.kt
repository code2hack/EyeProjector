package com.code2hack.eyeprojector.directhost

import android.util.Log
import java.io.IOException
import java.security.MessageDigest

/** Fixed metadata from an existing operation; never an operation, callback or foreground acknowledgment. */
internal object LaunchOperationDiagnostics {
    const val TAG = "EyeProjectorLaunch"
    enum class Kind { LAUNCH, DISPLAY_QUERY }
    enum class Stage { PRE_CALL, RESULT, BEFORE_CALLBACK }
    enum class Outcome { SKIPPED, ADMITTED, RETURNED, PRE_CALL_EXCEPTION, OPERATION_EXCEPTION, DECODE_EXCEPTION, VALUE_SELECTED, NULL_SELECTED }
    enum class Reason { EXPECTED_CONTEXT_UNAVAILABLE, NO_SERVICE, SERVICE_CHANGED, BINDING_CHANGED, CONTEXT_CHANGED }
    data class Match(
        val servicePresent: Boolean,
        val sameService: Boolean?,
        val sameBinding: Boolean?,
        val exactContext: Boolean,
        val acceptedContext: Boolean,
        val accepted: Boolean,
    ) {
        val reason: Reason? get() = when {
            !servicePresent -> Reason.NO_SERVICE
            sameService == false -> Reason.SERVICE_CHANGED
            sameBinding == false -> Reason.BINDING_CHANGED
            !acceptedContext -> Reason.CONTEXT_CHANGED
            else -> null
        }
    }
    data class Event(
        val operationId: Long,
        val kind: Kind,
        val selectedPackage: String?,
        val stage: Stage,
        val outcome: Outcome,
        val expectedAvailable: Boolean?,
        val match: Match? = null,
        val reason: Reason? = null,
        val result: ProjectionDisplayState? = null,
        val resultContextMatch: Boolean? = null,
        val error: Throwable? = null,
    )

    // These restrictions apply only to emitted text, never to launch admission or arguments.
    private val packageName = Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+")
    private val component = Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+/[A-Za-z0-9_.$]+")
    private fun application(value: String?, isComponent: Boolean = false): String = when {
        value == null -> "none"
        value.length > if (isComponent) 512 else 256 -> "redacted"
        (if (isComponent) component else packageName).matches(value) -> value
        else -> "redacted"
    }
    private fun family(error: Throwable?): String = when (error) {
        null -> "none"
        is SecurityException -> "SECURITY"
        is IllegalArgumentException -> "ARGUMENT"
        is IllegalStateException -> "STATE"
        is IOException -> "IO"
        else -> when (error.javaClass.name) {
            "android.os.RemoteException", "android.os.DeadObjectException" -> "REMOTE"
            else -> "OTHER"
        }
    }

    internal fun format(event: Event): String {
        val result = event.result
        fun flag(value: Boolean?) = value?.toString() ?: "not_evaluated"
        val digest = result?.generation?.takeIf { it.isNotEmpty() }?.let {
            MessageDigest.getInstance("SHA-256").digest(it.toByteArray(Charsets.UTF_8))
                .joinToString("") { byte -> "%02x".format(byte) }
        } ?: "none"
        return "v=1 operationId=${event.operationId} kind=${event.kind.name}" +
            " selectedPackage=${application(event.selectedPackage)} stage=${event.stage.name} outcome=${event.outcome.name}" +
            " expectedAvailable=${flag(event.expectedAvailable)} reason=${(event.reason ?: event.match?.reason)?.name ?: "none"}" +
            " servicePresent=${flag(event.match?.servicePresent)} sameService=${flag(event.match?.sameService)}" +
            " sameBinding=${flag(event.match?.sameBinding)} exactContext=${flag(event.match?.exactContext)}" +
            " acceptedContext=${flag(event.match?.acceptedContext)} accepted=${flag(event.match?.accepted)}" +
            " resultPresent=${result != null} resultStatus=${result?.status?.name ?: "none"}" +
            " resultRequestId=${result?.requestId ?: "none"} requestedPackage=${application(result?.requestedPackage)}" +
            " commandExit=${result?.commandExit ?: "none"} displayId=${result?.displayId ?: "none"}" +
            " generationDigest=$digest generationBasis=sha256_in_memory_not_raw_identity" +
            " launchedComponent=${application(result?.launchedComponent, true)} foregroundPackage=${application(result?.foregroundPackage)}" +
            " resultContextMatch=${flag(event.resultContextMatch)} exceptionFamily=${family(event.error)}" +
            " scope=existing_operation_metadata_not_ack"
    }

    internal inline fun emit(event: () -> Event, sink: (String) -> Unit = { Log.i(TAG, it); Unit }) {
        try { sink(format(event())) } catch (_: Throwable) { /* Reporting cannot alter the operation. */ }
    }
}
