package com.v2ray.ang.automode

import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.util.LogUtil

/**
 * Iran mode: connect *into* Iran rather than out of it.
 *
 * Every other part of Auto Mode exists to get a user inside Iran out to the open
 * internet. This is the opposite journey, and it has a different set of users: Iranians
 * living abroad, whose bank, insurer, tax office and domestic app store all refuse a
 * foreign address outright. For them a German REALITY server measuring 12 MB/s is not a
 * good result, it is the wrong answer quickly — the bank looks at where the request came
 * from and stops there.
 *
 * So the mode changes four things, and each of them follows from that one fact:
 *
 *  - **Only Iranian exits are kept.** Not "preferred, then topped up with the fastest
 *    found anywhere", which is what the country filter does — that fallback is exactly
 *    the silent failure this mode has to avoid. A run that finds nothing Iranian says so.
 *  - **Iranian exits are what the run spends its budget on.** Country outranks protocol
 *    here, the reverse of the usual order, because the fastest server that cannot do the
 *    job is worth less than a slow one that can.
 *  - **The config has to be safe.** See [isSecure]. Traffic through this tunnel is
 *    banking traffic, and it is carried by a machine sitting inside the network the user
 *    is reaching through. A config with no encryption, or with certificate checking
 *    switched off, is not merely worse — it exposes exactly what it was chosen to carry.
 *  - **Iranian traffic stops bypassing the tunnel.** The Iran routing preset sends
 *    `geoip:ir` and `geosite:category-ir` straight out of the phone, which is right for
 *    someone in Iran and defeats this mode entirely: the bank would see the user's
 *    foreign address anyway and nothing would look wrong. See [trimIranBypass].
 *
 * The evidence rule is the one the rest of the pipeline already follows: a label decides
 * what is worth testing, and a measurement decides what is kept.
 */
object IranMode {

    /** ISO code of the only country this mode accepts. */
    const val COUNTRY = "IR"

    /**
     * Fraction of the user's own line an Iranian server has to reach to be worth keeping.
     *
     * Far below the 70% a normal run demands, and not because the bar is being lowered
     * until the feature appears to work. Iran's international capacity is throttled at the
     * border, so a server in Tehran reached from Frankfurt is limited by that link rather
     * than by the server — no Iranian exit will ever deliver most of a European line. The
     * question this mode actually asks is whether it carries a bank page, and a tenth of a
     * normal connection answers it.
     */
    const val ACCEPT_FRACTION = 0.10

    // ---- security ---------------------------------------------------------------

    /**
     * Whether a server is safe enough to carry this particular traffic.
     *
     * Worth being precise about what is being defended, because a proxy cannot protect the
     * user from the proxy. Whoever runs the server always sees the connection; what is at
     * stake is everyone *else* on the path — the operator's ISP, the transit between the
     * phone and Iran, anyone able to sit in the middle of it. A bank session is HTTPS end
     * to end, so those parties see ciphertext either way; a config with no transport
     * encryption still leaks every hostname visited, and a config whose TLS does not check
     * certificates invites the interception that would break the HTTPS underneath as well.
     *
     * Three refusals, then:
     *
     *  - **No encryption at all.** SOCKS and HTTP proxies carry the request in clear.
     *    Shadowsocks with `none`/`plain`, and VMess with `none`/`zero`, are the same thing
     *    under a different name.
     *  - **TLS that checks nothing.** `allowInsecure` accepts any certificate presented,
     *    which was the whole of what TLS was doing here.
     *  - **VLESS or Trojan without a TLS layer.** VLESS has no encryption of its own — its
     *    security *is* the TLS or REALITY layer — so a `security=none` VLESS entry is a
     *    plaintext tunnel with a modern name on it.
     *
     * What survives is either TLS-based (Trojan, Hysteria2) or carries its own
     * authenticated encryption under a pre-shared key (VMess with an AEAD cipher,
     * Shadowsocks AEAD, WireGuard's Noise handshake).
     */
    fun isSecure(profile: ProfileItem): Boolean {
        if (profile.insecure == true) {
            return false
        }

        return when (profile.configType) {
            // Nothing on the wire but the request itself.
            EConfigType.SOCKS, EConfigType.HTTP -> false

            // No transport of its own; TLS or REALITY is the entire protection.
            EConfigType.VLESS, EConfigType.TROJAN -> isTlsLayer(profile.security)

            // Its own AEAD cipher counts, but only when one was actually chosen.
            EConfigType.VMESS -> isTlsLayer(profile.security) || isAeadCipher(profile.method)

            EConfigType.SHADOWSOCKS -> isAeadCipher(profile.method)

            // QUIC, so TLS 1.3 is not optional; the insecure flag above is the only way out.
            EConfigType.HYSTERIA2, EConfigType.HYSTERIA -> true

            // Noise: modern, mutually authenticated with static keys.
            EConfigType.WIREGUARD -> true

            // A type this build does not reason about is not one to assume safe.
            else -> false
        }
    }

