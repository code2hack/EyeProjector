package com.code2hack.eyeprojector.protocol

import java.io.File
import java.util.Properties
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

/**
 * Unused TLS environment boundary for the focused handoff/progress closure. Tests exercise the
 * actual role client with its supplied projection writer; they never connect, read credentials,
 * perform TLS or claim authenticated-network behavior. Actual Tls.kt is deliberately not selected.
 */
object Tls {
    fun settings(directory: File): Properties = error("TLS settings are outside the focused host fixture: $directory")
    fun context(directory: File): SSLContext = error("TLS context is outside the focused host fixture: $directory")
    fun requireTrustedPeer(socket: SSLSocket, directory: File): Unit =
        error("TLS peer validation is outside the focused host fixture: $socket $directory")
}
