package com.v2ray.ang.automode

import com.tencent.mmkv.MMKV
import com.v2ray.ang.AppConfig
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.LogUtil
import kotlin.math.max
import kotlin.math.min

/**
 * Owns the Auto Mode subscription-source list and the health evidence attached to it.
 *
 * Persisted as one JSON blob in its own MMKV store rather than spread over keys, so the
 * whole thing can be read, written and exported atomically and stays hand-editable.
 */
object AutoModeSourceManager {

    private const val ID_AUTO_MODE = "AUTO_MODE"
    private const val KEY_STORE = "STORE"

    /** Evidence older than a few runs should not decide today's picks. */
    private const val DECAY_FACTOR = 0.92

    private const val DEAD_STREAK_LIMIT = 5
    private const val RESURRECT_EVERY_RUNS = 5

    private val storage by lazy { MMKV.mmkvWithID(ID_AUTO_MODE, MMKV.MULTI_PROCESS_MODE) }

    @Volatile
    private var cached: AutoModeStore? = null

    private val lock = Any()

    fun getStore(): AutoModeStore {
        cached?.let { return it }
        synchronized(lock) {
            cached?.let { return it }
            val store = load()
            cached = store
            return store
        }
    }

    /**
     * Drop the in-memory copy so the next read comes off disk.
     *
     * A run happens in the core's process and writes its results there, so the UI
     * process's cache is stale the moment a run finishes. MMKV itself is multi-process;
     * this cache is not.
     */
    fun reload(): AutoModeStore {
        synchronized(lock) {
            val store = load()
            cached = store
            return store
        }
    }