    private fun isTlsLayer(security: String?): Boolean {
        val value = security?.trim()?.lowercase() ?: return false
        return value == "tls" || value == "reality" || value == "xtls"
    }

    /**
     * Ciphers that authenticate as well as encrypt. `auto` counts: the core resolves it to
     * an AEAD cipher, and it is what most VMess entries in the wild actually carry.
     */
    private fun isAeadCipher(method: String?): Boolean {
        val value = method?.trim()?.lowercase().orEmpty()
        if (value.isEmpty() || value == "none" || value == "plain" || value == "zero") {
            return false
        }
        if (value == "auto") {
            return true
        }
        return value.contains("gcm") || value.contains("poly1305")
    }

    // ---- the country filter -----------------------------------------------------

    /**
     * Separates a request for Iran out of an ordinary country filter.
     *
     * "IR" appears in the country picker because that is where somebody looks for it, but
     * it cannot mean there what every other code means. The ordinary filter prefers the
     * countries asked for and then fills the remaining slots with the fastest servers found
     * anywhere, which is the right trade when the country is a preference — and the exact
     * silent failure this mode exists to rule out when it is not. A user who picked Iran to
     * reach their bank and was handed a fast German server would be told the run succeeded
     * and would still be refused at the bank.
     *
     * So picking Iran turns the mode on rather than adding a filter value, and the two
     * controls are one setting seen from two places. The other countries are left in the
     * filter untouched: the mode overrides them while it is on, and they come back
     * unchanged when it is switched off.
     *
     * @return the codes that stay an ordinary filter, and whether Iran was among them.
     */
    fun splitFilter(countries: List<String>): Pair<List<String>, Boolean> {
        val rest = countries.filterNot { it.trim().uppercase() == COUNTRY }
        return rest to (rest.size != countries.size)
    }

    // ---- is this thing Iranian --------------------------------------------------

    /**
     * What the config claims, before anything has been measured — the label the ranker
     * spends its test budget on.
     *
     * A claim, and treated as one. It decides what is worth testing and never what is worth
     * keeping; [isIranianExit] is the other half of that rule.
     */
    fun looksIranian(profile: ProfileItem): Boolean =
        CountryHint.fromRemark(profile.remarks) == COUNTRY || isIranianHost(profile.server)

    /** True when the remark names a country and that country is not Iran. */
    fun labelledElsewhere(profile: ProfileItem): Boolean {
        if (isIranianHost(profile.server)) {
            return false
        }
        val labelled = CountryHint.fromRemark(profile.remarks) ?: return false
        return labelled != COUNTRY
    }

    /**
     * Whether a measured server may be kept.
     *
     * The measured exit country decides, and a server proven to come out somewhere else is
     * never kept however fast it was.
     *
     * The fallback applies only when there is no measurement at all — the lookup runs
     * *through* the tunnel and the services answering it are not reliably reachable from
     * inside Iran, so reading "the lookup timed out" as "not Iranian" would reject the very
     * servers this mode exists to find. But the fallback asks [isIranianAddress] and not
     * [looksIranian], and the difference is the whole point: a name is a claim and an
     * address block is not. Keeping a server on the strength of its name is how a German
     * machine ends up carrying a bank session, which is the one outcome this mode exists to
     * rule out, and it would happen silently because the run would report success.
     */
    fun isIranianExit(measurement: AutoModeMeasurement): Boolean {
        val measured = measurement.exitCountry
        if (measured != null) {
            return measured.uppercase() == COUNTRY
        }
        return isIranianAddress(measurement.profile.server)
    }

