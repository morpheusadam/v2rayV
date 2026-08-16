package com.v2ray.ang.automode

import com.v2ray.ang.AppConfig
import com.v2ray.ang.util.LogUtil
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * A very small HTTP/1.1 GET that can be carried over an HTTP CONNECT, SOCKS4a or SOCKS5
 * proxy — the three things a scraped `ip:port` list turns out to contain.
 *
 * Why not OkHttp with a [java.net.Proxy]:
 *
 *  - The JVM's SOCKS client is the only way OkHttp can speak SOCKS, and its SOCKS4 path is
 *    broken: it opens with a SOCKS5 greeting, reads two bytes of the eight-byte SOCKS4
 *    reply, and then writes a SOCKS4 request onto a socket that is already out of sync
 *    (square/okhttp#1359). A quarter of the observed candidate list listens on 4145, the
 *    conventional SOCKS4 port, so that path cannot simply be skipped.
 *  - More importantly, every client that takes a [java.net.Proxy] resolves the destination
 *    locally and hands the proxy an IP. On a network that answers DNS with a lie — which is
 *    the network this whole feature exists for — that defeats the point of using the proxy
 *    at all.
 *
 * So the destination is always addressed **by name** and resolved at the far end: SOCKS5's
 * domain address type, SOCKS4a's hostname extension, and CONNECT's authority form all
 * support it. Nothing here ever calls [java.net.InetAddress.getByName] on the target.
 *
 * The response side is deliberately minimal — status line, headers, Content-Length or
 * chunked body. It reads subscription lists, not arbitrary web pages.
 */
object ProxiedFetch {

    private const val CONNECT_TIMEOUT_MILLIS = 6_000
    private const val HANDSHAKE_TIMEOUT_MILLIS = 6_000
    private const val READ_TIMEOUT_MILLIS = 20_000

    /** Ceiling on a fetched body, so a mis-pointed URL cannot exhaust the heap. */
    private const val MAX_BODY_BYTES = 24 * 1024 * 1024

    private const val MAX_REDIRECTS = 3

    private const val USER_AGENT = "Mozilla/5.0 (Android) v2rayV"

    /** SOCKS4a signals "resolve this name yourself" with an otherwise invalid 0.0.0.x. */
    private val SOCKS4A_SENTINEL_IP = byteArrayOf(0, 0, 0, 1)

    data class Response(val code: Int, val body: ByteArray, val headers: Map<String, String>) {
        val isSuccess: Boolean get() = code in 200..299
        fun text(): String = String(body, Charsets.UTF_8)

        // Present only because the class has an array property; nothing compares responses.
        override fun equals(other: Any?): Boolean = this === other
        override fun hashCode(): Int = System.identityHashCode(this)
    }

    /**
     * GET [url], optionally through [proxy] and optionally asking for a byte range.
     *
     * @param proxy null for a direct connection.
     * @param range inclusive `first to last` byte offsets, or null for the whole body.
     */
    fun get(
        url: String,
        proxy: AutoModeProxy? = null,
        range: Pair<Long, Long>? = null,
        readTimeoutMillis: Int = READ_TIMEOUT_MILLIS,
    ): Response? {
        var current = url
        repeat(MAX_REDIRECTS) {
            val response = getOnce(current, proxy, range, readTimeoutMillis) ?: return null
            if (response.code !in 300..399) {
                return response
            }
            val location = response.headers["location"] ?: return response
            current = try {
                URI(current).resolve(location).toString()
            } catch (_: Exception) {
                return response
            }
        }
        return null
    }

    private fun getOnce(
        url: String,
        proxy: AutoModeProxy?,
        range: Pair<Long, Long>?,
        readTimeoutMillis: Int,
    ): Response? {
        val uri = try {
            URI(url)
        } catch (_: Exception) {
            return null
        }

        val https = uri.scheme.equals("https", ignoreCase = true)
        val host = uri.host ?: return null
        val port = if (uri.port > 0) uri.port else if (https) 443 else 80
        val path = buildString {
            append(uri.rawPath?.takeIf { it.isNotEmpty() } ?: "/")
            uri.rawQuery?.let { append('?').append(it) }
        }

        var socket: Socket? = null
        try {
            socket = openTunnel(host, port, proxy) ?: return null
            socket.soTimeout = readTimeoutMillis

            // TLS is negotiated with the real hostname regardless of how the bytes got
            // here, so SNI and certificate validation are unaffected by the detour.
            val stream: Socket = if (https) {
                (SSLSocketFactory.getDefault() as SSLSocketFactory)
                    .createSocket(socket, host, port, true).also { tls ->
                        (tls as SSLSocket).startHandshake()
                    }
            } else {
                socket
            }

            val request = buildString {
                append("GET ").append(path).append(" HTTP/1.1\r\n")
                append("Host: ").append(host).append("\r\n")
                append("User-Agent: ").append(USER_AGENT).append("\r\n")
                append("Accept: */*\r\n")
                // The lists on the other end are regenerated hourly. Every hop in between
                // — the CDN mirrors especially, which cache a branch reference for hours —
                // would otherwise be free to answer with yesterday's servers, and a run
                // that measures stale entries wastes its whole budget proving they died.
                append("Cache-Control: no-cache, max-age=0\r\n")
                append("Pragma: no-cache\r\n")
                if (range != null) {
                    append("Range: bytes=").append(range.first).append('-').append(range.second).append("\r\n")
                }
                append("Connection: close\r\n\r\n")
            }

            val out = stream.getOutputStream()
            out.write(request.toByteArray(Charsets.US_ASCII))
            out.flush()

            return readResponse(stream.getInputStream())
        } catch (e: Exception) {
            LogUtil.d(AppConfig.TAG, "AutoMode: fetch failed via ${proxy?.display ?: "direct"}: ${e.message}")
            return null
        } finally {
            try {
                socket?.close()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * A TCP socket whose far end is [host]:[port], reached either directly or by talking
     * whichever proxy protocol [proxy] declares.
     */
    private fun openTunnel(host: String, port: Int, proxy: AutoModeProxy?): Socket? {
        if (proxy == null) {
            return Socket().apply {
                soTimeout = HANDSHAKE_TIMEOUT_MILLIS
                connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MILLIS)
            }
        }

        val socket = Socket()
        try {
            socket.tcpNoDelay = true
            socket.soTimeout = HANDSHAKE_TIMEOUT_MILLIS
            // Only the proxy's own address is resolved locally; it is an IP already in
            // every list seen so far, so this does not touch DNS either.
            socket.connect(InetSocketAddress(proxy.host, proxy.port), CONNECT_TIMEOUT_MILLIS)

            val ok = when (proxy.protocol) {
                ProxyProtocol.HTTP -> httpConnect(socket, host, port, proxy)
                ProxyProtocol.SOCKS5 -> socks5Connect(socket, host, port, proxy)
                ProxyProtocol.SOCKS4 -> socks4aConnect(socket, host, port, proxy)
                ProxyProtocol.UNKNOWN -> false
            }
            if (!ok) {
                socket.close()
                return null
            }
            return socket
        } catch (e: Exception) {
            try {
                socket.close()
            } catch (_: Exception) {
            }
            return null
        }
    }

    //region handshakes

    /**
     * RFC 7231 tunnelling. The authority form carries the hostname, so the proxy resolves
     * it. Many public HTTP proxies allow CONNECT only to 443, which is all that is needed.
     */
    private fun httpConnect(socket: Socket, host: String, port: Int, proxy: AutoModeProxy): Boolean {
        val request = buildString {
            append("CONNECT ").append(host).append(':').append(port).append(" HTTP/1.1\r\n")
            append("Host: ").append(host).append(':').append(port).append("\r\n")
            append("User-Agent: ").append(USER_AGENT).append("\r\n")
            append("Proxy-Connection: keep-alive\r\n")
            proxy.basicAuthHeaderValue()?.let { append("Proxy-Authorization: ").append(it).append("\r\n") }
            append("\r\n")
        }
        socket.getOutputStream().write(request.toByteArray(Charsets.US_ASCII))
        socket.getOutputStream().flush()

        val input = socket.getInputStream()
        val statusLine = readLine(input) ?: return false
        // "HTTP/1.1 200 Connection established"
        val code = statusLine.split(' ').getOrNull(1)?.toIntOrNull() ?: return false
        if (code != 200) {
            return false
        }
        // Drain the tunnel response's headers; the body that follows is the tunnel itself.
        while (true) {
            val line = readLine(input) ?: return false
            if (line.isEmpty()) {
                return true
            }
        }
    }

    /**
     * RFC 1928. Greeting, then a CONNECT request using address type 0x03 (domain name) so
     * the proxy performs the lookup.
     */
    private fun socks5Connect(socket: Socket, host: String, port: Int, proxy: AutoModeProxy): Boolean {
        val out = socket.getOutputStream()
        val input = socket.getInputStream()

        val credentials = proxy.username != null && proxy.password != null
        // Offer username/password only when there is one, so servers that reject unknown
        // methods outright are not given the chance.
        if (credentials) {
            out.write(byteArrayOf(0x05, 0x02, 0x00, 0x02))
        } else {
            out.write(byteArrayOf(0x05, 0x01, 0x00))
        }
        out.flush()

        val greeting = readExactly(input, 2) ?: return false
        if (greeting[0] != 0x05.toByte()) {
            return false
        }
        when (greeting[1]) {
            0x00.toByte() -> Unit
            0x02.toByte() -> if (!socks5Authenticate(out, input, proxy)) return false
            else -> return false
        }

        val hostBytes = host.toByteArray(Charsets.US_ASCII)
        if (hostBytes.size > 255) {
            return false
        }
        val request = ByteArrayOutputStream().apply {
            write(0x05)          // version
            write(0x01)          // CONNECT
            write(0x00)          // reserved
            write(0x03)          // address type: domain name
            write(hostBytes.size)
            write(hostBytes)
            write((port shr 8) and 0xFF)
            write(port and 0xFF)
        }.toByteArray()
        out.write(request)
        out.flush()

        val reply = readExactly(input, 4) ?: return false
        if (reply[0] != 0x05.toByte() || reply[1] != 0x00.toByte()) {
            return false
        }
        // The bound address has to be consumed or it would be read as body bytes.
        val skip = when (reply[3]) {
            0x01.toByte() -> 4 + 2
            0x03.toByte() -> (readExactly(input, 1) ?: return false)[0].toInt().and(0xFF) + 2
            0x04.toByte() -> 16 + 2
            else -> return false
        }
        return readExactly(input, skip) != null
    }

    /** RFC 1929 username/password sub-negotiation. */
    private fun socks5Authenticate(out: OutputStream, input: InputStream, proxy: AutoModeProxy): Boolean {
        val user = proxy.username?.toByteArray(Charsets.US_ASCII) ?: return false
        val pass = proxy.password?.toByteArray(Charsets.US_ASCII) ?: return false
        if (user.size > 255 || pass.size > 255) {
            return false
        }
        val message = ByteArrayOutputStream().apply {
            write(0x01)
            write(user.size)
            write(user)
            write(pass.size)
            write(pass)
        }.toByteArray()
        out.write(message)
        out.flush()

        val reply = readExactly(input, 2) ?: return false
        return reply[1] == 0x00.toByte()
    }

    /**
     * SOCKS4a. The destination IP is set to the invalid 0.0.0.1, which is how a client
     * tells a SOCKS4a server that a hostname follows the user id instead.
     *
     * Plain SOCKS4 servers reject this rather than mis-parsing it, and the candidate is
     * simply dropped — there are thousands of others, and resolving locally to satisfy
     * them would reintroduce exactly the DNS dependency this avoids.
     */
    private fun socks4aConnect(socket: Socket, host: String, port: Int, proxy: AutoModeProxy): Boolean {
        val out = socket.getOutputStream()
        val input = socket.getInputStream()

        val request = ByteArrayOutputStream().apply {
            write(0x04)          // version
            write(0x01)          // CONNECT
            write((port shr 8) and 0xFF)
            write(port and 0xFF)
            write(SOCKS4A_SENTINEL_IP)
            proxy.username?.let { write(it.toByteArray(Charsets.US_ASCII)) }
            write(0x00)          // user id terminator
            write(host.toByteArray(Charsets.US_ASCII))
            write(0x00)          // hostname terminator
        }.toByteArray()
        out.write(request)
        out.flush()

        val reply = readExactly(input, 8) ?: return false
        // 0x00 null byte, then 0x5A "request granted".
        return reply[0] == 0x00.toByte() && reply[1] == 0x5A.toByte()
    }

    //endregion handshakes

    //region response parsing

    private fun readResponse(input: InputStream): Response? {
        val statusLine = readLine(input) ?: return null
        val code = statusLine.split(' ').getOrNull(1)?.toIntOrNull() ?: return null

        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = readLine(input) ?: return null
            if (line.isEmpty()) {
                break
            }
            val colon = line.indexOf(':')
            if (colon > 0) {
                headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
            }
        }

        val body = when {
            headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true ->
                readChunked(input)

            headers["content-length"]?.toLongOrNull() != null ->
                readFixed(input, headers["content-length"]!!.toLong())

            // No length and no chunking: the body runs until the server closes, which is
            // what "Connection: close" asked for.
            else -> readUntilClose(input)
        } ?: return null

        // Every response passes through here, so this is the one place the Date header can be
        // read without adding a request anywhere. See [ClockSkew] for why it is worth reading.
        ClockSkew.observe(headers)

        return Response(code, body, headers)
    }

    private fun readFixed(input: InputStream, length: Long): ByteArray? {
        if (length > MAX_BODY_BYTES) {
            return null
        }
        return readExactly(input, length.toInt())
    }

    private fun readChunked(input: InputStream): ByteArray? {
        val out = ByteArrayOutputStream()
        while (true) {
            val sizeLine = readLine(input) ?: return null
            val size = sizeLine.substringBefore(';').trim().toIntOrNull(16) ?: return null
            if (size == 0) {
                return out.toByteArray()
            }
            if (out.size() + size > MAX_BODY_BYTES) {
                return null
            }
            out.write(readExactly(input, size) ?: return null)
            readLine(input) // trailing CRLF after each chunk
        }
    }

    private fun readUntilClose(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        try {
            while (out.size() < MAX_BODY_BYTES) {
                val read = input.read(buffer)
                if (read < 0) {
                    break
                }
                out.write(buffer, 0, read)
            }
        } catch (_: Exception) {
            // A truncated body is still worth what arrived; the caller validates content.
        }
        return out.toByteArray()
    }

    /** Reads a CRLF- or LF-terminated line, without the terminator. Null at end of stream. */
    private fun readLine(input: InputStream): String? {
        val out = ByteArrayOutputStream(128)
        while (true) {
            val b = input.read()
            if (b < 0) {
                return if (out.size() == 0) null else out.toString("US-ASCII")
            }
            if (b == '\n'.code) {
                val text = out.toString("US-ASCII")
                return text.removeSuffix("\r")
            }
            out.write(b)
            if (out.size() > 8192) {
                return null
            }
        }
    }

    private fun readExactly(input: InputStream, count: Int): ByteArray? {
        if (count == 0) {
            return ByteArray(0)
        }
        val buffer = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val read = try {
                input.read(buffer, offset, count - offset)
            } catch (_: Exception) {
                return null
            }
            if (read < 0) {
                return null
            }
            offset += read
        }
        return buffer
    }

    //endregion response parsing
}
