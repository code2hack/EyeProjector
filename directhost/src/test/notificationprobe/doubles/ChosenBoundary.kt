package com.code2hack.eyeprojector.directhost.apps

import android.content.Context
import android.os.Looper

object ChosenApps {
    data class ChosenApp(val packageName: String)
    class Store(private val context: Context) {
        fun read(): List<ChosenApp> {
            Looper.requireMain(); context.eligibilityReads++
            context.eligibilityFailure?.let { throw it }
            return context.chosen.map(::ChosenApp)
        }
    }
    fun open(context: Context) = Store(context)
}
