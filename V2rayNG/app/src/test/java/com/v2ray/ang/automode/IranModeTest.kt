package com.v2ray.ang.automode

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules that decide what Iran mode is allowed to connect to.
 *
 * Two of them are safety rules rather than preferences, and both are tested from the
 * failing direction: a config that cannot protect the traffic must be refused, and a
 * server proven to come out somewhere other than Iran must never be kept.
 */
class IranModeTest {

    private fun profile(
        type: EConfigType = EConfigType.VLESS,
        security: String? = "reality",
        method: String? = null,
        insecure: Boolean? = null,
        remarks: String = "",
        server: String = "1.2.3.4",
    ) = ProfileItem(
        configType = type,
        remarks = remarks,
        server = server,
        serverPort = "443",
        security = security,
        method = method,
        insecure = insecure,
    )

    // ---- security ---------------------------------------------------------------

    @Test
    fun `a proxy that encrypts nothing is refused`() {
        assertFalse(IranMode.isSecure(profile(type = EConfigType.SOCKS, security = null)))
        assertFalse(IranMode.isSecure(profile(type = EConfigType.HTTP, security = null)))
    }

    /** VLESS carries no encryption of its own, so a `security=none` entry is plaintext. */
    @Test
    fun `vless without a tls layer is refused`() {
        assertFalse(IranMode.isSecure(profile(security = null)))
        assertFalse(IranMode.isSecure(profile(security = "none")))
        assertTrue(IranMode.isSecure(profile(security = "tls")))
        assertTrue(IranMode.isSecure(profile(security = "reality")))
    }

    @Test
    fun `tls that accepts any certificate is refused`() {
        assertFalse(IranMode.isSecure(profile(security = "tls", insecure = true)))
        assertFalse(IranMode.isSecure(profile(type = EConfigType.TROJAN, security = "tls", insecure = true)))
        assertFalse(IranMode.isSecure(profile(type = EConfigType.HYSTERIA2, security = "tls", insecure = true)))
    }

    /**
     * VMess and Shadowsocks authenticate and encrypt under a pre-shared key, so they pass
     * without TLS — but only when a real cipher was chosen. `none`, `plain` and `zero` are
     * the same clear channel the SOCKS case is refused for.
     */
    @Test
    fun `a named cipher is what makes vmess and shadowsocks acceptable`() {
        assertTrue(IranMode.isSecure(profile(type = EConfigType.VMESS, security = null, method = "auto")))
        assertTrue(IranMode.isSecure(profile(type = EConfigType.VMESS, security = null, method = "aes-128-gcm")))
        assertFalse(IranMode.isSecure(profile(type = EConfigType.VMESS, security = null, method = "none")))
        assertFalse(IranMode.isSecure(profile(type = EConfigType.VMESS, security = null, method = "zero")))

        assertTrue(
            IranMode.isSecure(
                profile(type = EConfigType.SHADOWSOCKS, security = null, method = "chacha20-ietf-poly1305")
            )
        )
        assertFalse(IranMode.isSecure(profile(type = EConfigType.SHADOWSOCKS, security = null, method = "plain")))
        assertFalse(IranMode.isSecure(profile(type = EConfigType.SHADOWSOCKS, security = null, method = null)))
    }

    // ---- where the server is ----------------------------------------------------

    @Test
    fun `an address inside an iranian block is read as iranian`() {
        assertTrue(IranMode.isIranianHost("2.144.0.1"))
        assertTrue(IranMode.isIranianHost("178.22.122.100"))
        assertTrue(IranMode.isIranianHost("217.218.155.155"))
        assertTrue(IranMode.isIranianHost("bank.example.ir"))
    }

    @Test
    fun `an address outside those blocks is not claimed as iranian`() {
        assertFalse(IranMode.isIranianHost("8.8.8.8"))
        assertFalse(IranMode.isIranianHost("1.1.1.1"))
        assertFalse(IranMode.isIranianHost("cdn.example.com"))
        assertFalse(IranMode.isIranianHost("2001:db8::1"))
        assertFalse(IranMode.isIranianHost(""))
        assertFalse(IranMode.isIranianHost(null))
    }

