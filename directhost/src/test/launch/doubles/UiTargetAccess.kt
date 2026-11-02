package com.code2hack.eyeprojector.directhost.uiaccess

import java.io.Closeable
/** External enabled Android-service connection only; reader/traversal/resolver remain production. */
object UiTargetAccess {
    data class WindowChange(val displayId: Int, val windowId: Int, val currentChanged: Boolean)
    var currentReader: UiTargetReader? = null
    private val observers = mutableListOf<(WindowChange?) -> Unit>()
    fun reader() = currentReader
    fun observe(callback: (WindowChange?) -> Unit): Closeable { observers.add(callback); return Closeable { observers.remove(callback) } }
    fun event(change: WindowChange?) { observers.toList().forEach { it(change) } }
}
