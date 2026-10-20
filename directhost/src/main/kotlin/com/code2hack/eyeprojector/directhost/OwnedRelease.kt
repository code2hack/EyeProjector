package com.code2hack.eyeprojector.directhost

/** A second close caller waits for the first caller's complete release before returning. */
internal class OwnedRelease {
    private val lock = Any()
    private var released = false
    fun close(release: () -> Unit) = synchronized(lock) {
        if (!released) {
            release()
            released = true
        }
    }
}