    /** A malformed address is unknown, and unknown must never read as a yes. */
    @Test
    fun `a malformed address is not iranian`() {
        assertFalse(IranMode.isIranianHost("2.144.0"))
        assertFalse(IranMode.isIranianHost("2.144.0.999"))
        assertFalse(IranMode.isIranianHost("2.144.0.x"))
    }

    @Test
    fun `the address counts as evidence alongside the remark`() {
        assertTrue(IranMode.looksIranian(profile(remarks = "🇮🇷 Tehran")))
        assertTrue(IranMode.looksIranian(profile(remarks = "node 7", server = "91.98.5.5")))
        assertFalse(IranMode.looksIranian(profile(remarks = "node 7", server = "8.8.8.8")))
    }

    /**
     * A remark naming another country is enough to skip a candidate, since a test slot
     * spent on it is one not spent on something that could work — but an Iranian address
     * overrules the remark, because the address is the better evidence of the two.
     */
    @Test
    fun `a server labelled elsewhere is dropped unless its address says otherwise`() {
        assertTrue(IranMode.labelledElsewhere(profile(remarks = "🇩🇪 Frankfurt", server = "8.8.8.8")))
        assertFalse(IranMode.labelledElsewhere(profile(remarks = "🇩🇪 Frankfurt", server = "91.98.5.5")))
        assertFalse(IranMode.labelledElsewhere(profile(remarks = "fast node 3", server = "8.8.8.8")))
    }

    // ---- what may be kept -------------------------------------------------------

    private fun measured(country: String?, remarks: String = "", server: String = "8.8.8.8") =
        AutoModeMeasurement(
            guid = "g",
            profile = profile(remarks = remarks, server = server),
            speedMbps = 1.0,
            delayMillis = 200,
            exitCountry = country,
        )

    @Test
    fun `a measured foreign exit is never kept, however it is labelled`() {
        assertFalse(IranMode.isIranianExit(measured("DE", remarks = "🇮🇷 Iran", server = "91.98.5.5")))
        assertTrue(IranMode.isIranianExit(measured("IR")))
        assertTrue(IranMode.isIranianExit(measured("ir")))
    }

    /**
     * The lookup runs through the tunnel and is not reliably answerable from inside Iran.
     * Reading a timeout as "not Iranian" would reject the servers this mode exists to find,
     * so the address answers instead when nothing was measured.
     */
    @Test
    fun `with no measurement the address decides`() {
        assertTrue(IranMode.isIranianExit(measured(null, server = "91.98.5.5")))
        assertTrue(IranMode.isIranianExit(measured(null, remarks = "🇮🇷 Tehran")))
        assertFalse(IranMode.isIranianExit(measured(null, server = "8.8.8.8")))
    }

    // ---- routing ----------------------------------------------------------------

    @Test
    fun `the iran bypass entries are stripped and the rest of the rule survives`() {
        val trimmed = IranMode.trimIranBypass(
            domains = listOf("domain:ir", "geosite:category-ir", "geosite:private"),
            ips = listOf("geoip:ir", "geoip:private"),
        )

        assertEquals(listOf("geosite:private"), trimmed?.first)
        assertEquals(listOf("geoip:private"), trimmed?.second)
    }

    /** A rule that was only ever about Iran has nothing left, so the caller drops it. */
    @Test
    fun `a rule that bypassed only iran is dropped whole`() {
        assertNull(IranMode.trimIranBypass(domains = listOf("domain:ir"), ips = null))
        assertNull(IranMode.trimIranBypass(domains = null, ips = listOf("geoip:ir")))
        assertNull(IranMode.trimIranBypass(domains = listOf("geosite:category-ir"), ips = listOf("geoip:ir")))
    }

    /** A rule about something else entirely is handed back untouched. */
    @Test
    fun `unrelated direct rules are left alone`() {
        val trimmed = IranMode.trimIranBypass(domains = listOf("geosite:private"), ips = listOf("geoip:private"))
        assertEquals(listOf("geosite:private"), trimmed?.first)
        assertEquals(listOf("geoip:private"), trimmed?.second)
    }
}
