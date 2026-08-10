package com.v2ray.ang.automode

import android.content.Context
import com.v2ray.ang.AppConfig
import com.v2ray.ang.util.LogUtil
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * Getting bytes out of GitHub from a network that would rather you did not.
 *
 * Three obstacles, in the order they are hit:
 *
 *  1. **The host is blocked.** `raw.githubusercontent.com` is unreachable from some
 *     networks, so every fetch is tried over a ladder of routes — the host itself, then
 *     CDN mirrors that serve the same repository from different hostnames, then a public
 *     proxy. Whichever rung works is remembered, so later fetches start there.
 *  2. **The bootstrap is circular.** The proxy list lives on the host that is blocked. A
 *     snapshot therefore ships inside the APK, which is enough to find a live proxy and
 *     pull a fresh list through it.
 *  3. **The file may be enormous.** A subscription list of tens of millions of entries
 *     cannot be downloaded to sample it. GitHub's raw host answers `Accept-Ranges: bytes`,
 *     so a large file is read as a handful of random windows instead — constant cost
 *     whatever the size, and a uniform sample rather than the first N lines, which on a
 *     sorted list would be the same servers every run.
 */
object AutoModeNetwork {

    /** The subscription list every run imports from, whatever else the user has added. */
    const val DEFAULT_SUBS_URL =
        "https://raw.githubusercontent.com/morpheusadam/v2ray-config/main/subs/all.txt"

    /** The proxy list used to get out when the subscription host is blocked. */
    const val DEFAULT_PROXIES_URL =
        "https://raw.githubusercontent.com/morpheusadam/v2ray-config/main/proxies/all.txt"

    private const val ASSET_PROXIES = "automode_proxies.txt"
    private const val ASSET_SUBS = "automode_subs.txt"

    /** Below this a file is simply downloaded; above it, sampled by byte range. */
    private const val FULL_DOWNLOAD_LIMIT = 3L * 1024 * 1024

    /** Random windows taken from a large file, and how much each one reads. */
    private const val SAMPLE_WINDOWS = 6
    private const val SAMPLE_WINDOW_BYTES = 256 * 1024

    private val rawUrlRegex =
        Regex("^https://raw\\.githubusercontent\\.com/([^/]+)/([^/]+)/(?:refs/heads/)?([^/]+)/(.+)$")

    /**
     * Alternative hostnames serving the same repository file.
     *
     * These are ordinary CDNs rather than circumvention tools; they are useful here only
     * because a block list that names `raw.githubusercontent.com` usually does not name
     * them too. A route that stops working simply falls through to the next.
     */
    fun mirrorsFor(url: String): List<String> {
        val m = rawUrlRegex.find(url) ?: return emptyList()
        val (user, repo, ref, path) = m.destructured
        return listOf(
            "https://cdn.jsdelivr.net/gh/$user/$repo@$ref/$path",
            "https://raw.githack.com/$user/$repo/$ref/$path",
            "https://gitcdn.link/cdn/$user/$repo/$ref/$path",
        )
    }

    /** Every way to ask for [url], best first. */
    private fun routes(url: String): List<String> = listOf(url) + mirrorsFor(url)

    /**
     * Fetches [url] whole, trying each route directly and then, if [proxy] is given,
     * each route again through it.
     */
    fun fetchText(url: String, proxy: AutoModeProxy? = null): String? {
        for (route in routes(url)) {
            ProxiedFetch.get(route)?.takeIf { it.isSuccess }?.let {
                LogUtil.i(AppConfig.TAG, "AutoMode: fetched $route directly")
                return it.text()
            }
        }
        if (proxy != null) {
            for (route in routes(url)) {
                ProxiedFetch.get(route, proxy)?.takeIf { it.isSuccess }?.let {
                    LogUtil.i(AppConfig.TAG, "AutoMode: fetched $route via ${proxy.display}")
                    return it.text()
                }
            }
        }
        return null
    }

    /**
     * The candidate proxy list: fresh from the network when that is possible, and the
     * snapshot bundled in the APK when it is not.
     *
     * The bundled copy is the only rung that cannot fail, which is what makes the first
     * run on a blocked network possible at all. It goes stale between releases, so it is
     * merged behind whatever the network produced rather than replacing it.
     */
    fun fetchProxyList(context: Context, onProgress: (String) -> Unit = {}): List<AutoModeProxy> {
        val url = AutoModeSourceManager.getStore().proxiesUrl.ifBlank { DEFAULT_PROXIES_URL }

        onProgress("Fetching the proxy list…")
        val fetched = fetchText(url)
        if (fetched != null) {
            val parsed = AutoModeProxy.parseList(fetched)
            if (parsed.isNotEmpty()) {
                onProgress("${parsed.size} proxies to try.")
                return parsed
            }
        }

        onProgress("Proxy list unreachable — using the copy shipped with the app.")
        val bundled = readAsset(context, ASSET_PROXIES)
        return AutoModeProxy.parseList(bundled)
    }

