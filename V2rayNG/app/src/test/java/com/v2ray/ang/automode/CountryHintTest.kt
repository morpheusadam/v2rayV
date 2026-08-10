package com.v2ray.ang.automode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CountryHintTest {

    @Test
    fun `flag emoji wins over anything spelled out`() {
        assertEquals("NL", CountryHint.fromRemark("🇳🇱 relay via Germany"))
    }

    @Test
    fun `reads a spelled out country name`() {
        assertEquals("DE", CountryHint.fromRemark("Frankfurt Germany 01"))
        assertEquals("US", CountryHint.fromRemark("usa premium"))
    }

    @Test
    fun `reads a bare two letter code`() {
        assertEquals("NL", CountryHint.fromRemark("NL-01 premium"))
    }

    /** The whole reason the bare-code branch is restricted to known codes. */
    @Test
    fun `a traffic quota is not a country`() {
        assertNull(CountryHint.fromRemark("500GB monthly"))
        assertNull(CountryHint.fromRemark("100GB"))
    }

    @Test
    fun `an unrecognised capital pair is not guessed at`() {
        assertNull(CountryHint.fromRemark("XX premium node"))
    }

    @Test
    fun `no country evidence at all`() {
        assertNull(CountryHint.fromRemark("fast node 42"))
        assertNull(CountryHint.fromRemark(""))
        assertNull(CountryHint.fromRemark(null))
    }

    @Test
    fun `reads the measured country out of an ip info string`() {
        assertEquals("NL", CountryHint.fromIpInfo("(NL) 1.2.3.4"))
        assertEquals("DE", CountryHint.fromIpInfo("(de) 5.6.7.8"))
    }

    /** SpeedtestManager writes "(unknown)" when the lookup came back without a country. */
    @Test
    fun `ip info without a real country is not a country`() {
        assertNull(CountryHint.fromIpInfo("(unknown) 1.2.3.4"))
        assertNull(CountryHint.fromIpInfo(""))
        assertNull(CountryHint.fromIpInfo(null))
    }

    @Test
    fun `parses a typed filter into iso codes`() {
        assertEquals(listOf("NL", "DE", "GB"), CountryHint.parseFilter("nl, DE gb"))
    }

    @Test
    fun `accepts a spelled out name in the filter and drops duplicates`() {
        assertEquals(listOf("NL"), CountryHint.parseFilter("Netherlands, NL, holland"))
    }
}
