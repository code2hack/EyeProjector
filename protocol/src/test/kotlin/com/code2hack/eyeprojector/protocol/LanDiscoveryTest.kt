package com.code2hack.eyeprojector.protocol

import java.net.InetAddress
import java.net.SocketException
import org.junit.Assert.assertEquals
import org.junit.Test

class LanDiscoveryTest {
    private val addresses = listOf("255.255.255.255", "192.0.2.255", "198.51.100.255")
        .map(InetAddress::getByName)

    private fun assertDestinationFailureIsIsolated(failedIndex: Int) {
        val attempted = mutableListOf<InetAddress>()
        val successful = mutableListOf<InetAddress>()
        var failures = 0
        LanDiscovery.sendToEachAddress(addresses, { address ->
            attempted += address
            if (address == addresses[failedIndex]) throw SocketException("Unreachable route")
            successful += address
        }, { failures++ })
        assertEquals(addresses, attempted)
        assertEquals(addresses.filterIndexed { index, _ -> index != failedIndex }, successful)
        assertEquals(1, failures)
    }

    @Test fun unreachableGlobalBroadcastStillAttemptsDirectedDestinations() {
        assertDestinationFailureIsIsolated(0)
    }

    @Test fun failingDirectedDestinationDoesNotSuppressLaterDestinations() {
        assertDestinationFailureIsIsolated(1)
    }
}
