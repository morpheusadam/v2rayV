package com.v2ray.ang.automode

import android.util.Base64

/** What a candidate proxy speaks. [UNKNOWN] means the list did not say and it must be probed. */
enum class ProxyProtocol {
    HTTP,
    SOCKS4,
    SOCKS5,
    UNKNOWN,
}

/**
 * One entry from a scraped proxy list.
 *
 * These lists are not a format so much as a habit. The same file mixes `socks5://1.2.3.4:1080`
 * with a bare `1.2.3.4:4145`, and the bare half is the majority of some sources — so the
 * protocol is a guess until a handshake proves it, and [probeOrder] decides which guess to
 * spend the first connection on.
 */
data class AutoModeProxy(
    val host: String,
    val port: Int,
    val protocol: ProxyProtocol = ProxyProtocol.UNKNOWN,
    val username: String? = null,
    val password: String? = null,
) {
    val display: String
        get() = "${protocol.name.lowercase()}://$host:$port"

    /** The `Proxy-Authorization` value, or null when the entry carries no credentials. */
    fun basicAuthHeaderValue(): String? {
        if (username == null || password == null) {
            return null
        }
        val encoded = Base64.encodeToString("$username:$password".toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        return "Basic $encoded"
    }

    /**
     * The protocols to try, best guess first.
     *
     * When the list said which protocol it is, that is the only one worth attempting — a
     * mislabelled entry is rarer than a server that hangs for six seconds on a handshake it
     * does not understand, and every wasted attempt is one fewer candidate reached.
     *
     * When it did not, the port is the evidence. It is only a convention, but a strong one:
     * in the observed list every port 4145 entry is SOCKS4 and every 1080 is SOCKS5, and
     * getting the order right turns three handshake attempts into one.
     */
    fun probeOrder(): List<ProxyProtocol> {
        if (protocol != ProxyProtocol.UNKNOWN) {
            return listOf(protocol)
        }
        return when (port) {
            in socks5Ports -> listOf(ProxyProtocol.SOCKS5, ProxyProtocol.HTTP, ProxyProtocol.SOCKS4)
            in socks4Ports -> listOf(ProxyProtocol.SOCKS4, ProxyProtocol.SOCKS5, ProxyProtocol.HTTP)
            in httpPorts -> listOf(ProxyProtocol.HTTP, ProxyProtocol.SOCKS5, ProxyProtocol.SOCKS4)
            else -> listOf(ProxyProtocol.SOCKS5, ProxyProtocol.HTTP, ProxyProtocol.SOCKS4)
        }
    }

    companion object {
        /** Tor's 9050/9150 included: they are SOCKS5 and turn up in scraped lists. */
        private val socks5Ports = setOf(1080, 1081, 1085, 1088, 1090, 10808, 10809, 9050, 9150, 7890, 7891)

        /** 4145 is the conventional SOCKS4 port and dominates the bare half of these lists. */
        private val socks4Ports = setOf(4145, 5678, 9091)

        private val httpPorts = setOf(
            80, 81, 800, 801, 808, 999, 3128, 3129, 8000, 8008, 8080, 8081, 8085,
            8086, 8090, 8118, 8123, 8888, 8889, 9000, 9080,
        )

        private val schemes = mapOf(
            "http" to ProxyProtocol.HTTP,
            "https" to ProxyProtocol.HTTP,
            "socks4" to ProxyProtocol.SOCKS4,
            "socks4a" to ProxyProtocol.SOCKS4,
            "socks5" to ProxyProtocol.SOCKS5,
            "socks5h" to ProxyProtocol.SOCKS5,
            "socks" to ProxyProtocol.SOCKS5,
        )

        /**
         * Parses one line of a proxy list. Accepts, in the shapes actually observed:
         *
         *     socks5://1.2.3.4:1080
         *     http://user:pass@1.2.3.4:8080
         *     1.2.3.4:4145
         *     1.2.3.4:8080:user:pass
         *
         * Anything else — comments, blank lines, host names with no port, junk — is null
         * rather than a guess, because a malformed entry costs a six-second timeout.
         */
        fun parse(rawLine: String?): AutoModeProxy? {
            var line = rawLine?.trim() ?: return null
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) {
                return null
            }

            var protocol = ProxyProtocol.UNKNOWN
            val schemeSplit = line.indexOf("://")
            if (schemeSplit > 0) {
                protocol = schemes[line.substring(0, schemeSplit).lowercase()] ?: return null
                line = line.substring(schemeSplit + 3)
            }

            // Trim anything after the authority — some lists append "|US|elite" or a path.
            line = line.substringBefore('/').substringBefore('|').substringBefore(' ').trim()
            if (line.isEmpty()) {
                return null
            }

            var username: String? = null
            var password: String? = null

            val at = line.lastIndexOf('@')
            if (at > 0) {
                val credentials = line.substring(0, at).split(':', limit = 2)
                if (credentials.size == 2) {
                    username = credentials[0].takeIf { it.isNotEmpty() }
                    password = credentials[1].takeIf { it.isNotEmpty() }
                }
                line = line.substring(at + 1)
            }

            val parts = line.split(':')
            if (parts.size < 2) {
                return null
            }

            // The colon-delimited "host:port:user:pass" form used by several list vendors.
            if (parts.size >= 4 && username == null) {
                username = parts[2].takeIf { it.isNotEmpty() }
                password = parts[3].takeIf { it.isNotEmpty() }
            }

            val host = parts[0].trim()
            val port = parts[1].trim().toIntOrNull() ?: return null
            if (host.isEmpty() || port !in 1..65535) {
                return null
            }
            // A host has to look like one. This rejects the stray IPv6 fragments and the
            // "1.2.3.4:80:80:80" nonsense that scraped lists carry.
            if (!host.all { it.isLetterOrDigit() || it == '.' || it == '-' }) {
                return null
            }

            return AutoModeProxy(host, port, protocol, username, password)
        }

        /** Parses a whole list, dropping duplicates by endpoint. */
        fun parseList(body: String?): List<AutoModeProxy> {
            if (body.isNullOrBlank()) {
                return emptyList()
            }
            val seen = mutableSetOf<String>()
            val result = mutableListOf<AutoModeProxy>()
            for (line in body.lineSequence()) {
                val proxy = parse(line) ?: continue
                if (seen.add("${proxy.host}:${proxy.port}")) {
                    result.add(proxy)
                }
            }
            return result
        }
    }
}
