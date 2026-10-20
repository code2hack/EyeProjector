package com.code2hack.eyeprojector.protocol

import java.security.SecureRandom

/** Uses elapsed time: a clock adjustment cannot prolong a displayed code. */
class PairingCode(
    private val elapsedMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    private val generate: () -> String = { "%04d".format(java.util.Locale.ROOT, SecureRandom().nextInt(10_000)) },
) {
    data class Display(val code: String, val generation: Long, val expiresAt: Long, val remainingSeconds: Long)
    private var code = ""
    private var generation = 0L
    private var expiresAt = Long.MIN_VALUE

    @Synchronized fun display(): Display {
        val now = elapsedMillis()
        if (now >= expiresAt) {
            val old = code
            do { code = generate().also { require(it.matches(Regex("[0-9]{4}"))) } } while (code == old)
            generation++
            expiresAt = now + VALIDITY_MILLIS
        }
        return Display(code, generation, expiresAt, (expiresAt - now + 999) / 1000)
    }

    @Synchronized fun isCurrent(candidate: Display): Boolean {
        val current = display()
        return candidate.generation == current.generation && candidate.code == current.code
    }

    @Synchronized fun rotate() { expiresAt = Long.MIN_VALUE }

    companion object { const val VALIDITY_MILLIS = 300_000L }
}
