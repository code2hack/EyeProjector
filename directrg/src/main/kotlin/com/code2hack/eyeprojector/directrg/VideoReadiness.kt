package com.code2hack.eyeprojector.directrg

/** Readiness belongs to one decoder epoch, separately from releasing its predecessor. */
internal class VideoReadiness {
    private sealed interface Phase
    private data object Idle : Phase
    private class Replacing(val epoch: Any) : Phase
    private class Awaiting(val epoch: Any, val startedNs: Long) : Phase
    private class Ready(val epoch: Any) : Phase
    private data object Closed : Phase

    internal class Expiry internal constructor(internal val awaiting: Any)

    private var phase: Phase = Idle

    @get:Synchronized
    val ready: Boolean get() = phase is Ready

    @Synchronized fun awaitInitialFrame(nowNs: Long): Any? {
        if (phase !== Idle) return null
        return Any().also { phase = Awaiting(it, nowNs) }
    }

    @Synchronized fun beginReplacement(): Any? {
        if (phase === Closed) return null
        return Any().also { phase = Replacing(it) }
    }

    @Synchronized fun replacing(epoch: Any): Boolean =
        (phase as? Replacing)?.epoch === epoch

    @Synchronized fun awaitFrame(epoch: Any, nowNs: Long): Boolean {
        if ((phase as? Replacing)?.epoch !== epoch) return false
        phase = Awaiting(epoch, nowNs)
        return true
    }

    @Synchronized fun awaiting(epoch: Any): Boolean =
        (phase as? Awaiting)?.epoch === epoch

    @Synchronized fun ready(epoch: Any): Boolean =
        (phase as? Ready)?.epoch === epoch

    @Synchronized fun rendered(epoch: Any): Boolean {
        if ((phase as? Awaiting)?.epoch !== epoch) return false
        phase = Ready(epoch)
        return true
    }

    @Synchronized fun expiry(nowNs: Long): Expiry? {
        val wait = phase as? Awaiting ?: return null
        return if (nowNs - wait.startedNs > 5_000_000_000L) Expiry(wait) else null
    }

    /** Claim before cancellation; a render or another epoch retires a computed expiry. */
    @Synchronized fun claim(expiry: Expiry): Boolean {
        if (phase !== expiry.awaiting) return false
        phase = Closed
        return true
    }

    /** Atomically retire only the decoder that failed; replacement wins over an old failure. */
    @Synchronized fun claimFailure(epoch: Any): Boolean {
        val current = when (val value = phase) {
            is Awaiting -> value.epoch
            is Ready -> value.epoch
            else -> null
        }
        if (current !== epoch) return false
        phase = Closed
        return true
    }

    @Synchronized fun close() { phase = Closed }
}
