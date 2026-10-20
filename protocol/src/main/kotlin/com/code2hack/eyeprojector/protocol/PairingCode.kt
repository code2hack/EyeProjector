package com.code2hack.eyeprojector.protocol

import java.security.SecureRandom

/** Uses elapsed time: a clock adjustment cannot prolong a displayed code. */
class PairingCode(
    private val elapsedMillis: () -> Long,
    private val generate: () -> String = { "%04d".format(java.util.Locale.ROOT, SecureRandom().nextInt(10_000)) },
) {
    data class Display(val code: String, val generation: Long, val expiresAt: Long, val remainingSeconds: Long, val attemptsRemaining: Int)
    private var code = ""
    private var generation = 0L
    private var expiresAt = Long.MIN_VALUE
    private var attempts = 0

    @Synchronized fun display(): Display {
        val now = elapsedMillis()
        if (now >= expiresAt) {
            val old = code
            do { code = generate().also { require(it.matches(Regex("[0-9]{4}"))) } } while (code == old)
            generation++
            attempts = 0
            expiresAt = now + VALIDITY_MILLIS
        }
        return Display(code, generation, expiresAt, (expiresAt - now + 999) / 1000, MAX_ATTEMPTS - attempts)
    }

    /** Owner-approved online-guess budget, including attempts currently in progress. */
    @Synchronized fun beginAttempt(): Display? {
        val shown = display()
        if (shown.attemptsRemaining == 0) return null
        attempts++
        return shown
    }

    @Synchronized fun finishAttempt(shown: Display, success: Boolean) {
        // A completion from the previous code must not alter this code's budget.
        if (success && isCurrent(shown)) attempts--
    }

    @Synchronized fun isCurrent(candidate: Display): Boolean {
        val current = display()
        return candidate.generation == current.generation && candidate.code == current.code
    }

    @Synchronized fun rotate() { expiresAt = Long.MIN_VALUE }

    companion object {
        const val VALIDITY_MILLIS = 300_000L
        const val MAX_ATTEMPTS = 5
    }
}
