package com.code2hack.eyeprojector.directhost

/** Typed launch admission metadata; OwnedHostLifecycle remains the backend's sole lifecycle owner. */
internal class OwnedProjectionRoutes<T> {
    class Owner internal constructor(val epoch: String)
    data class Route<T>(val owner: Owner, val target: T)
    data class Snapshot<T>(val owner: Owner, val route: Route<T>?)
    private val lock = Any()
    private var current: Snapshot<T>? = null

    fun begin(epoch: String): Owner = synchronized(lock) {
        Owner(epoch).also { current = Snapshot(it, null) }
    }

    fun publish(owner: Owner, target: T): Boolean = synchronized(lock) {
        if (current?.owner !== owner) return@synchronized false
        current = Snapshot(owner, Route(owner, target))
        true
    }

    fun snapshot(): Snapshot<T>? = synchronized(lock) { current }

    fun capture(expectedOwnerEpoch: String): Route<T>? = synchronized(lock) {
        current?.takeIf { expectedOwnerEpoch.isNotEmpty() && it.owner.epoch == expectedOwnerEpoch }?.route
    }

    fun invalidate(): Route<T>? = synchronized(lock) {
        current?.route.also { current = null }
    }

    fun retire(owner: Owner) = synchronized(lock) { if (current?.owner === owner) current = null }
}
