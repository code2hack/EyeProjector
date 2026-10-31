package com.code2hack.eyeprojector.directhost.anchor.preferences

import java.io.Closeable

/** Atomic complete-value writes; an I/O failure throws without publishing a partial value. */
interface AppAnchorStorage {
    fun read(packageName: String): AppAnchor?
    fun write(packageName: String, anchor: AppAnchor?)
}

/** A committed preference transition, not a touch/contact result. */
sealed interface AppAnchorChange {
    val packageName: String
    val previous: AppAnchor?

    data class Replaced(override val previous: AppAnchor?, val current: AppAnchor) : AppAnchorChange {
        override val packageName: String get() = current.packageName
    }

    data class Cleared(override val previous: AppAnchor) : AppAnchorChange {
        override val packageName: String get() = previous.packageName
    }
}

/**
 * Use AppAnchorPreferences.open in Android: all UI and future contact owners share its process-wide
 * instance. This class owns preferences only, never a Function/contact/session or live UI target.
 *
 * Reads, writes and subscription changes serialize on this instance. A successful atomic storage
 * write precedes synchronous package-filtered callbacks, before the mutation returns or another
 * thread can read/mutate the store. Callbacks may read and unsubscribe; they must not mutate this
 * store or wait for another thread using it. Closing a subscription prevents later callbacks.
 *
 * A future shared contact owner must subscribe for its active app for its whole input-context
 * lifetime and retire the original contact synchronously on Replaced/Cleared before further use.
 * This seam alone does not retire a held contact. No events replay on open/restart/subscription;
 * read the current preference separately. No chosen-app membership or notification/scale changes
 * flow through here. Cancel/incomplete registration must never call replace.
 */
class AppAnchorStore(
    private val storage: AppAnchorStorage,
    private val onObserverFailure: (RuntimeException) -> Unit = { it.printStackTrace() },
) {
    private class Observer(val packageName: String, val changed: (AppAnchorChange) -> Unit)
    private val observers = mutableListOf<Observer>()

    @Synchronized fun read(packageName: String): AppAnchor? = storage.read(packageName)

    /** Each completed replacement emits an event, even when the descriptor/mode are unchanged. */
    @Synchronized fun replace(anchor: AppAnchor) {
        val previous = storage.read(anchor.packageName)
        storage.write(anchor.packageName, anchor)
        publish(AppAnchorChange.Replaced(previous, anchor))
    }

    /** Missing is already clear; no write or event. Chosen-app removal never calls this method. */
    @Synchronized fun clear(packageName: String) {
        val previous = storage.read(packageName) ?: return
        storage.write(packageName, null)
        publish(AppAnchorChange.Cleared(previous))
    }

    @Synchronized fun observe(packageName: String, changed: (AppAnchorChange) -> Unit): Closeable {
        val observer = Observer(packageName, changed)
        observers.add(observer)
        return Closeable { synchronized(this) { observers.remove(observer) } }
    }

    private fun publish(change: AppAnchorChange) {
        observers.toList().filter { it.packageName == change.packageName }.forEach { observer ->
            if (observer in observers) {
                // Persistence already succeeded. A broken consumer cannot roll it back or keep
                // other consumers from receiving the committed event. Report that consumer failure.
                try { observer.changed(change) } catch (failure: RuntimeException) { onObserverFailure(failure) }
            }
        }
    }
}
