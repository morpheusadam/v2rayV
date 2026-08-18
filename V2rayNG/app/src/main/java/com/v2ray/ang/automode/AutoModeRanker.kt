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

    /**
     * Country preference, lower is better. Null means the remark said nothing.
     *
     * Iran mode inverts the table rather than reordering it. Every country but one is
     * equally useless there — a bank refuses a German address and a Turkish one alike —
     * so there is nothing to rank among them, and an unlabelled server sorts ahead of a
     * server known to be foreign because it might still turn out to be Iranian.
     */
    fun countryTier(country: String?, iranMode: Boolean = false): Int {
        if (iranMode) {
            return when {
                country == null -> 1
                country.uppercase() == IranMode.COUNTRY -> 0
                else -> 2
            }
        }
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
     *
     * Iran mode swaps the two around, and that swap is the whole point of the mode. There,
     * a server that does not come out in Iran cannot do the job at any speed over any
     * protocol, so country decides first and protocol only separates candidates that could
     * both serve. The country evidence is also wider there — see [IranMode.looksIranian],
     * which reads the address as well as the remark.
     */
    fun score(profile: ProfileItem, iranMode: Boolean = false): Int {
        if (iranMode) {
            val country = if (IranMode.looksIranian(profile)) IranMode.COUNTRY else CountryHint.fromRemark(profile.remarks)
            return countryTier(country, true) * 10 + protocolTier(profile)
        }
        return protocolTier(profile) * 10 + countryTier(CountryHint.fromRemark(profile.remarks))
    }

    /**
     * Orders candidates best-tier first, shuffled inside each tier.
     *
     * The shuffle is what keeps this from collapsing into "test the same servers every
     * run": within a tier the choice is still random, so a run explores rather than
     * re-confirming.
     */
    fun <T> prioritise(items: List<T>, iranMode: Boolean = false, profileOf: (T) -> ProfileItem): List<T> =
        items.shuffled().sortedBy { score(profileOf(it), iranMode) }

    /**
     * Final tiebreak among servers that all cleared the speed bar.
     *
     * Throughput still decides — a fast server in an unpreferred country beats a slow one
     * in Germany, because the user asked for a working connection rather than a passport.
     * Country only separates servers that measured close to each other, which is why the
     * speed is bucketed before it is compared.
     *
     * Iran mode reverses that too: there the country is not a preference to be outweighed
     * by throughput, it is the requirement, so it is compared before the speed and a
     * foreign server can never sort ahead of an Iranian one.
     */
    fun compareWinners(a: AutoModeMeasurement, b: AutoModeMeasurement, iranMode: Boolean = false): Int {
        val countryA = countryTier(countryOf(a, iranMode), iranMode)
        val countryB = countryTier(countryOf(b, iranMode), iranMode)
        if (iranMode && countryA != countryB) {
            return countryA - countryB
        }

        val bucketA = speedBucket(a.speedMbps)
        val bucketB = speedBucket(b.speedMbps)
        if (bucketA != bucketB) {
            return bucketB - bucketA
        }

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
     * The country a measured server is judged by: what came out of the tunnel, falling
     * back to the provider's claim. Iran mode also lets the address speak, since a server
     * on an Iranian address block is an Iranian exit whatever its remark says.
     */
    private fun countryOf(measurement: AutoModeMeasurement, iranMode: Boolean): String? {
        measurement.exitCountry?.let { return it }
        // Ahead of the remark rather than after it: a server on an Iranian address block
        // that a provider labelled "DE" is an Iranian exit with a careless label, and in
        // this mode believing the label would throw away the only candidate there is.
        if (iranMode && IranMode.isIranianHost(measurement.profile.server)) {
            return IranMode.COUNTRY
        }
        return CountryHint.fromRemark(measurement.profile.remarks)
    }

    /**
     * Half-MB/s buckets. Two servers a tenth of a megabyte apart are the same server as
     * far as a user is concerned, and treating them as ordered would let noise outrank
     * everything else that is known about them.
     */
    fun speedBucket(mbps: Double): Int = (mbps * 2).toInt()
}
