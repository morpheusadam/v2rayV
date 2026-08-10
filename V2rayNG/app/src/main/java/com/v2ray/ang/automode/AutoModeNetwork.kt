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

    /**
     * The one source every run imports from, whatever else the user has added.
     *
     * Deliberately a *bundle* rather than the catalog. The catalog is 1,500 links to other
     * people's lists; sampling eight of them costs eight fetches to eight strangers, any of
     * which may be down, and the phone then has to work out which of them were worth
     * anything. This file is the answer to that question, computed daily against far more
     * evidence than a phone will ever have: only configs from sources that scored 85 or
     * better, deduplicated across all of them, best first.
     *
     * One fetch, to a host the app already has a mirror ladder for.
     */
    const val DEFAULT_SOURCE_URL =
        "https://raw.githubusercontent.com/morpheusadam/v2ray-config/main/subs/bundles/best.txt"

    /**
     * The catalog of other people's subscription links, kept as a second source of variety.
     *
     * It is where the bundle above ultimately comes from, and a run that samples it can
     * still turn up something the daily job has not seen yet.
     */
    const val DEFAULT_SUBS_URL =
        "https://raw.githubusercontent.com/morpheusadam/v2ray-config/main/subs/all.txt"

    /** The proxy list used to get out when the subscription host is blocked. */
    const val DEFAULT_PROXIES_URL =
        "https://raw.githubusercontent.com/morpheusadam/v2ray-config/main/proxies/all.txt"

    /**
     * The one channel for telling an installed app something — an update, a notice, an
     * announcement. Absent or empty means the dashboard shows nothing, which is the
     * normal state. Read by [com.v2ray.ang.notice.NoticeManager].
     */
    const val DEFAULT_NOTICE_URL =
        "https://raw.githubusercontent.com/morpheusadam/v2ray-config/main/app/notice.json"

    private const val ASSET_PROXIES = "automode_proxies.txt"
    private const val ASSET_SUBS = "automode_subs.txt"

    /** Below this a file is simply downloaded; above it, sampled by byte range. */
    private const val FULL_DOWNLOAD_LIMIT = 3L * 1024 * 1024

    /** How long a remembered rung is believed before the ladder is walked from the top again. */
    private const val ROUTE_MEMO_TTL_MILLIS = 6L * 60 * 60 * 1000

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

    /**
     * Every way to ask for [url], best first, with the rung that worked last time moved to
     * the front.
     *
     * @param preferred index of the rung to try first, as remembered in
     *        [AutoModeStore.lastRouteIndex]. Out-of-range values are ignored rather than
     *        rejected, so a stored index that no longer names anything is harmless.
     */
    fun routes(url: String, preferred: Int = 0): List<String> {
        val all = listOf(url) + mirrorsFor(url)
        if (preferred <= 0 || preferred >= all.size) {
            return all
        }
        // Moved to the front rather than sorted: the rest keep their order, which is the
        // order they are worth trying in when the remembered one has since been blocked too.
        return listOf(all[preferred]) + all.filterIndexed { index, _ -> index != preferred }
    }

    /** The rung to start from, honoured only while it is still recent enough to believe. */
    private fun preferredRoute(): Int {
        val store = AutoModeSourceManager.getStore()
        if (System.currentTimeMillis() - store.lastRouteMillis > ROUTE_MEMO_TTL_MILLIS) {
            return 0
        }
        return store.lastRouteIndex
    }

    /**
     * Records which rung answered, so later fetches — and later runs — start there.
     *
     * Only worth storing when it is not the obvious one: an open network wins on rung zero
     * every time and would otherwise write to the store on every fetch.
     */
    private fun rememberRoute(url: String, winner: String) {
        val index = (listOf(url) + mirrorsFor(url)).indexOf(winner)
        if (index < 0) {
            return
        }
        val store = AutoModeSourceManager.getStore()
        if (store.lastRouteIndex == index && System.currentTimeMillis() - store.lastRouteMillis < ROUTE_MEMO_TTL_MILLIS) {
            return
        }
        store.lastRouteIndex = index
        store.lastRouteMillis = System.currentTimeMillis()
        AutoModeSourceManager.save()
    }

    /**
     * Fetches [url] whole, racing the routes directly and then, if [proxy] is given,
     * racing them again through it.
     *
     * Direct and proxied are two rounds rather than one race because they are not
     * equivalent outcomes: a direct hit costs nothing to keep using, while a proxied one
     * routes every later byte through a stranger's machine. Racing them together would
     * sometimes pick the proxy on a network where the direct route works perfectly well.
     */
    suspend fun fetchText(url: String, proxy: AutoModeProxy? = null): String? {
        RouteRace.first(routes(url, preferredRoute())) { route ->
            ProxiedFetch.get(route)?.takeIf { it.isSuccess }?.let { route to it }
        }?.let { (route, response) ->
            LogUtil.i(AppConfig.TAG, "AutoMode: fetched $route directly")
            rememberRoute(url, route)
            return response.text()
        }

        if (proxy != null) {
            RouteRace.first(routes(url, preferredRoute())) { route ->
                ProxiedFetch.get(route, proxy)?.takeIf { it.isSuccess }?.let { route to it }
            }?.let { (route, response) ->
                LogUtil.i(AppConfig.TAG, "AutoMode: fetched $route via ${proxy.display}")
                rememberRoute(url, route)
                return response.text()
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
    suspend fun fetchProxyList(context: Context, onProgress: (String) -> Unit = {}): List<AutoModeProxy> {
        val url = AutoModeSourceManager.getStore().proxiesUrl.ifBlank { DEFAULT_PROXIES_URL }

        onProgress("Fetching the proxy list…")
        val fetched = fetchText(url)
        if (fetched != null) {
            noteFormat(fetched)
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

    /**
     * Logs when the published list declares a format this build was not written against.
     *
     * The generator stamps every file it publishes with `# format: v1`. Nothing enforces
     * it — an unknown version still gets parsed, because the alternative is a censored
     * network finding no proxies at all over a cosmetic change upstream. This is only what
     * turns that drift from silent into findable in a log.
     */
    private fun noteFormat(body: String) {
        val format = AutoModeProxy.formatOf(body) ?: return
        if (format != AutoModeProxy.SUPPORTED_FORMAT) {
            LogUtil.w(
                AppConfig.TAG,
                "AutoMode: proxy list declares format $format, this build knows ${AutoModeProxy.SUPPORTED_FORMAT}"
            )
        }
    }

    /** The bundled snapshot of the subscription list, for when no route to it works. */
    fun bundledSubs(context: Context): String? = readAsset(context, ASSET_SUBS)

    /**
     * Whether [url] can be fetched without help. A one-byte range request rather than a
     * HEAD, because some CDNs answer HEAD from a cache that does not prove the body is
     * reachable — and because the answer doubles as the size probe.
     */
    suspend fun reachableDirectly(url: String): Boolean {
        val winner = RouteRace.first(routes(url, preferredRoute())) { route ->
            route.takeIf { ProxiedFetch.get(route, null, 0L to 0L)?.isSuccess == true }
        } ?: return false
        rememberRoute(url, winner)
        return true
    }

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
    suspend fun fetchSampled(url: String, proxy: AutoModeProxy? = null): String? {
        val route = workingRoute(url, proxy) ?: return null
        // The size comes out of the probe that found the route. It was always in that
        // response — the old code discarded it and sent an identical request to read the
        // same header back, one wasted round trip per source per run.
        val size = sizeOf(route.probe)

        if (size == null || size <= FULL_DOWNLOAD_LIMIT) {
            return ProxiedFetch.get(route.url, route.proxy)?.takeIf { it.isSuccess }?.text()
        }

        LogUtil.i(AppConfig.TAG, "AutoMode: $url is $size bytes, sampling $SAMPLE_WINDOWS windows")
        return sampleWindows(route.url, route.proxy, size)
    }

    /** A route that answered, the proxy it needed (possibly none), and the probe that proved it. */
    private data class Route(
        val url: String,
        val proxy: AutoModeProxy?,
        val probe: ProxiedFetch.Response,
    )

    /** The first route that answers, raced rather than walked. */
    private suspend fun workingRoute(url: String, proxy: AutoModeProxy?): Route? {
        RouteRace.first(routes(url, preferredRoute())) { candidate ->
            ProxiedFetch.get(candidate, null, 0L to 0L)
                ?.takeIf { it.isSuccess }
                ?.let { Route(candidate, null, it) }
        }?.let {
            rememberRoute(url, it.url)
            return it
        }

        if (proxy != null) {
            RouteRace.first(routes(url, preferredRoute())) { candidate ->
                ProxiedFetch.get(candidate, proxy, 0L to 0L)
                    ?.takeIf { it.isSuccess }
                    ?.let { Route(candidate, proxy, it) }
            }?.let {
                rememberRoute(url, it.url)
                return it
            }
        }
        return null
    }

    /**
     * Total size read out of a range response's `Content-Range: bytes 0-0/N`.
     *
     * Null when the server ignored the range, which is also the signal that windowed
     * sampling is not available and the file has to be taken whole.
     */
    fun sizeOf(response: ProxiedFetch.Response): Long? {
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
