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

    /**
     * The digit guard only ever worked when the digit touched the letters. Free lists write
     * the quota with a space at least as often as without, and `GB` is both the commonest
     * unit on those lists and a country code — so the spaced form was read as Great Britain
     * with full confidence. In Iran mode that is not a mislabel, it is a deletion: anything
     * labelled elsewhere is dropped before it is ever tested.
     */
    @Test
    fun `a traffic quota with a space is still not a country`() {
        assertNull(CountryHint.fromRemark("500 GB monthly"))
        assertNull(CountryHint.fromRemark("100 GB"))
        assertNull(CountryHint.fromRemark("premium | 50 GB | 30 days"))
    }

    /** The quota must not shadow a real code that appears later in the same remark. */
    @Test
    fun `a real code still wins over a quota unit beside it`() {
        assertEquals("IR", CountryHint.fromRemark("premium | 50 GB | IR"))
        assertEquals("DE", CountryHint.fromRemark("100 GB DE-04"))
    }

    /** Great Britain must still be read when it is genuinely meant. */
    @Test
    fun `great britain is still read when it is a country`() {
        assertEquals("GB", CountryHint.fromRemark("GB-01 london"))
        assertEquals("GB", CountryHint.fromRemark("fast node GB"))
    }

    /**
     * The separators these lists actually use. A plain `\s` misses every one of these, and
     * the non-breaking space in particular survives a copy-paste out of a channel post,
     * which is where most of these remarks come from.
     */
    @Test
    fun `a quota is still a quota across the separators lists use`() {
        assertEquals("IR", CountryHint.fromRemark("MCI 50-GB | IR"))
        assertEquals("IR", CountryHint.fromRemark("node 50_GB IR"))
        assertEquals("IR", CountryHint.fromRemark("MTN 20 GB | IR"))
        assertEquals("IR", CountryHint.fromRemark("plan 1.5–TB IR"))
    }

    /**
     * Forms with no number to anchor on. Stripping cannot see them, so the rule that GB
     * loses to any other code in the same remark is what catches these.
     */
    @Test
    fun `an allowance with no figure does not become great britain`() {
        assertEquals("IR", CountryHint.fromRemark("Unlimited GB | IR"))
        assertEquals("IR", CountryHint.fromRemark("traffic GB IR"))
        assertEquals("IR", CountryHint.fromRemark("GB 500 IR"))
        assertEquals("DE", CountryHint.fromRemark("data GB · DE-04"))
    }

    /** With nothing else to go on, an allowance word still is not a country. */
    @Test
    fun `an allowance word alone is not a country`() {
        assertNull(CountryHint.fromRemark("unlimited GB"))
        assertNull(CountryHint.fromRemark("traffic GB monthly"))
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