    private fun load(): AutoModeStore {
        try {
            val json = storage.decodeString(KEY_STORE)
            if (!json.isNullOrBlank()) {
                val store = JsonUtil.fromJsonSafe(json, AutoModeStore::class.java)
                if (store != null) {
                    // Gson happily leaves collections null when the stored JSON predates
                    // a field, and every caller below assumes they are present.
                    @Suppress("SENSELESS_COMPARISON")
                    if (store.sources == null) store.sources = mutableListOf()
                    @Suppress("SENSELESS_COMPARISON")
                    if (store.protocolFilter == null) store.protocolFilter = mutableListOf()
                    @Suppress("SENSELESS_COMPARISON")
                    if (store.countryFilter == null) store.countryFilter = mutableListOf()
                    return store
                }
            }
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "AutoMode: failed to load source store", e)
        }
        return AutoModeStore()
    }

    fun save() {
        synchronized(lock) {
            val store = cached ?: return
            try {
                storage.encode(KEY_STORE, JsonUtil.toJson(store))
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "AutoMode: failed to save source store", e)
            }
        }
    }

    /**
     * Replace the source list with the URLs the user typed, keeping the accumulated
     * health of every URL that survived the edit.
     */
    fun setUrls(urls: List<String>): Int {
        val store = getStore()
        val existing = store.sources.associateBy { it.url }
        val result = mutableListOf<AutoModeSource>()
        val seen = mutableSetOf<String>()

        for (raw in urls) {
            val url = normalizeUrl(raw) ?: continue
            if (!seen.add(url)) {
                continue
            }
            result.add(existing[url] ?: AutoModeSource(url = url))
        }

        store.sources = result
        save()
        return result.size
    }

    fun setEnabled(url: String, enabled: Boolean) {
        val store = getStore()
        val source = store.sources.firstOrNull { it.url == url } ?: return
        source.enabled = enabled
        // A hand re-enable must not be undone by the resurrection probe treating it as
        // still auto-disabled, nor be immediately auto-disabled again by a stale streak.
        source.autoDisabled = false
        if (enabled) {
            source.deadStreak = 0
        }
        save()
    }

    fun setTopCount(count: Int) {
        getStore().topCount = count.coerceIn(1, 50)
        save()
    }

    fun setProtocolFilter(protocols: List<String>) {
        getStore().protocolFilter = protocols.toMutableList()
        save()
    }

    fun setCountryFilter(countries: List<String>) {
        getStore().countryFilter = countries.toMutableList()
        save()
    }

    private val urlRegex = Regex("https?://[^\\s,;'\"]+")

    /**
     * Accepts the shape of a real sub list: one URL per line, commas, stray labels such
     * as "Pack 3: https://..." and github blob links that need the raw host.
     */
    fun parseUrls(text: String?): List<String> {
        val list = mutableListOf<String>()
        if (text.isNullOrEmpty()) {
            return list
        }

        // Angle brackets stay inside the match on purpose: a template row such as
        // ".../countries/<CODE>.sub.txt" must be rejected whole, not silently truncated
        // into the valid-looking but useless ".../countries/".
        for (m in urlRegex.findAll(text)) {
            normalizeUrl(m.value)?.let { list.add(it) }
        }
        return list
    }

    /**
     * Turns free text such as "vless, vmess" into [EConfigType] names. Unknown words are
     * dropped rather than guessed at, so a typo narrows nothing.
     */
    fun parseProtocols(text: String?): MutableList<String> {
        val result = mutableListOf<String>()
        if (text.isNullOrEmpty()) {
            return result
        }

        val known = EConfigType.entries.associateBy({ it.name.lowercase() }, { it.name })

        for (part in text.split(',', ';', ' ', '\t', '\n', '\r')) {
            var token = part.trim().lowercase()
            if (token.isEmpty()) {
                continue
            }
            // "ss" and "shadowsocks" are the same thing to everyone except the enum.
            if (token == "ss") token = EConfigType.SHADOWSOCKS.name.lowercase()
            if (token == "hy2") token = EConfigType.HYSTERIA2.name.lowercase()

            val name = known[token]
            if (name != null && !result.contains(name)) {
                result.add(name)
            }
        }

        return result
    }

    fun normalizeUrl(raw: String?): String? {
        var url = raw?.trim()?.trimEnd('.', ',', ';', ')', ']')
        if (url.isNullOrEmpty()) {
            return null
        }

        if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) {
            return null
        }

        // Placeholder rows in hand-maintained lists, e.g. .../<CODE>.sub.txt
        if (url.contains("YOUR_USERNAME") || url.contains("YOUR_REPOSITORY")
            || url.contains('<') || url.contains('>')
        ) {
            return null
        }

        // A github blob page is HTML; the raw host serves the actual list.
        if (url.startsWith("https://github.com/") && url.contains("/blob/")) {
            url = url.replace("https://github.com/", "https://raw.githubusercontent.com/")
                .replace("/blob/", "/")
        }

        return try {
            val uri = java.net.URI(url)
            if (uri.host.isNullOrBlank()) null else url
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Pick the sources to spend this run on: Thompson sampling over the Beta evidence,
     * plus guaranteed slots for unexplored links and an occasional resurrection probe
     * for links that were auto-disabled after going dead.
     */
    fun selectSources(): List<AutoModeSource> {
        val store = getStore()
        val wanted = store.sourcesPerRun.coerceIn(3, 24)

        // Age out old evidence first, so a source that worked months ago is not still
        // trusted on the strength of that.
        for (s in store.sources) {
            s.alpha = 1 + ((s.alpha - 1) * DECAY_FACTOR)
            s.beta = 1 + ((s.beta - 1) * DECAY_FACTOR)
        }

        val live = store.sources.filter { it.enabled }
        val picked = mutableListOf<AutoModeSource>()

        // Unexplored links first — a brand new link deserves a look before the sampler
        // starts preferring whatever already has a track record.
        picked.addAll(live.filter { it.tried == 0 }.take(2))

        val pool = live.filter { s -> picked.none { it === s } }
        picked.addAll(
            pool.sortedByDescending { BetaSampler.sample(it.alpha, it.beta) }
                .take(max(0, wanted - picked.size))
        )

        // A link that died in March may be alive again in July.
        if (store.runCount % RESURRECT_EVERY_RUNS == 0) {
            store.sources
                .filter { !it.enabled && it.autoDisabled }
                .minByOrNull { it.lastTriedMillis }
                ?.let { picked.add(it) }
        }

        return picked
    }

    /** Record what a source produced this run and let it decay, die or recover. */
    fun applyResult(
        source: AutoModeSource,
        realPingTested: Int,
        realPingOk: Int,
        winners: Int,
        fetchFailed: Boolean,
        hash: String?,
        configCount: Int,
        bestSpeedMbps: Double,
    ) {
        source.tried++
        source.lastTriedMillis = System.currentTimeMillis()

        if (fetchFailed) {
            source.beta += 2
            source.deadStreak++
        } else {
            source.lastConfigCount = configCount
            source.staleRuns = if (hash != null && hash == source.lastHash) source.staleRuns + 1 else 0
            source.lastHash = hash

            source.testedTotal += realPingTested
            source.greenTotal += realPingOk
            source.winnerTotal += winners

            source.alpha += realPingOk + (winners * 2)
            source.beta += max(0, realPingTested - realPingOk)

            if (realPingOk > 0) {
                source.lastGreenMillis = System.currentTimeMillis()
                source.deadStreak = 0
            } else {
                // Contributed nothing usable. Only a soft penalty when it was never
                // actually reached by the real-ping stage.
                if (realPingTested == 0) {
                    source.beta += 1
                }
                source.deadStreak++
            }

            if (bestSpeedMbps > source.bestSpeedMbps) {
                source.bestSpeedMbps = bestSpeedMbps
            }
        }

        if (source.deadStreak >= DEAD_STREAK_LIMIT && source.enabled) {
            source.enabled = false
            source.autoDisabled = true
        } else if (source.deadStreak == 0 && source.autoDisabled) {
            source.enabled = true
            source.autoDisabled = false
        }
    }

    /**
     * Widen the net when a run came up short, narrow it when it did not have to try hard.
     */
    fun adaptSourceCount(store: AutoModeStore, passers: Int, target: Int) {
        store.sourcesPerRun = if (passers < target) {
            min(store.sourcesPerRun + 4, 20)
        } else {
            max(store.sourcesPerRun - 2, 4)
        }
    }
}
