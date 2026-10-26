package android.view

/** Borrowed endpoint identity and release accounting only; no Android graphics/camera proof. */
class Surface(val label: String) {
    @Volatile var releaseCount = 0
        private set

    @Synchronized fun release() { releaseCount++ }
    override fun toString() = "Surface($label)"
}
