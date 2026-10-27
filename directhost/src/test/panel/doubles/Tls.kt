package com.code2hack.eyeprojector.protocol

import java.io.File
import java.util.Properties
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

/** Explicit unused TLS boundary; actual Session control/cleanup is exercised in-process. */
object Tls {
    fun context(directory: File): SSLContext = error("TLS creation is outside the Session fixture")
    fun settings(directory: File): Properties = Properties().apply { File(directory, "endpoint.properties").inputStream().use(::load) }
    fun requireTrustedPeer(socket: SSLSocket, directory: File) = Unit
}