    /**
     * Whether a host is in Iran on the evidence of the address itself.
     *
     * Only an address in [IRAN_V4_BLOCKS] counts. Anything else — a domain of any kind, an
     * IPv6 literal — is unknown, and unknown is not a yes, because this answer is used
     * exactly where a measurement is missing and a wrong yes is kept rather than caught.
     *
     * A `.ir` name deliberately does not count, and that is worth writing down because the
     * opposite is the intuitive guess. Measured against the 2000 configs in this project's
     * own default bundle: 39 carried a `.ir` hostname, and of the 38 that resolved, **37
     * pointed outside Iran** — Cloudflare, OVH, Hetzner. Exactly one was in Iranian address
     * space. That is not noise around a good signal, it is the opposite of a signal. These
     * lists are full of circumvention configs, and an Iranian domain in one is nearly always
     * fronting for a machine hosted abroad, which is the whole technique. Trusting the
     * suffix would have kept a foreign server for 97 out of every 98 names it matched.
     */
    fun isIranianAddress(host: String?): Boolean {
        val value = host?.trim()?.lowercase() ?: return false
        if (value.isEmpty()) {
            return false
        }
        val packed = packIpv4(value) ?: return false
        return IRAN_V4_BLOCKS.any { (network, mask) -> (packed and mask) == network }
    }

    /**
     * The weaker question, for deciding what to test rather than what to keep.
     *
     * An Iranian address, or a `.ir` name. The name is nearly always wrong — see
     * [isIranianAddress] for the count — so it earns a slot in the test queue and nothing
     * more. A measurement settles it either way, and a wasted test costs one slot.
     */
    fun isIranianHost(host: String?): Boolean {
        val value = host?.trim()?.lowercase() ?: return false
        return value.endsWith(".ir") || isIranianAddress(value)
    }

    private fun packIpv4(value: String): Long? {
        val parts = value.split('.')
        if (parts.size != 4) {
            return null
        }
        var packed = 0L
        for (part in parts) {
            val octet = part.toIntOrNull() ?: return null
            if (octet !in 0..255) {
                return null
            }
            packed = (packed shl 8) or octet.toLong()
        }
        return packed
    }

    /**
     * Large Iranian IPv4 allocations, as (network, mask) pairs.
     *
     * A prior, not a register. These are the big consumer and hosting blocks — TCI, MCI,
     * Irancell, the main data centres — and the list is neither complete nor guaranteed
     * current, because allocations move. That is affordable given where it is used: a hit
     * means "test this one first", which costs one test slot when it is wrong, and it
     * stands in for a missing measurement rather than overruling one. Shipping the full
     * geoip table to make it exact would mean parsing a megabyte of protobuf to reorder a
     * test queue.
     */
    private val IRAN_V4_BLOCKS: List<Pair<Long, Long>> by lazy {
        listOf(
            "2.144.0.0/12", "5.22.192.0/19", "5.52.0.0/16", "5.112.0.0/12",
            "31.2.128.0/17", "31.24.200.0/21", "31.56.0.0/13",
            "37.32.0.0/19", "37.98.0.0/16", "37.129.0.0/16", "37.148.0.0/17",
            "37.156.0.0/14", "37.191.0.0/17", "37.254.0.0/15",
            "46.32.0.0/19", "46.38.128.0/18", "46.100.0.0/14", "46.143.0.0/17",
            "46.209.0.0/16", "46.224.0.0/15", "46.245.0.0/17",
            "62.60.128.0/18", "62.102.128.0/19", "62.193.0.0/19", "62.220.96.0/19",
            "77.36.128.0/17", "77.104.64.0/18",
            "78.38.0.0/15", "78.109.192.0/20", "78.157.32.0/19",
            "79.127.0.0/17", "79.132.192.0/18", "79.175.128.0/18",
            "80.75.0.0/19", "80.191.0.0/16", "80.210.0.0/16", "80.242.0.0/19",
            "80.249.112.0/20", "80.253.128.0/19",
            "81.12.0.0/17", "81.16.112.0/20", "81.28.32.0/19", "81.31.160.0/19",
            "81.90.144.0/20", "81.91.128.0/18", "82.99.192.0/18",
            "84.241.0.0/18", "85.9.64.0/19", "85.133.128.0/17", "85.185.0.0/16",
            "86.104.32.0/19", "87.107.0.0/16", "87.236.208.0/20", "87.247.160.0/19",
            "88.135.32.0/19", "89.165.0.0/16", "91.98.0.0/15",
            "92.42.48.0/21", "92.114.16.0/20", "92.242.192.0/19",
            "93.110.0.0/15", "93.117.0.0/17", "93.126.0.0/17",
            "94.74.128.0/17", "94.101.128.0/19", "94.182.0.0/15", "94.184.0.0/15",
            "95.38.0.0/16", "95.162.0.0/16",
            "109.162.128.0/17", "109.203.128.0/18",
            "151.232.0.0/13", "151.240.0.0/13",
            "178.22.120.0/21", "178.131.0.0/16",
            "185.51.200.0/22", "185.55.224.0/22",
            "188.121.96.0/19", "188.136.128.0/17", "188.158.0.0/15", "188.229.0.0/16",
            "212.33.192.0/19", "213.176.0.0/19", "213.217.32.0/19",
            "217.24.144.0/20", "217.218.0.0/15",
        ).mapNotNull(::parseCidr)
    }

