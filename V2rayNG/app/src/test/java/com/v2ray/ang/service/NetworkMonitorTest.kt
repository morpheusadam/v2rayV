package com.v2ray.ang.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule that decides whether an address change is a handover.
 *
 * Everything else in [NetworkMonitor] is Android callback plumbing; this is the judgement, and
 * the cases below are the ones that were actually wrong before it existed.
 */
class NetworkMonitorTest {

    @Test
    fun `no baseline yet is never a handover`() {
        assertFalse(NetworkMonitor.hasLostAddress(emptySet(), setOf("192.168.1.5")))
        assertFalse(NetworkMonitor.hasLostAddress(emptySet(), emptySet()))
    }

    @Test
    fun `addresses settling in after connect is not a handover`() {
        // A link-local address arrives first, then DHCP, then a global IPv6. Three callbacks,
        // three different sets, nothing moved.
        val linkLocal = setOf("fe80::1")
        val withDhcp = setOf("fe80::1", "192.168.1.5")
        val withGlobalV6 = setOf("fe80::1", "192.168.1.5", "2001:db8::1")

        assertFalse(NetworkMonitor.hasLostAddress(linkLocal, withDhcp))
        assertFalse(NetworkMonitor.hasLostAddress(withDhcp, withGlobalV6))
    }

    @Test
    fun `an unchanged set is not a handover`() {
        val addresses = setOf("192.168.1.5", "fe80::1")
        assertFalse(NetworkMonitor.hasLostAddress(addresses, addresses))
    }

    @Test
    fun `a new lease replacing the old address is a handover`() {
        assertTrue(
            NetworkMonitor.hasLostAddress(
                setOf("192.168.1.5", "fe80::1"),
                setOf("192.168.1.9", "fe80::1"),
            )
        )
    }

    @Test
    fun `the carrier re-assigning the mobile address is a handover`() {
        assertTrue(NetworkMonitor.hasLostAddress(setOf("10.34.7.2"), setOf("10.51.220.8")))
    }

    @Test
    fun `losing every address is a handover`() {
        assertTrue(NetworkMonitor.hasLostAddress(setOf("192.168.1.5"), emptySet()))
    }

    @Test
    fun `dropping IPv4 and keeping IPv6 is a handover`() {
        // The core's outbound sockets may be bound to the v4 address; keeping v6 does not save them.
        assertTrue(
            NetworkMonitor.hasLostAddress(
                setOf("192.168.1.5", "2001:db8::1"),
                setOf("2001:db8::1"),
            )
        )
    }
}
