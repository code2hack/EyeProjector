package com.code2hack.eyeprojector.directhost.anchor.preferences

import java.io.Closeable
import java.util.UUID

/** Atomic complete-value writes; an I/O failure throws without publishing a partial value. */
interface AppAnchorStorage {
    fun read(packageName: String): AppAnchor?
    fun write(packageName: String, anchor: AppAnchor?)
}

/** Process-instance/package/revision identity; never persisted or reused as contact identity. */
data class AnchorSettingContext(val instanceId: String, val packageName: String, val revision: Long)
data class AppAnchorSetting(val context: AnchorSettingContext, val anchor: AppAnchor?)

/** An immutable committed preference transition, not a touch/contact result. */
sealed interface AppAnchorChange {
    val context: AnchorSettingContext
    val packageName: String get() = context.packageName
    val previous: AppAnchor?

    data class Replaced(override val previous: AppAnchor?, val current: AppAnchor,
        override val context: AnchorSettingContext) : AppAnchorChange

    data class Cleared(override val previous: AppAnchor,
        override val context: AnchorSettingContext) : AppAnchorChange
}

/**
 * Android consumers share AppAnchorPreferences.open's app-process instance. Preferences only: no
 * Function/contact/session ownership. Cancel/incomplete registration must never call replace.
 *
 * Complete writes, snapshots, revisions and the event queue serialize on state. External observers
 * run OUTSIDE that monitor. One caller drains immutable events in commit order, including nested
 * mutations; callbacks may compose with reads/mutations on other threads without holding state.
 * While a callback is busy, another mutation can commit/enqueue and return before its own delivery.
 * A read may therefore observe a newer value than the callback's immutable event. No event replays
 * on restart/subscription. Close removes future deliveries; an already selected callback may finish.
 *
 * snapshot provides a package-scoped context even when absent. Each successful mutation advances
 * that package's revision; isCurrent compares it within this instance. A future shared contact
 * owner must subscribe for its input-context lifetime, serialize retirement/use itself, and admit
 * use only for the current setting context it has handled. Read/mutation return or event enqueue
 * is NOT contact-retirement acknowledgment. This seam currently retires no held contact. Contexts
 * reset on a new store/process; live contacts must never be restored from persisted preferences.
 */
class AppAnchorStore(
    private val storage: AppAnchorStorage,
    private val onObserverFailure: (RuntimeException) -> Unit = { it.printStackTrace() },
) {
    private class Observer(val packageName: String, val changed: (AppAnchorChange) -> Unit)
    private data class Delivery(val change: AppAnchorChange, val observers: List<Observer>)
    private val state = Any()
    private val instanceId = UUID.randomUUID().toString()
    private val revisions = mutableMapOf<String, Long>()
    private val observers = mutableListOf<Observer>()
    private val pending = ArrayDeque<Delivery>()
    private var delivering = false

    fun read(packageName: String): AppAnchor? = snapshot(packageName).anchor

    fun snapshot(packageName: String): AppAnchorSetting = synchronized(state) {
        AppAnchorSetting(context(packageName), storage.read(packageName))
    }

    fun isCurrent(context: AnchorSettingContext): Boolean = synchronized(state) {
        context == context(context.packageName)
    }

    /** Every completed replacement emits, even for equal descriptor/mode values. */
    fun replace(anchor: AppAnchor): AnchorSettingContext {
        val (changeContext, drain) = synchronized(state) {
            val previous = storage.read(anchor.packageName)
            storage.write(anchor.packageName, anchor)
            val next = advance(anchor.packageName)
            next to enqueue(AppAnchorChange.Replaced(previous, anchor, next))
        }
        if (drain) deliver()
        return changeContext
    }

    /** Missing is already clear; no write/event/revision. Chosen removal never calls Clear. */
    fun clear(packageName: String): AnchorSettingContext {
        val (changeContext, drain) = synchronized(state) {
            val previous = storage.read(packageName) ?: return context(packageName)
            storage.write(packageName, null)
            val next = advance(packageName)
            next to enqueue(AppAnchorChange.Cleared(previous, next))
        }
        if (drain) deliver()
        return changeContext
    }

    fun observe(packageName: String, changed: (AppAnchorChange) -> Unit): Closeable {
        val observer = Observer(packageName, changed)
        synchronized(state) { observers.add(observer) }
        return Closeable { synchronized(state) { observers.remove(observer) } }
    }

    private fun context(packageName: String) = AnchorSettingContext(instanceId, packageName, revisions[packageName] ?: 0)

    private fun advance(packageName: String): AnchorSettingContext {
        revisions[packageName] = (revisions[packageName] ?: 0) + 1
        return context(packageName)
    }

    /** Called only under state; recipients belong to the subscription set at commit time. */
    private fun enqueue(change: AppAnchorChange): Boolean {
        pending.addLast(Delivery(change, observers.filter { it.packageName == change.packageName }))
        if (delivering) return false
        delivering = true
        return true
    }

    private fun deliver() {
        while (true) {
            val delivery = synchronized(state) {
                if (pending.isEmpty()) { delivering = false; return }
                pending.removeFirst()
            }
            delivery.observers.forEach { observer ->
                val active = synchronized(state) { observer in observers }
                if (active) {
                    // Commit already succeeded. Consumer failures cannot undo it or starve peers.
                    try { observer.changed(delivery.change) }
                    catch (failure: RuntimeException) { onObserverFailure(failure) }
                }
            }
        }
    }
}
