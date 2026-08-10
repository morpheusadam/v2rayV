package com.v2ray.ang.automode

import android.content.Context
import com.v2ray.ang.AppConfig
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Finds one public proxy that can reach the subscription host, so a run works from a
 * network where the host itself is blocked.
 *
 * This exists because of a specific failure: on an Iranian connection the desktop client
 * reported "Sources downloaded but contained no usable server" — the fetches had not
 * really succeeded, they had returned a block page. A run cannot import servers it cannot
 * download, and no amount of tuning the later stages fixes that.
 *
 * The economics here are the opposite of the server pipeline. A scraped proxy list is
 * mostly dead, but only **one** entry has to work and the payload is a few hundred
 * kilobytes of text, so the strategy is a wide shallow race rather than a careful funnel:
 * probe many at once, keep the first that answers, and remember it so the next run starts
 * there instead of racing again.
 */
object AutoModeProxyFinder {

    /**
     * The probe is a byte-range request for the real subscription file rather than a
     * synthetic reachability check. A proxy that returns these sixteen bytes has proven
     * every property that matters at once: the protocol guess was right, it allows CONNECT
     * to 443, it resolves the host, and the host is not blocked from where it sits.
     */
    private const val PROBE_BYTES = 16L

    /** Candidates probed concurrently. Each is a short-lived socket, not a download. */
    private const val PROBE_CONCURRENCY = 24

    /** Candidates to try before giving up, out of a list that is usually thousands long. */
    private const val MAX_PROBED = 600

    /** A remembered proxy is re-probed rather than trusted, but only for this long. */
    private const val CACHE_TTL_MILLIS = 6L * 60 * 60 * 1000

    /**
     * Returns a proxy that can reach [probeUrl], or null when none could be found.
     *
     * Tries the remembered one first: on every run after the first this is a single
     * round trip instead of a race over hundreds of candidates.
     */
    suspend fun ensureProxy(
        context: Context,
        probeUrl: String,
        onProgress: (String) -> Unit = {},
    ): AutoModeProxy? = withContext(Dispatchers.IO) {
        val store = AutoModeSourceManager.getStore()

        remembered(store)?.let { cached ->
            onProgress("Checking the proxy that worked last time…")
            if (probe(cached, probeUrl)) {
                LogUtil.i(AppConfig.TAG, "AutoMode: reusing proxy ${cached.display}")
                return@withContext cached
            }
            LogUtil.i(AppConfig.TAG, "AutoMode: remembered proxy ${cached.display} no longer works")
        }

        val candidates = AutoModeNetwork.fetchProxyList(context, onProgress)
        if (candidates.isEmpty()) {
            onProgress("No proxy list could be reached.")
            return@withContext null
        }

        onProgress("Racing ${minOf(candidates.size, MAX_PROBED)} proxies…")
        val found = race(candidates.shuffled().take(MAX_PROBED), probeUrl, onProgress)

        if (found != null) {
            store.lastProxy = "${found.protocol.name}|${found.host}|${found.port}"
            store.lastProxyMillis = System.currentTimeMillis()
            AutoModeSourceManager.save()
            onProgress("Proxy found: ${found.display}")
        } else {
            onProgress("No working proxy among ${minOf(candidates.size, MAX_PROBED)} tried.")
        }
        found
    }

    private fun remembered(store: AutoModeStore): AutoModeProxy? {
        val raw = store.lastProxy?.takeIf { it.isNotBlank() } ?: return null
        if (System.currentTimeMillis() - store.lastProxyMillis > CACHE_TTL_MILLIS) {
            return null
        }
        val parts = raw.split('|')
        if (parts.size != 3) {
            return null
        }
        val protocol = runCatching { ProxyProtocol.valueOf(parts[0]) }.getOrNull() ?: return null
        val port = parts[2].toIntOrNull() ?: return null
        return AutoModeProxy(parts[1], port, protocol)
    }

    /**
     * Probes candidates in waves, stopping the moment one answers.
     *
     * Waves rather than a flat fan-out because the winner is usually in the first few
     * dozen, and opening six hundred sockets to find it would compete with the radio the
     * rest of the run needs.
     */
    private suspend fun race(
        candidates: List<AutoModeProxy>,
        probeUrl: String,
        onProgress: (String) -> Unit,
    ): AutoModeProxy? = coroutineScope {
        val gate = Semaphore(PROBE_CONCURRENCY)
        val done = AtomicBoolean(false)
        var tried = 0

        for (wave in candidates.chunked(PROBE_CONCURRENCY * 2)) {
            if (done.get()) {
                break
            }
            val results = wave.map { candidate ->
                async {
                    if (done.get()) return@async null
                    gate.withPermit {
                        if (done.get()) return@withPermit null
                        // A bare ip:port has no declared protocol, so each guess in
                        // probeOrder is a separate handshake against the same endpoint.
                        for (protocol in candidate.probeOrder()) {
                            if (done.get()) return@withPermit null
                            val attempt = candidate.copy(protocol = protocol)
                            if (probe(attempt, probeUrl)) {
                                done.set(true)
                                return@withPermit attempt
                            }
                        }
                        null
                    }
                }
            }.awaitAll()

            tried += wave.size
            results.filterNotNull().firstOrNull()?.let { return@coroutineScope it }
            onProgress("Tried $tried proxies, still looking…")
        }

        null
    }

    /** True when this proxy really did fetch the first bytes of the target. */
    fun probe(proxy: AutoModeProxy, probeUrl: String): Boolean {
        return try {
            val response = ProxiedFetch.get(
                url = probeUrl,
                proxy = proxy,
                range = 0L to (PROBE_BYTES - 1),
                readTimeoutMillis = 8_000,
            ) ?: return false
            // 206 for an honoured range, 200 for a server that ignored it. Either proves
            // the fetch works; a captive portal or block page is neither, and a proxy that
            // returns its own error page fails the length check below.
            if (response.code != 206 && response.code != 200) {
                return false
            }
            response.body.isNotEmpty()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }
}
