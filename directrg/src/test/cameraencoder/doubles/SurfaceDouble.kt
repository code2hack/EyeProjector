package android.view

/** Focused host endpoint double; no Android Surface/native camera proof. */
class Surface(val label: String) {
    @Volatile var releaseCount = 0
        private set
    @Volatile var releaseReturnedCount = 0
        private set
    @Volatile var releaseFailure: RuntimeException? = null

    @Synchronized fun release() {
        releaseCount++
        val failure = releaseFailure
        releaseFailure = null
        if (failure != null) throw failure
        releaseReturnedCount++
    }
    override fun toString() = "Surface($label)"
}
