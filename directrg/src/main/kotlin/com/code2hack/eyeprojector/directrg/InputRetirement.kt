package com.code2hack.eyeprojector.directrg

/** An original cleanup obligation; finishing never grants or restricts input admission. */
internal class InputRetirement(finishOriginal: () -> Unit, val superseded: () -> Boolean) {
    private var finished = false
    val finishOriginal: () -> Unit = {
        if (!finished) {
            finished = true // Repeated/reentrant completion cannot repeat an old output attempt.
            finishOriginal()
        }
    }

    companion object {
        /** Complete every detached obligation, retaining the first failure and all later failures. */
        fun finishAll(actions: Iterable<() -> Unit>) {
            var failure: Throwable? = null
            for (action in actions) try { action() }
            catch (error: Throwable) {
                val first = failure
                if (first == null) failure = error else if (first !== error) first.addSuppressed(error)
            }
            failure?.let { throw it }
        }
    }
}
