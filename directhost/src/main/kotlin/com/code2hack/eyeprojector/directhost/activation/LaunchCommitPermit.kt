package com.code2hack.eyeprojector.directhost.activation

import java.util.concurrent.atomic.AtomicReference

/** App-local one-use selection commitment. Retirement never rolls back an already admitted effect. */
internal class LaunchCommitPermit {
    enum class State { PENDING, COMMITTED, RETIRED }
    private val current = AtomicReference(State.PENDING)
    val state: State get() = current.get()
    fun retire(): Boolean = current.compareAndSet(State.PENDING, State.RETIRED)
    internal fun tryCommit(): Boolean = current.compareAndSet(State.PENDING, State.COMMITTED)
}
