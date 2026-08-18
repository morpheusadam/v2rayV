package com.v2ray.ang.automode

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The order candidates are tested in. This is prior knowledge read off the config, not a
 * measurement — the finding that ranking by *measured* latency performs worse than random
 * still stands and is not what this does.
 */
class AutoModeRankerTest {

    private fun profile(
        type: EConfigType = EConfigType.VLESS,
        security: String? = null,
        flow: String? = null,
        remarks: String = "",
    ) = ProfileItem(
        configType = type,
        remarks = remarks,
        server = "1.2.3.4",
        serverPort = "443",
        security = security,
        flow = flow,
    )

    @Test
    fun `reality outranks every other protocol`() {
        val reality = AutoModeRanker.protocolTier(profile(security = "reality"))
        assertTrue(reality < AutoModeRanker.protocolTier(profile(type = EConfigType.HYSTERIA2)))
        assertTrue(reality < AutoModeRanker.protocolTier(profile()))
        assertTrue(reality < AutoModeRanker.protocolTier(profile(type = EConfigType.VMESS)))
        assertTrue(reality < AutoModeRanker.protocolTier(profile(type = EConfigType.WIREGUARD)))
    }

    /** XTLS-Vision only appears on the configs that carry REALITY, so it counts as one. */
    @Test
    fun `the vision flow is read as reality`() {
        assertTrue(AutoModeRanker.isReality(profile(flow = "xtls-rprx-vision")))
        assertTrue(AutoModeRanker.isReality(profile(security = "reality")))
        assertTrue(!AutoModeRanker.isReality(profile(security = "tls")))
    }

    @Test
    fun `wireguard sorts last, being the one modern DPI reliably catches`() {
        val wg = AutoModeRanker.protocolTier(profile(type = EConfigType.WIREGUARD))
        assertTrue(wg >= AutoModeRanker.protocolTier(profile(type = EConfigType.VMESS)))
    }

    @Test
    fun `the preferred countries rank in the configured order`() {
        val tiers = AutoModeRanker.PREFERRED_COUNTRIES.map { AutoModeRanker.countryTier(it) }
        assertEquals(listOf(0, 1, 2, 3), tiers)
    }

    /**
     * An unlabelled server sits mid-table rather than last. Plenty of good servers name no
     * country, and burying them would throw away the pool the measured-country check later
     * exists to sort out.
     */
    @Test
    fun `an unnamed country beats a bad one but loses to a preferred one`() {
        val unknown = AutoModeRanker.countryTier(null)
        assertTrue(unknown > AutoModeRanker.countryTier("DE"))
        assertTrue(unknown < AutoModeRanker.countryTier("CN"))
    }

    /**
     * Protocol dominates country: a REALITY server that names no country beats a plain
     * VMess one claiming Germany, because the first might work and the second probably
     * will not.
     */
    @Test
    fun `protocol outweighs country`() {
        val realityUnknown = AutoModeRanker.score(profile(security = "reality"))
        val vmessGermany = AutoModeRanker.score(profile(type = EConfigType.VMESS, remarks = "🇩🇪 Germany"))
        assertTrue(realityUnknown < vmessGermany)
    }

    @Test
    fun `prioritise puts the best tier first`() {
        val items = listOf(
            profile(type = EConfigType.WIREGUARD),
            profile(type = EConfigType.VMESS),
            profile(security = "reality", remarks = "🇩🇪"),
            profile(type = EConfigType.HYSTERIA2),
        )
        val ordered = AutoModeRanker.prioritise(items) { it }
        assertEquals(EConfigType.VLESS, ordered.first().configType)
        assertEquals(EConfigType.WIREGUARD, ordered.last().configType)
    }

    private fun measured(speed: Double, country: String?, delay: Long = 100) = AutoModeMeasurement(
        guid = "g$speed$country",
        profile = profile(remarks = ""),
        speedMbps = speed,
        delayMillis = delay,
        exitCountry = country,
    )

    /**
     * Speed still decides. A user asked for a working connection, not a passport, so a
     * clearly faster server in an unpreferred country wins.
     */
    @Test
    fun `a clearly faster server wins regardless of country`() {
        val fastElsewhere = measured(9.0, "SG")
        val slowGermany = measured(1.0, "DE")
        assertTrue(AutoModeRanker.compareWinners(fastElsewhere, slowGermany) < 0)
    }

    /**
     * Within a half-megabyte bucket the two are the same server as far as anyone can feel,
     * so the country breaks the tie instead of measurement noise.
     */
    @Test
    fun `country breaks a tie between servers of the same speed`() {
        val germany = measured(5.0, "DE")
        val singapore = measured(5.1, "SG")
        assertTrue(AutoModeRanker.compareWinners(germany, singapore) < 0)
    }

    @Test
    fun `half a megabyte per second is one bucket`() {
        assertEquals(AutoModeRanker.speedBucket(5.0), AutoModeRanker.speedBucket(5.4))
        assertTrue(AutoModeRanker.speedBucket(5.0) < AutoModeRanker.speedBucket(5.6))
    }

    /**
     * Iran mode flattens the country table: every country but one is equally useless, and
     * an unlabelled server sorts ahead of a known-foreign one because it might yet be
     * Iranian.
     */
    @Test
    fun `iran mode ranks iran first and everywhere else alike`() {
        assertTrue(AutoModeRanker.countryTier("IR", true) < AutoModeRanker.countryTier(null, true))
        assertTrue(AutoModeRanker.countryTier(null, true) < AutoModeRanker.countryTier("DE", true))
        assertEquals(AutoModeRanker.countryTier("DE", true), AutoModeRanker.countryTier("NL", true))
    }

    /**
     * And it swaps the two priorities around. Normally protocol dominates, because a
     * REALITY server that might work beats a plain VMess one that probably will not. Here
     * a REALITY server in Germany cannot do the job at all, so country goes first.
     */
    @Test
    fun `iran mode scores country above protocol`() {
        val iranianVmess = AutoModeRanker.score(
            profile(type = EConfigType.VMESS, remarks = "🇮🇷 Tehran"), iranMode = true
        )
        val germanReality = AutoModeRanker.score(
            profile(security = "reality", remarks = "🇩🇪 Frankfurt"), iranMode = true
        )
        assertTrue(iranianVmess < germanReality)
    }

    /** A foreign server never sorts ahead of an Iranian one, however much faster it is. */
    @Test
    fun `iran mode puts an iranian server ahead of a faster foreign one`() {
        val fastGermany = measured(9.0, "DE")
        val slowIran = measured(0.4, "IR")
        assertTrue(AutoModeRanker.compareWinners(slowIran, fastGermany, iranMode = true) < 0)
        assertTrue(AutoModeRanker.compareWinners(fastGermany, slowIran) < 0)
    }
}