    private fun parseCidr(cidr: String): Pair<Long, Long>? {
        val slash = cidr.indexOf('/')
        if (slash < 0) {
            return null
        }
        val network = packIpv4(cidr.substring(0, slash)) ?: return null
        val bits = cidr.substring(slash + 1).toIntOrNull() ?: return null
        if (bits !in 0..32) {
            return null
        }
        val mask = if (bits == 0) 0L else (0xFFFFFFFFL shl (32 - bits)) and 0xFFFFFFFFL
        return (network and mask) to mask
    }

    // ---- routing ----------------------------------------------------------------

    /**
     * Whether the server about to be started is one that Iranian traffic should be sent
     * *through* rather than around.
     *
     * Deliberately narrower than "the mode is on". Somebody can have Iran mode enabled and
     * still be connected to a foreign server — the reserve holds both, and a manual pick is
     * always allowed — and sending every Iranian site through Frankfurt in that state would
     * make ordinary browsing slower for no benefit at all. So it asks about this server:
     * the exit country a run measured for it, or failing that its address, which is the only
     * evidence available for a server the user added by hand.
     *
     * The whole thing is behind a catch because it runs in the core's process while a
     * connection is being built. A storage problem there must fall back to the routing the
     * user already had rather than take the connection down with it.
     */
    fun tunnelsIranianTraffic(guid: String): Boolean = try {
        val store = AutoModeSourceManager.getStore()
        when {
            !store.iranMode -> false
            store.countryByGuid[guid]?.uppercase() == COUNTRY -> true
            else -> MmkvManager.decodeServerConfig(guid)?.let { looksIranian(it) } == true
        }
    } catch (e: Exception) {
        LogUtil.e(AppConfig.TAG, "AutoMode: could not decide Iran routing for $guid", e)
        false
    }

    /**
     * Strips the entries that would send Iranian traffic around the tunnel.
     *
     * Applied to every direct rule while an Iranian server is carrying the connection, and
     * it edits the copy the core is being handed rather than the user's saved ruleset — so
     * switching the mode off restores the original behaviour with nothing to undo.
     *
     * @return the trimmed lists, or null when the rule has nothing left and should be
     *         dropped entirely.
     */
    fun trimIranBypass(domains: List<String>?, ips: List<String>?): Pair<List<String>?, List<String>?>? {
        val keptDomains = domains?.filterNot { isIranDomainRule(it) }
        val keptIps = ips?.filterNot { isIranIpRule(it) }

        val hadSomething = !domains.isNullOrEmpty() || !ips.isNullOrEmpty()
        val hasSomething = !keptDomains.isNullOrEmpty() || !keptIps.isNullOrEmpty()
        if (hadSomething && !hasSomething) {
            return null
        }

        return keptDomains?.takeIf { it.isNotEmpty() } to keptIps?.takeIf { it.isNotEmpty() }
    }

    private fun isIranDomainRule(entry: String): Boolean {
        val value = entry.trim().lowercase()
        return value == "domain:ir" || value == "ir" || value.startsWith("geosite:category-ir")
    }

    private fun isIranIpRule(entry: String): Boolean {
        val value = entry.trim().lowercase()
        return value == "geoip:ir" || (value.startsWith("ext:") && value.endsWith(":ir"))
    }
}
