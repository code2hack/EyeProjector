package com.code2hack.eyeprojector.directhost.apps
import android.content.Context
import java.io.Closeable
/** Canonical chosen read/observe boundary double; Android persistence is not exercised. */
object ChosenApps {
    data class App(val packageName: String)
    var selected = setOf("example.chosen")
    private val observers = linkedSetOf<() -> Unit>()
    class Store { fun read() = selected.map { App(it) } }
    fun open(context: Context) = Store()
    fun observe(context: Context, onChange: () -> Unit): Closeable {
        observers.add(onChange)
        return Closeable { observers.remove(onChange) }
    }
    fun changed() { observers.toList().forEach { it() } }
}
