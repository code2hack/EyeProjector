package com.code2hack.eyeprojector.directhost

/** Completes trust deletion only after the caller has verified teardown and copy revocation. */
internal class HostForgetCompletion(private val dispatchToUi: (() -> Unit) -> Unit) {
    private var completion: (() -> Unit)? = null
    private var dispatched = false
    @Synchronized fun request(complete: () -> Unit) { if (completion == null) completion = complete }
    @Synchronized fun pending(): Boolean = completion != null
    @Synchronized fun awaitingTeardown(): Boolean = completion != null && !dispatched

    fun revoked() {
        synchronized(this) {
            if (completion == null || dispatched) return
            dispatched = true
        }
        dispatchToUi {
            try { synchronized(this) { completion }?.invoke() }
            finally { synchronized(this) { completion = null; dispatched = false } }
        }
    }
}
