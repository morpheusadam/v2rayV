package com.v2ray.ang.automode

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType

/**
 * Decides which candidates are worth spending tests on, before any test is run.
 *
 * This is not the same thing as ranking by measured latency, which was tried and was
 * *worse than random* — the fastest-answering hosts turned out to be CDN edges fronting
 * dead proxies. That finding stands and nothing here changes it.
 *
 * What this does instead is order candidates by what is already known about them from the
 * config itself, before a single packet is sent. Two pieces of prior evidence, both cheap
 * to read and neither of them a measurement:
 *
 *  - **Protocol.** Against Iran's DPI in 2026, VLESS with REALITY (and XTLS-Vision flow)
 *    is reported to survive where others do not, with Hysteria2 and TUIC — both QUIC —
 *    next, and WireGuard and OpenVPN reliably detected. A VMess-over-plain-TCP entry from
 *    a scraped list is not worth the eight seconds a speed test costs when a REALITY entry
 *    is sitting next to it untested.
 *  - **Country.** Latency to Iran is geography, and geography does not change between
 *    runs. Germany, the Netherlands, France and Turkey are the locations that actually
 *    carry traffic well from there.
 *
 * Randomness is kept *within* each tier rather than removed. That preserves the property
 * the funnel was built around — a run samples differently each time, so a source that is
 * having a bad day does not poison every future run — while spending the budget on the
 * half of the pool that has a chance.
 */
object AutoModeRanker {

    /**
     * Countries to prefer, best first. Ordered by the operator's own experience rather
     * than by raw distance: Turkey has the lowest ping of the four but the least capacity,
     * so it sorts behind the European locations that hold a speed test up.
     */
    val PREFERRED_COUNTRIES: List<String> = listOf("DE", "NL", "FR", "TR")

    /** Second rank: nearby European locations that are usually fine, just less reliably. */
    private val SECONDARY_COUNTRIES: Set<String> =
        setOf("AT", "CH", "PL", "SE", "FI", "GB", "IE", "CZ", "RO", "LT", "LV", "EE", "IT", "ES", "BE", "DK", "NO", "HU", "BG")

    /**
     * Protocol preference, lower is better. Derived from what is reported to survive
     * Iranian DPI rather than from what is fastest on an open network.
     */
    fun protocolTier(profile: ProfileItem): Int = when (profile.configType) {
        EConfigType.VLESS -> if (isReality(profile)) 0 else 2
        EConfigType.HYSTERIA2 -> 1
        EConfigType.TROJAN -> if (isReality(profile)) 1 else 3
        EConfigType.VMESS -> 4
        EConfigType.SHADOWSOCKS -> 4
        EConfigType.HYSTERIA -> 5
        // Detected reliably by modern DPI, and not worth a slot while anything else is left.
        EConfigType.WIREGUARD -> 6
        EConfigType.SOCKS, EConfigType.HTTP -> 6
        else -> 5
    }

    /** True for REALITY, or for XTLS-Vision, which only exists on the configs that matter. */
    fun isReality(profile: ProfileItem): Boolean {
        val security = profile.security?.lowercase()
        if (security == "reality") {
            return true
        }
        return profile.flow?.contains("vision", ignoreCase = true) == true
    }

    /** Country preference, lower is better. Null means the remark said nothing. */
    fun countryTier(country: String?): Int {
        if (country == null) {
            // Deliberately mid-table rather than last. Plenty of good servers carry a
            // remark that names no country, and burying them would throw away the pool
            // the measured-country check later exists to sort out.
            return PREFERRED_COUNTRIES.size + 1
        }
        val preferred = PREFERRED_COUNTRIES.indexOf(country.uppercase())
        if (preferred >= 0) {
            return preferred
        }
        return if (SECONDARY_COUNTRIES.contains(country.uppercase())) {
            PREFERRED_COUNTRIES.size
        } else {
            PREFERRED_COUNTRIES.size + 2
        }
    }

    /**
     * The score a candidate is ordered by. Protocol dominates country: a REALITY server in
     * an unnamed country beats a plain VMess one that claims to be in Germany, because the
     * first might work and the second probably will not.
     */
    fun score(profile: ProfileItem): Int =
        protocolTier(profile) * 10 + countryTier(CountryHint.fromRemark(profile.remarks))

    /**
     * Orders candidates best-tier first, shuffled inside each tier.
     *
     * The shuffle is what keeps this from collapsing into "test the same servers every
     * run": within a tier the choice is still random, so a run explores rather than
     * re-confirming.
     */
    fun <T> prioritise(items: List<T>, profileOf: (T) -> ProfileItem): List<T> =
        items.shuffled().sortedBy { score(profileOf(it)) }

    /**
     * Final tiebreak among servers that all cleared the speed bar.
     *
     * Throughput still decides — a fast server in an unpreferred country beats a slow one
     * in Germany, because the user asked for a working connection rather than a passport.
     * Country only separates servers that measured close to each other, which is why the
     * speed is bucketed before it is compared.
     */
    fun compareWinners(a: AutoModeMeasurement, b: AutoModeMeasurement): Int {
        val bucketA = speedBucket(a.speedMbps)
        val bucketB = speedBucket(b.speedMbps)
        if (bucketA != bucketB) {
            return bucketB - bucketA
        }

        val countryA = countryTier(a.exitCountry ?: CountryHint.fromRemark(a.profile.remarks))
        val countryB = countryTier(b.exitCountry ?: CountryHint.fromRemark(b.profile.remarks))
        if (countryA != countryB) {
            return countryA - countryB
        }

        val protocolA = protocolTier(a.profile)
        val protocolB = protocolTier(b.profile)
        if (protocolA != protocolB) {
            return protocolA - protocolB
        }

        return a.delayMillis.compareTo(b.delayMillis)
    }

    /**
     * Half-MB/s buckets. Two servers a tenth of a megabyte apart are the same server as
     * far as a user is concerned, and treating them as ordered would let noise outrank
     * everything else that is known about them.
     */
    fun speedBucket(mbps: Double): Int = (mbps * 2).toInt()
}