    /** The bundled snapshot of the subscription list, for when no route to it works. */
    fun bundledSubs(context: Context): String? = readAsset(context, ASSET_SUBS)

    /**
     * Whether [url] can be fetched without help. A one-byte range request rather than a
     * HEAD, because some CDNs answer HEAD from a cache that does not prove the body is
     * reachable — and because the answer doubles as the size probe.
     */
    fun reachableDirectly(url: String): Boolean =
        routes(url).any { ProxiedFetch.get(it, null, 0L to 0L)?.isSuccess == true }

    private fun readAsset(context: Context, name: String): String? = try {
        context.assets.open(name).bufferedReader().use { it.readText() }
    } catch (e: Exception) {
        LogUtil.e(AppConfig.TAG, "AutoMode: bundled asset $name unreadable", e)
        null
    }

    /**
     * Reads [url] in full when it is small, and as random byte windows when it is not.
     *
     * @param proxy route to use once the direct one has been shown not to work.
     * @return the text, or null when no route produced anything.
     */
    fun fetchSampled(url: String, proxy: AutoModeProxy? = null): String? {
        val route = workingRoute(url, proxy) ?: return null
        val size = probeSize(route.first, route.second)

        if (size == null || size <= FULL_DOWNLOAD_LIMIT) {
            return ProxiedFetch.get(route.first, route.second)?.takeIf { it.isSuccess }?.text()
        }

        LogUtil.i(AppConfig.TAG, "AutoMode: $url is $size bytes, sampling $SAMPLE_WINDOWS windows")
        return sampleWindows(route.first, route.second, size)
    }

    /** The first route that answers, paired with the proxy it needed (possibly none). */
    private fun workingRoute(url: String, proxy: AutoModeProxy?): Pair<String, AutoModeProxy?>? {
        for (candidate in routes(url)) {
            if (ProxiedFetch.get(candidate, null, 0L to 0L)?.isSuccess == true) {
                return candidate to null
            }
        }
        if (proxy != null) {
            for (candidate in routes(url)) {
                if (ProxiedFetch.get(candidate, proxy, 0L to 0L)?.isSuccess == true) {
                    return candidate to proxy
                }
            }
        }
        return null
    }

    /**
     * Total size from a one-byte range request, read out of `Content-Range: bytes 0-0/N`.
     *
     * Null when the server ignored the range, which is also the signal that windowed
     * sampling is not available and the file has to be taken whole.
     */
    fun probeSize(url: String, proxy: AutoModeProxy?): Long? {
        val response = ProxiedFetch.get(url, proxy, 0L to 0L) ?: return null
        if (response.code != 206) {
            return null
        }
        return parseContentRangeTotal(response.headers["content-range"])
    }

    /** `bytes 0-0/123456` → 123456. */
    fun parseContentRangeTotal(header: String?): Long? {
        val total = header?.substringAfter('/', "")?.trim() ?: return null
        return total.toLongOrNull()
    }

    /**
     * Reads [count][SAMPLE_WINDOWS] windows from random offsets and stitches them together.
     *
     * The first and last lines of each window are dropped: a window starts and ends
     * mid-line, and half a `vless://` URI parses as nothing at best and as a corrupted
     * server at worst.
     */
    private fun sampleWindows(url: String, proxy: AutoModeProxy?, size: Long): String? {
        val builder = StringBuilder()
        val maxStart = max(0L, size - SAMPLE_WINDOW_BYTES)

        for (i in 0 until SAMPLE_WINDOWS) {
            val start = if (maxStart <= 0) 0L else Random.nextLong(maxStart)
            val end = min(size - 1, start + SAMPLE_WINDOW_BYTES - 1)

            val response = ProxiedFetch.get(url, proxy, start to end) ?: continue
            if (!response.isSuccess) {
                continue
            }

            val text = response.text()
            val lines = text.lineSequence().toList()
            if (lines.size <= 2) {
                continue
            }
            // Keep the interior only — unless this window happens to start at byte zero,
            // where the first line really is a whole line.
            val from = if (start == 0L) 0 else 1
            lines.subList(from, lines.size - 1).forEach { builder.append(it).append('\n') }
        }

        return builder.toString().takeIf { it.isNotBlank() }
    }
}
