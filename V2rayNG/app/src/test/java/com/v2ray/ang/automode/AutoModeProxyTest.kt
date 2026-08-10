package com.v2ray.ang.automode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The proxy lists these are parsed from are scraped, not specified. Every shape asserted
 * here was taken from the list the app actually ships with.
 */
class AutoModeProxyTest {

    @Test
    fun `reads the scheme when the line declares one`() {
        val proxy = AutoModeProxy.parse("socks5://1.2.3.4:1080")!!
        assertEquals("1.2.3.4", proxy.host)
        assertEquals(1080, proxy.port)
        assertEquals(ProxyProtocol.SOCKS5, proxy.protocol)
    }

    @Test
    fun `treats socks5h and socks4a as their base protocols`() {
        assertEquals(ProxyProtocol.SOCKS5, AutoModeProxy.parse("socks5h://1.2.3.4:1080")!!.protocol)
        assertEquals(ProxyProtocol.SOCKS4, AutoModeProxy.parse("socks4a://1.2.3.4:4145")!!.protocol)
    }

    @Test
    fun `leaves a bare host and port undeclared rather than guessing a protocol`() {
        val proxy = AutoModeProxy.parse("213.157.6.50:80")!!
        assertEquals(ProxyProtocol.UNKNOWN, proxy.protocol)
    }

    @Test
    fun `reads credentials from both the at form and the colon form`() {
        val at = AutoModeProxy.parse("http://bob:secret@1.2.3.4:8080")!!
        assertEquals("bob", at.username)
        assertEquals("secret", at.password)

        val colons = AutoModeProxy.parse("1.2.3.4:8080:bob:secret")!!
        assertEquals(8080, colons.port)
        assertEquals("bob", colons.username)
        assertEquals("secret", colons.password)
    }

    @Test
    fun `rejects what a list carries besides proxies`() {
        assertNull(AutoModeProxy.parse("# a comment"))
        assertNull(AutoModeProxy.parse(""))
        assertNull(AutoModeProxy.parse("   "))
        assertNull(AutoModeProxy.parse("1.2.3.4"))
        assertNull(AutoModeProxy.parse("1.2.3.4:notaport"))
        assertNull(AutoModeProxy.parse("1.2.3.4:70000"))
        assertNull(AutoModeProxy.parse("vless://something"))
    }

    /**
     * The port is the only evidence a bare line offers, and getting the order right turns
     * three handshakes into one. 4145 is the conventional SOCKS4 port and accounts for a
     * quarter of the bare entries in the shipped list.
     */
    @Test
    fun `orders the probes by what the port conventionally means`() {
        assertEquals(ProxyProtocol.SOCKS4, AutoModeProxy.parse("1.2.3.4:4145")!!.probeOrder().first())
        assertEquals(ProxyProtocol.SOCKS5, AutoModeProxy.parse("1.2.3.4:1080")!!.probeOrder().first())
        assertEquals(ProxyProtocol.SOCKS5, AutoModeProxy.parse("1.2.3.4:10808")!!.probeOrder().first())
        assertEquals(ProxyProtocol.HTTP, AutoModeProxy.parse("1.2.3.4:3128")!!.probeOrder().first())
        assertEquals(ProxyProtocol.HTTP, AutoModeProxy.parse("1.2.3.4:8080")!!.probeOrder().first())
    }

    @Test
    fun `an unconventional port still gets all three guesses`() {
        assertEquals(3, AutoModeProxy.parse("1.2.3.4:31337")!!.probeOrder().size)
    }

    /**
     * A declared protocol is tried once and no more. A handshake a server does not
     * understand usually hangs to its timeout, so a wrong guess is not a cheap mistake.
     */
    @Test
    fun `a declared protocol is not second-guessed`() {
        assertEquals(
            listOf(ProxyProtocol.SOCKS5),
            AutoModeProxy.parse("socks5://1.2.3.4:9999")!!.probeOrder()
        )
    }

    @Test
    fun `parses a mixed list and drops duplicate endpoints`() {
        val list = AutoModeProxy.parseList(
            """
            # header
            http://45.71.186.213:999
            socks5://1.2.3.4:1080
            213.157.6.50:80
            http://213.157.6.50:80
            """.trimIndent()
        )

        assertEquals(3, list.size)
        assertEquals(ProxyProtocol.HTTP, list[0].protocol)
        assertEquals(ProxyProtocol.SOCKS5, list[1].protocol)
        // The bare line came first, so the duplicate that declares HTTP is the one dropped.
        assertEquals(ProxyProtocol.UNKNOWN, list[2].protocol)
    }
}
