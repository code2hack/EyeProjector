package com.code2hack.eyeprojector.protocol

/** Optional in-memory observations. Only the isolated test runner consumes reports.
 * No packet body, peer identity, label, pairing code or exception message is accepted. */
class LanDiscoveryDiagnostics {
    data class SocketFacts(val address: String, val addressClass: String, val port: Int,
                           val bound: Boolean, val reuseAddress: Boolean, val broadcast: Boolean)
    data class AddressFacts(val address: String, val addressClass: String, val prefixLength: Int, val broadcast: String?)
    data class InterfaceFacts(val name: String, val up: Boolean, val loopback: Boolean?,
                              val addressesObserved: Boolean, val addresses: List<AddressFacts>)
    data class SendFacts(val host: String, val port: Int, val kind: String, val attempts: Long,
                         val succeeded: Long, val errorClasses: Map<String, Long>)
    data class Snapshot(val role: String?, val socket: SocketFacts?, val counters: Map<String, Long>,
                        val interfaceEnumerationComplete: Boolean, val interfaces: List<InterfaceFacts>,
                        val sends: List<SendFacts>, val errorClasses: Map<String, Long>) {
        fun toJson(): String = discoveryJson(linkedMapOf(
            "role" to role,
            "socket" to socket?.let { linkedMapOf("address" to it.address, "addressClass" to it.addressClass,
                "port" to it.port, "bound" to it.bound, "reuseAddress" to it.reuseAddress, "broadcast" to it.broadcast) },
            "counters" to counters,
            "interfaceEnumerationComplete" to interfaceEnumerationComplete,
            "interfaces" to interfaces.map { network -> linkedMapOf("name" to network.name, "up" to network.up,
                "loopback" to network.loopback, "addressesObserved" to network.addressesObserved,
                "addresses" to network.addresses.map { linkedMapOf("address" to it.address,
                    "addressClass" to it.addressClass, "prefixLength" to it.prefixLength, "broadcast" to it.broadcast) }) },
            "sends" to sends.map { linkedMapOf("host" to it.host, "port" to it.port, "kind" to it.kind,
                "attempts" to it.attempts, "succeeded" to it.succeeded, "errorClasses" to it.errorClasses) },
            "errorClasses" to errorClasses,
        ))
    }

    private class SendState(val host: String, val port: Int, val kind: String) {
        var attempts = 0L
        var succeeded = 0L
        val errors = mutableMapOf<String, Long>()
    }
    private var role: String? = null
    private var socket: SocketFacts? = null
    private var enumerationComplete = false
    private val counters = mutableMapOf<String, Long>()
    private val interfaces = mutableMapOf<String, InterfaceFacts>()
    private val sends = mutableMapOf<Triple<String, Int, String>, SendState>()
    private val errors = mutableMapOf<String, Long>()

    @Synchronized internal fun localRole(value: PeerRole) { role = value.name }
    @Synchronized internal fun count(name: String) { counters[name] = (counters[name] ?: 0L) + 1 }
    @Synchronized internal fun bound(facts: SocketFacts) { socket = facts; count("bindSucceeded") }
    @Synchronized internal fun error(site: String, error: Exception) {
        val key = "$site:${error.javaClass.simpleName}"
        errors[key] = (errors[key] ?: 0L) + 1
    }
    @Synchronized internal fun enumerationStarted() {
        count("interfaceEnumerationAttempts"); enumerationComplete = false; interfaces.clear()
    }
    @Synchronized internal fun interfaceObserved(facts: InterfaceFacts) { interfaces[facts.name] = facts }
    @Synchronized internal fun enumerationFinished() {
        count("interfaceEnumerationSucceeded"); enumerationComplete = true
    }
    @Synchronized internal fun sendAttempt(host: String, port: Int, kind: String) {
        sends.getOrPut(Triple(host, port, kind)) { SendState(host, port, kind) }.attempts++
        count("sendAttempts")
    }
    @Synchronized internal fun sendSucceeded(host: String, port: Int, kind: String) {
        sends.getValue(Triple(host, port, kind)).succeeded++; count("sendSucceeded")
    }
    @Synchronized internal fun sendFailed(host: String, port: Int, kind: String, failure: Exception) {
        val state = sends.getValue(Triple(host, port, kind))
        val name = failure.javaClass.simpleName
        state.errors[name] = (state.errors[name] ?: 0L) + 1
        count("sendFailed"); error("SEND_$kind", failure)
    }

    /** Called by the actual isolated runner callback, after its label predicate. */
    @Synchronized fun labelChecked(matches: Boolean, selected: Boolean) {
        count("labelChecks")
        if (matches) count("labelMatches") else count("labelMismatches")
        if (selected) count("peerSelections")
    }

    @Synchronized fun snapshot(): Snapshot = Snapshot(role, socket, counters.toSortedMap(), enumerationComplete,
        interfaces.values.sortedBy { it.name }.map { it.copy(addresses = it.addresses.toList()) },
        sends.values.sortedWith(compareBy({ it.host }, { it.port }, { it.kind })).map {
            SendFacts(it.host, it.port, it.kind, it.attempts, it.succeeded, it.errors.toSortedMap())
        }, errors.toSortedMap())
}

/** Encoding is platform independent, so host tests exercise the same report bytes. */
private fun discoveryJson(value: Any?): String = when (value) {
    null -> "null"
    is String -> buildString {
        append('"')
        for (char in value) when (char) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (char.code < 32) append("\\u" + char.code.toString(16).padStart(4, '0')) else append(char)
        }
        append('"')
    }
    is Number, is Boolean -> value.toString()
    is Map<*, *> -> value.entries.joinToString(",", "{", "}") { discoveryJson(it.key) + ":" + discoveryJson(it.value) }
    is Iterable<*> -> value.joinToString(",", "[", "]") { discoveryJson(it) }
    else -> error("Unsupported discovery report value")
}
