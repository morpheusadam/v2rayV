package com.v2ray.ang.automode

import android.content.Context
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.RealPingEvent
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.dto.entities.SubscriptionItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.service.RealPingWorkerService
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.math.min

/**
 * One button press: fetch a few subscription sources, find the servers that actually
 * work, and leave the best ones in a dedicated group.
 *
 * The funnel is deliberately adaptive rather than fixed-width. Measured against a real
 * source list, only ~5% of TCP-reachable endpoints survive a real tunnel test, and that
 * rate swings with which sources happen to be healthy this week — so the real-ping stage
 * keeps pulling batches until it has enough survivors or spends its budget, instead of
 * testing a guessed number of candidates.
 *
 * Two findings from the same measurement are baked in:
 *  - TCP reachability is used only to drop dead endpoints, never to rank. Ranking by
 *    lowest tcping performed *worse* than random (2.1% vs 7.5% pass), because the
 *    fastest-answering hosts are CDN edges fronting dead proxies.
 *  - Which sources a run spends itself on matters far more than which configs it picks,
 *    which is why source selection is a decayed Thompson sampler.
 *
 * The stage budgets below are smaller than the desktop client's. A desktop run can spend
 * five minutes and a hundred watts; a phone run competes with the screen timeout, the
 * battery and one radio that every concurrent test has to share.
 */
class AutoModeEngine(
    private val context: Context,
    private val onProgress: (String) -> Unit = {},
    private val onEstimate: (Long) -> Unit = {},
    /** Which stage the run has reached, for the timeline on the dashboard. */
    private val onStage: (AutoModeStage) -> Unit = {},
    /**
     * Fired the moment a server is measured at or above the acceptance threshold, before
     * the run has finished. The caller connects on this rather than waiting for the full
     * ranking: a user who pressed a button wants a working tunnel, not the best possible
     * one, and the difference between the two is several minutes.
     */
    private val onFirstAcceptable: (String) -> Unit = {},
    /**
     * Live throughput while a measurement is in flight, in MB/s, with a flag saying whether
     * it is the user's own line or a server being tested. The dashboard shows the needle
     * moving instead of a card that sits at zero for the length of the run.
     */
    private val onSpeedSample: (Double, Boolean) -> Unit = { _, _ -> },
) {

    companion object {
        /** Group holding the surviving best servers. Fixed id so runs find it again. */
        const val TOP_GROUP_ID = "automode-top"

        const val TOP_GROUP_REMARKS = "⚡ Auto Mode"

        /**
         * Prefix for a run's scratch groups. One group per source, so a server's
         * subscriptionId says exactly which link produced it — attribution with no
         * guesswork. All of them are deleted once the run picks its winners.
         */
        private const val POOL_GROUP_PREFIX = "automode-pool-"

        /** Configs imported per run. Kept modest so the profile store stays small. */
        private const val MAX_POOL_SIZE = 900

        /** Cap on the cheap liveness stage. */
        private const val MAX_TCPING = 800

        /** Real-ping batch size. */
        private const val REAL_PING_BATCH = 100

        /** Upper bound on real-ping tests when survivors are scarce. */
        private const val MAX_REAL_PING = 400

        /**
         * Rounds of real-ping per run. Bounded so a run with poor sources still finishes
         * in a predictable time rather than grinding through the whole live pool.
         */
        private const val MAX_REAL_PING_ROUNDS = 4

        /**
         * New servers taken into the speed-test stage, on top of the champions. Each one
         * costs a core start plus a real download, so this is the most expensive stage
         * per server by a wide margin.
         */
        private const val MAX_SPEED_TEST = 10

        /** Existing top-list servers re-tested each run so they can defend a slot. */
        private const val MAX_CHAMPIONS_RETESTED = 8

        /** Beyond this a server is unusable however fast it downloads. */
        private const val MAX_ACCEPTABLE_DELAY = 2500L

        /** Rough seconds one speed test costs, for the countdown. */
        private const val SPEED_TEST_SECONDS = 8.0

        private const val FETCH_CONCURRENCY = 6
    }

    private val stopped = AtomicBoolean(false)

    /**
     * Wall clock spent in each stage, in the order they ran.
     *
     * Every latency judgement about this pipeline has so far come from [estimateSeconds],
     * which exists to drive a countdown and was never evidence. This is a clock, and it is
     * reported at the end of a run so a screenshot from a censored network says *where* the
     * time went instead of only that it was slow.
     */
    private val stageMillis = linkedMapOf<AutoModeStage, Long>()
    private var currentStage: AutoModeStage? = null
    private var currentStageNanos = 0L

    /** Starts [stage], booking the wall clock the previous one spent. */
    private fun beginStage(stage: AutoModeStage) {
        closeStage()
        currentStage = stage
        currentStageNanos = System.nanoTime()
        onStage(stage)
    }

    private fun closeStage() {
        val stage = currentStage ?: return
        val millis = (System.nanoTime() - currentStageNanos) / 1_000_000
        stageMillis[stage] = (stageMillis[stage] ?: 0L) + millis
        currentStage = null
    }

    /** One line naming where the run's wall clock actually went. */
    fun timingLine(): String = stageMillis.entries.joinToString(" · ") { (stage, millis) ->
        "${stage.label} ${String.format(java.util.Locale.US, "%.1f", millis / 1000.0)}s"
    }

    /**
     * Pool guid to the guid it was published under, so the final ranking rewrites the
     * early winner's entry instead of replacing it with a copy and deleting the original
     * out from under a live tunnel.
     */
    private val publishedGuids = mutableMapOf<String, String>()

    fun stop() {
        stopped.set(true)
    }

    private fun isStopped(): Boolean = stopped.get()

    suspend fun run(): AutoModeRunResult = withContext(Dispatchers.IO) {
        val result = AutoModeRunResult()

        /**
         * The line measurement, when it could not be answered from cache. Held rather than
         * awaited so it can run under the liveness stage; cancelled in the finally, because
         * a run that bailed out early has nothing left to compare against and the probe
         * would otherwise hold a socket for six more seconds.
         */
        var pendingBaseline: Deferred<Double>? = null
        var sources: List<AutoModeSource> = emptyList()
        var fetched: Map<String, FetchResult> = emptyMap()
        var poolBySource: Map<String, List<ServerRef>> = emptyMap()
        val realPingTested = mutableSetOf<String>()
        var workingIds: Set<String> = emptySet()
        var winnerIds: Set<String> = emptySet()
        var bestSpeedBySource: Map<String, Double> = emptyMap()
        var proxy: AutoModeProxy? = null
        var baselineMbps = AutoModeBaseline.UNKNOWN
        var acceptThreshold = 0.0

        try {
            val store = AutoModeSourceManager.getStore()
            store.runCount++
            store.lastRunMillis = System.currentTimeMillis()

            // ---- what is this connection capable of --------------------------------
            // Everything after this is a ratio against this number, including the point at
            // which the run stops looking and connects — but nothing needs it until the
            // speed test, which is the last stage of all.
            //
            // So a cached baseline is taken here and a stale one is deferred rather than
            // waited on. Where it gets taken matters: the probe is a full-throttle download
            // and must not share the radio with the source fetches, which would measure the
            // line as slower than it is and quietly lower the bar every server is judged
            // against. The liveness stage is the safe window — thousands of short
            // connections that move almost no bytes — so that is where it goes.
            beginStage(AutoModeStage.MEASURING)
            val cachedBaseline = AutoModeBaseline.cached(context)
            if (cachedBaseline != null) {
                baselineMbps = cachedBaseline
                report("Your connection: ${AutoModeBaseline.format(baselineMbps)}.")
            } else {
                report("Measuring your connection alongside the first tests…")
            }

            if (isStopped()) return@withContext cancelled(result)

            // ---- find a way out, if the direct one is blocked ----------------------
            beginStage(AutoModeStage.ROUTING)
            proxy = resolveRoute(store)

            // ---- top up the source list from the catalog ---------------------------
            beginStage(AutoModeStage.FETCHING)
            refreshCatalog(store, proxy)
            if (store.sources.none { it.enabled }) {
                result.message = "No usable subscription sources could be reached."
                report(result.message)
                return@withContext result
            }

            sources = AutoModeSourceManager.selectSources()
            result.sourcesUsed = sources.size
            report("Auto Mode: run #${store.runCount}, trying ${sources.size} of ${store.sources.size} sources.")

            // ---- fetch -------------------------------------------------------------
            fetched = fetchSources(sources, proxy)
            result.fetched = fetched.count { !it.value.failed }
            if (result.fetched == 0) {
                result.message = "No source could be downloaded. Check the connection."
                report(result.message)
                return@withContext result
            }
            report("Downloaded ${result.fetched} of ${sources.size} sources.")

            if (isStopped()) return@withContext cancelled(result)

            // ---- import candidates -------------------------------------------------
            beginStage(AutoModeStage.IMPORTING)
            poolBySource = importCandidates(sources, fetched)

            // The downloaded bodies are megabytes each and nothing after this point reads
            // them — only the hash and the failure flag.
            fetched = fetched.mapValues { it.value.copy(text = "") }

            var candidates = poolBySource.values.flatten()
            val beforeFilter = candidates.size
            candidates = applyFilters(candidates, store)
            result.candidates = candidates.size

            report(
                if (candidates.size == beforeFilter) "Imported ${candidates.size} candidate servers."
                else "Imported $beforeFilter candidates, ${candidates.size} match the filter."
            )
            if (candidates.isEmpty()) {
                result.message = "Sources downloaded but contained no usable server."
                report(result.message)
                return@withContext result
            }

            if (isStopped()) return@withContext cancelled(result)

            // ---- liveness (drop dead endpoints; never used for ranking) -------------
            // First projection, from the work now known to be ahead. The later stages
            // replace it with figures measured from this device and this network.
            estimateSeconds(
                (min(candidates.size, MAX_TCPING) / 40.0)
                    + (REAL_PING_BATCH * MAX_REAL_PING_ROUNDS / 12.0)
                    + (MAX_SPEED_TEST * SPEED_TEST_SECONDS)
            )

            beginStage(AutoModeStage.PROBING)
            // Launched here rather than awaited here: the liveness stage is the one window
            // in the run where a download does not distort anything. See the note above.
            if (cachedBaseline == null) {
                pendingBaseline = async(Dispatchers.IO) {
                    AutoModeBaseline.measure(
                        context,
                        onProgress = ::report,
                        onSample = { mbps -> onSpeedSample(mbps, true) },
                    )
                }
            }

            val live = tcpingStage(candidates)
            result.tcpAlive = live.size
            report("${live.size} of ${min(candidates.size, MAX_TCPING)} endpoints answered.")
            if (live.isEmpty()) {
                result.message = "No endpoint answered."
                report(result.message)
                return@withContext result
            }

            if (isStopped()) return@withContext cancelled(result)

            // ---- real ping: the stage that actually proves a proxy works ------------
            beginStage(AutoModeStage.TUNNELING)
            val target = max(store.topCount * 2, 12)
            val working = realPingStage(live, target, realPingTested)
            workingIds = working.map { it.guid }.toSet()
            result.realPingOk = working.size
            report("${working.size} servers completed a real request through the tunnel.")

            // Champions from the previous run are re-tested rather than grandfathered,
            // so a server that has since died loses its slot on its own.
            val champions = loadGroup(TOP_GROUP_ID)
            val speedInput = mergeForSpeedTest(working, champions)

            if (speedInput.isEmpty()) {
                result.message =
                    "Nothing survived the tunnel test this run. Try again — different sources are sampled each time."
                report(result.message)
                return@withContext result
            }

            if (isStopped()) return@withContext cancelled(result)

            // ---- speed test --------------------------------------------------------
            // The last possible moment the baseline can be needed, and by now it has had
            // the whole liveness and tunnel stages to finish in.
            beginStage(AutoModeStage.MEASURING_SERVERS)
            pendingBaseline?.let { baselineMbps = it.await() }
            result.baselineMbps = baselineMbps
            acceptThreshold = AutoModeBaseline.acceptThreshold(baselineMbps, store)

            val measurements = speedTestStage(speedInput, acceptThreshold)
            result.speedTested = measurements.size
            result.acceptedMbps = measurements.firstOrNull { isAcceptable(it, acceptThreshold) }?.speedMbps ?: 0.0

            // ---- keep the best -----------------------------------------------------
            val winners = selectWinners(measurements, store)
            winnerIds = winners.map { it.guid }.toSet()
            result.topCount = winners.size

            bestSpeedBySource = bestSpeedPerSource(poolBySource, measurements)

            publishWinners(winners)
            AutoModeSourceManager.adaptSourceCount(store, working.size, target)

            result.success = winners.isNotEmpty()
            result.message = when {
                winners.isEmpty() -> "Auto Mode found no server fast enough to keep."
                // Naming the line speed the servers were measured against is the
                // difference between "5.7 MB/s, is that good?" and an answer.
                baselineMbps > AutoModeBaseline.UNKNOWN ->
                    "${winners.size} servers ready — best ${formatSpeed(winners.first().speedMbps)} " +
                        "of your ${AutoModeBaseline.format(baselineMbps)}."

                else -> "Auto Mode done: ${winners.size} servers kept in \"$TOP_GROUP_REMARKS\"."
            }
            report(result.message)
            return@withContext result
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "AutoMode: run failed", e)
            result.message = "Auto Mode failed: ${e.message ?: e.javaClass.simpleName}"
            report(result.message)
            return@withContext result
        } finally {
            // A run that bailed out early leaves the baseline probe with nothing left to
            // be measured for, and it holds a socket for six seconds if left alone.
            pendingBaseline?.cancel()
            // Carries whatever was known — the cached figure, or nothing — into a result
            // that bailed out before the stage that would otherwise have set it.
            result.baselineMbps = baselineMbps

            closeStage()
            result.timings = timingLine()
            LogUtil.i(AppConfig.TAG, "AutoMode: timings — ${result.timings}")
            report("Timing: ${result.timings}")

            // Runs even when a stage bailed out, so the scratch groups never survive a
            // run and the source health always reflects what actually happened.
            try {
                finishSources(sources, fetched, poolBySource, realPingTested, workingIds, winnerIds, bestSpeedBySource)
                AutoModeSourceManager.save()
                cleanupPools()
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "AutoMode: cleanup failed", e)
            }
        }
    }

    private fun cancelled(result: AutoModeRunResult): AutoModeRunResult {
        result.message = "Auto Mode stopped."
        return result
    }

    //region stages

    private data class FetchResult(val text: String, val hash: String?, val failed: Boolean)

    /** A profile plus the guid it was stored under, which is what every stage keys on. */
    data class ServerRef(val guid: String, val profile: ProfileItem)

    /**
     * Decides how this run will reach the internet: directly if that works, and otherwise
     * through a public proxy found for the purpose.
     *
     * This is the fix for the failure an Iranian tester hit — the run reported "sources
     * downloaded but contained no usable server" because the fetches had returned a block
     * page rather than a list. Checking reachability up front turns a silent wrong answer
     * into a route change, and costs one round trip when the network is open.
     */
    private suspend fun resolveRoute(store: AutoModeStore): AutoModeProxy? {
        val probeUrl = store.subsUrl.ifBlank { AutoModeNetwork.DEFAULT_SUBS_URL }

        if (withContext(Dispatchers.IO) { AutoModeNetwork.reachableDirectly(probeUrl) }) {
            return null
        }

        report("Subscription host unreachable — looking for a proxy to fetch through.")
        val found = AutoModeProxyFinder.ensureProxy(context, probeUrl, ::report)
        if (found == null) {
            // Not fatal: the bundled list and any source on a reachable host still work.
            report("No proxy found. Falling back to whatever can be reached directly.")
        }
        return found
    }

    /**
     * Pulls the catalog of subscription links and folds it into the source list, so a
     * fresh install has something to run on without the user pasting anything.
     *
     * The catalog is a list of links rather than a list of servers, which is the whole
     * reason it is handled here instead of being added as an ordinary source: the import
     * stage deliberately strips bare subscription URLs out of any body it is given, so a
     * catalog fed through that path yields nothing at all.
     *
     * It is still checked for servers, because "point this at a URL" is the obvious thing
     * to do with the setting and the file on the other end could be either.
     */
    private suspend fun refreshCatalog(store: AutoModeStore, proxy: AutoModeProxy?) =
        withContext(Dispatchers.IO) {
            // The bundle first, and unconditionally: it is one fetch to a host with a mirror
            // ladder, carrying a selection this device could not compute for itself. The
            // catalog below is then variety rather than the whole plan.
            if (AutoModeSourceManager.ensureSource(AutoModeNetwork.DEFAULT_SOURCE_URL)) {
                report("Added the curated source list.")
            }

            val url = store.subsUrl.ifBlank { AutoModeNetwork.DEFAULT_SUBS_URL }

            val body = AutoModeNetwork.fetchText(url, proxy)
                // Kept so a later run that cannot reach the network falls back on this
                // rather than on whatever was compiled into the APK.
                ?.also { AutoModeNetwork.cacheSubs(context, it) }
                ?: AutoModeNetwork.bundledSubs(context)?.also {
                    report("Catalog unreachable — using the last copy this phone fetched.")
                }

            if (body.isNullOrBlank()) {
                report("Could not reach the subscription catalog.")
                return@withContext
            }

            val links = AutoModeSourceManager.parseUrls(body)
            if (links.isEmpty()) {
                // Not a catalog after all. Treat it as a source in its own right so a URL
                // pointing straight at a server list is not silently ignored.
                if (AutoModeSourceManager.ensureSource(url)) {
                    report("Catalog holds servers rather than links — added as a source.")
                }
                return@withContext
            }

            val added = AutoModeSourceManager.mergeCatalog(links)
            report(
                if (added > 0) "Catalog: $added new links, ${store.sources.size} sources known."
                else "Catalog: ${store.sources.size} sources known."
            )
        }

    private suspend fun fetchSources(
        sources: List<AutoModeSource>,
        proxy: AutoModeProxy?,
    ): Map<String, FetchResult> = coroutineScope {
        val gate = Semaphore(FETCH_CONCURRENCY)
        sources.map { source ->
            async {
                gate.withPermit {
                    val fetch = try {
                        // Sampled rather than downloaded: a list of tens of millions of
                        // entries is read as a few random byte windows, so the cost of a
                        // run does not grow with the size of the source.
                        val text = AutoModeNetwork.fetchSampled(source.url, proxy)

                        if (text.isNullOrBlank()) {
                            FetchResult("", null, true)
                        } else {
                            FetchResult(text, md5(text), false)
                        }
                    } catch (e: Exception) {
                        LogUtil.e(AppConfig.TAG, "AutoMode: fetch failed for ${source.url}", e)
                        FetchResult("", null, true)
                    }
                    source.url to fetch
                }
            }
        }.awaitAll().toMap()
    }

    /**
     * Turn the downloaded bodies into profiles, one scratch group per source. Each source
     * gets an equal quota so one huge list cannot crowd out the rest, and the quota is
     * filled by random sampling — the "try a few of them each time" behaviour, and also
     * the only selection shown to beat ranking by latency.
     */
    private fun importCandidates(
        sources: List<AutoModeSource>,
        fetched: Map<String, FetchResult>,
    ): Map<String, List<ServerRef>> {
        val result = linkedMapOf<String, List<ServerRef>>()
        val usable = sources.filter { fetched[it.url]?.failed == false && fetched[it.url]?.text.isNullOrBlank() == false }
        if (usable.isEmpty()) {
            return result
        }

        val quota = max(40, MAX_POOL_SIZE / usable.size)

        usable.forEachIndexed { index, source ->
            val poolId = "$POOL_GROUP_PREFIX$index"
            ensureGroup(poolId, "pool ${index + 1}")

            val raw = fetched[source.url]?.text.orEmpty()
            val body = Utils.decode(raw).takeIf { it.isNotBlank() } ?: raw
            val uris = extractUris(body)

            // Not a URI list — Clash YAML, SIP008 JSON and friends. Hand the whole body
            // to the existing parser rather than trying to sample it.
            val payload = if (uris.isEmpty()) {
                stripSubscriptionUrls(raw)
            } else {
                uris.shuffled().take(quota).joinToString("\n")
            }

            if (payload.isBlank()) {
                result[source.url] = emptyList()
                return@forEachIndexed
            }

            // append = false clears whatever the previous run left in this group.
            val (_, subsImported) = AngConfigManager.importBatchConfig(payload, poolId, false)
            if (subsImported > 0) {
                LogUtil.w(AppConfig.TAG, "AutoMode: ${source.url} added $subsImported subscriptions unexpectedly")
            }

            result[source.url] = loadGroup(poolId)
        }

        return result
    }

    /**
     * Narrow the candidate pool to what the user asked for.
     *
     * Protocol is exact — it comes from the parsed config. Country is not: at this point
     * the only evidence is the remark the provider wrote, which is a claim rather than a
     * measurement. So a country filter is applied here only to decide what is worth
     * spending tests on, and is checked again against the measured exit country when the
     * winners are chosen. Candidates whose remark says nothing about a country are kept
     * rather than dropped — an unlabelled server in the right country is still a good
     * server, and the measurement will settle it.
     */
    private fun applyFilters(candidates: List<ServerRef>, store: AutoModeStore): List<ServerRef> {
        var filtered = candidates

        if (store.protocolFilter.isNotEmpty()) {
            val wanted = store.protocolFilter.map { it.uppercase() }.toSet()
            filtered = filtered.filter { wanted.contains(it.profile.configType.name.uppercase()) }
        }

        if (store.countryFilter.isNotEmpty()) {
            val wanted = store.countryFilter.map { it.uppercase() }.toSet()
            val labelled = filtered.map { it to CountryHint.fromRemark(it.profile.remarks) }

            // Prefer the ones that claim the right country, then top up with unlabelled
            // ones so a filter never starves the run of anything to test.
            val matching = labelled.filter { it.second != null && wanted.contains(it.second) }.map { it.first }
            val unlabelled = labelled.filter { it.second == null }.map { it.first }
            filtered = matching + unlabelled
        }

        return filtered
    }

    private val uriSchemes: List<String> by lazy {
        EConfigType.entries.map { it.protocolScheme }.filter { it.isNotBlank() }.distinct()
    }

    /**
     * Drop lines that are bare subscription links.
     *
     * `AngConfigManager.importBatchConfig` treats any line that looks like a subscription
     * URL as one to *add*, and then updates every subscription the user has. Plenty of
     * free sources are exactly that — a list of other people's links — so without this a
     * run would quietly fill the user's subscription list with whatever it happened to
     * fetch, and trigger a full refresh of everything while it was at it.
     *
     * Structured bodies are unharmed: a URL inside YAML or JSON is preceded by a key or a
     * quote, so it is never a bare URL on a line of its own.
     */
    fun stripSubscriptionUrls(body: String): String =
        body.lineSequence()
            .filterNot { Utils.isValidSubUrl(it.trim()) }
            .joinToString("\n")

    private fun extractUris(body: String): List<String> {
        return body.lineSequence()
            .map { it.trim() }
            .filter { line -> line.length > 12 && uriSchemes.any { line.startsWith(it, ignoreCase = true) } }
            .toList()
    }

    /**
     * Drop endpoints that answer nothing. The resulting delays are deliberately *not*
     * used to order the next stage: measured pass rate for the lowest-tcping candidates
     * was 2.1% against 7.5% for a random draw from the same pool.
     */
    private suspend fun tcpingStage(candidates: List<ServerRef>): List<ServerRef> {
        // Which candidates get a slot is decided by protocol and country — prior evidence
        // read off the config, not a measurement — and randomised within each tier. The
        // *results* are still used only to drop the dead, never to order what follows.
        val subset = if (candidates.size > MAX_TCPING) {
            AutoModeRanker.prioritise(candidates) { it.profile }.take(MAX_TCPING)
        } else {
            candidates
        }
        val delays = runPingBatch(subset.map { it.guid }, onlyTcp = true)
        return subset.filter { (delays[it.guid] ?: -1L) > 0 }
    }

    /**
     * Keep testing batches until enough servers have proven they tunnel, or the budget
     * runs out. Batches are drawn in random order for the reason above.
     *
     * The target is now checked on every result rather than at the end of each batch. That
     * matters more than it sounds: a batch is a hundred tests and the target is twenty, so
     * a healthy pool used to spend four fifths of this stage proving servers it had already
     * decided it did not need.
     */
    private suspend fun realPingStage(
        live: List<ServerRef>,
        target: Int,
        testedIds: MutableSet<String>,
    ): List<ServerRef> {
        // Same prior, applied again: the batches that go first are the ones most likely to
        // contain something that tunnels, so the stage reaches its target in fewer rounds.
        var queue = AutoModeRanker.prioritise(live) { it.profile }
        val working = mutableListOf<ServerRef>()
        var tested = 0
        var round = 0

        while (queue.isNotEmpty() && tested < MAX_REAL_PING && working.size < target
            && round < MAX_REAL_PING_ROUNDS && !isStopped()
        ) {
            round++
            val batch = queue.take(REAL_PING_BATCH)
            queue = queue.drop(batch.size)

            val batchStarted = System.currentTimeMillis()
            // Counted rather than read off the map, because this is called from the
            // worker's threads and the map it would otherwise size is still being filled.
            val survivors = java.util.concurrent.atomic.AtomicInteger(working.size)
            val delays = runPingBatch(batch.map { it.guid }, onlyTcp = false) { _, delayMillis ->
                val enough = delayMillis in 1..MAX_ACCEPTABLE_DELAY
                    && survivors.incrementAndGet() >= target
                !enough && !isStopped()
            }
            val batchSeconds = (System.currentTimeMillis() - batchStarted) / 1000.0

            // Only what actually got a result counts as tested: a batch cut short must not
            // charge its sources for servers that were never tried.
            tested += delays.size
            delays.keys.forEach { testedIds.add(it) }

            working.addAll(batch.filter { (delays[it.guid] ?: -1L) in 1..MAX_ACCEPTABLE_DELAY })

            report("  tunnel test $tested/${min(live.size, MAX_REAL_PING)} — ${working.size} working so far.")

            // Now that a batch has been timed, project from that instead of the prior:
            // how many rounds are still likely, plus the speed test that follows them.
            val roundsLeft = if (working.size >= target) 0 else max(0, MAX_REAL_PING_ROUNDS - round)
            estimateSeconds(
                (roundsLeft * batchSeconds) + (min(working.size + target, MAX_SPEED_TEST) * SPEED_TEST_SECONDS)
            )
        }

        return working
    }

    /**
     * The current champions plus this run's survivors, deduplicated by endpoint so a
     * server that reappears in a fresh fetch cannot occupy two slots.
     *
     * Champions go in first and are never squeezed out by the newcomer cap: a slot is
     * only lost by being beaten in the same speed test, not by arriving earlier. Without
     * that, a run turning up plenty of mediocre servers would silently evict a fast one
     * that was still working.
     */
    fun mergeForSpeedTest(working: List<ServerRef>, champions: List<ServerRef>): List<ServerRef> {
        val merged = mutableListOf<ServerRef>()
        val seen = mutableSetOf<String>()

        fun add(items: List<ServerRef>, limit: Int) {
            var added = 0
            for (item in items) {
                if (added >= limit) return
                if (item.profile.configType == EConfigType.CUSTOM) continue
                if ((item.profile.serverPort?.toIntOrNull() ?: 0) <= 0) continue
                if (seen.add(endpointKey(item.profile))) {
                    merged.add(item)
                    added++
                }
            }
        }

        add(champions, MAX_CHAMPIONS_RETESTED)
        // The speed test is the most expensive stage by a wide margin — a core start plus
        // a real download each — so the newcomers that get a slot are the best-scoring
        // ones rather than whichever happened to survive first.
        add(AutoModeRanker.prioritise(working) { it.profile }, MAX_SPEED_TEST)
        return merged
    }

    private fun endpointKey(item: ProfileItem): String =
        "${item.configType}|${item.server}|${item.serverPort}|${item.password}"

    /**
     * Serial by design: two downloads racing over one radio measure the radio, not the
     * servers.
     *
     * The first server to reach [acceptThreshold] is published and handed to the caller
     * immediately, and the stage then keeps going to fill the reserve. This is the
     * difference between a button that takes four minutes and one that takes twenty
     * seconds: the remaining tests only improve a list the user is not waiting on, so
     * making them wait for those is a cost with nothing bought.
     *
     * With no baseline the threshold is zero, and the first server that carries any
     * traffic at all is accepted — worse than a measured bar, still better than making a
     * user with an unmeasurable connection wait for the whole run.
     */
    private suspend fun speedTestStage(
        input: List<ServerRef>,
        acceptThreshold: Double,
    ): List<AutoModeMeasurement> = coroutineScope {
        val measurements = mutableListOf<AutoModeMeasurement>()
        var accepted = false

        // Cores that have been brought up but not yet measured through. Tracked so a run
        // that is stopped mid-stage cannot leave one running.
        val warmed = java.util.Collections.synchronizedList(mutableListOf<AutoModeSpeedTester.WarmCore>())

        // Starting the next server's core while the current one downloads. This is
        // pipelining, not parallelism: bringing a core up binds a loopback socket and
        // initialises an outbound, and sends nothing over the air, so the rule that only
        // one *download* may be in flight — the rule the whole ratio depends on — is
        // untouched. NonCancellable because a half-started core still has to be stopped,
        // and it is bounded by the core's own three-second start timeout anyway.
        fun prewarm(next: ServerRef?): Deferred<AutoModeSpeedTester.WarmCore?>? = next?.let { ref ->
            async(Dispatchers.IO + NonCancellable) {
                AutoModeSpeedTester.start(context, ref.guid)?.also { warmed.add(it) }
            }
        }

        var warming = prewarm(input.firstOrNull())

        try {
            input.forEachIndexed { index, ref ->
                if (isStopped() || !currentCoroutineContext().isActive) {
                    return@forEachIndexed
                }
                report("Speed testing ${index + 1}/${input.size}: ${ref.profile.remarks.take(40)}")
                estimateSeconds((input.size - index) * SPEED_TEST_SECONDS)

                val warm = warming?.await()
                warming = prewarm(input.getOrNull(index + 1))
                if (warm == null) {
                    return@forEachIndexed
                }

                // The delay stages already proved this one tunnels; carry that number
                // forward rather than paying for it twice. Read before the download so the
                // acceptance test below has it the moment throughput is known.
                val knownDelay = MmkvManager.decodeServerAffiliationInfo(ref.guid)?.testDelayMillis ?: -1

                val measurement = AutoModeSpeedTester.measure(
                    warm = warm,
                    onSample = { mbps -> onSpeedSample(mbps, false) },
                    onThroughput = { throughput ->
                        // Fired before the exit-country lookup, so the user is connected
                        // while that round trip happens rather than after it.
                        throughput.delayMillis = knownDelay
                        if (!accepted && isAcceptable(throughput, acceptThreshold)) {
                            accepted = true
                            publishEarlyWinner(throughput)
                            report(
                                "Good enough at ${formatSpeed(throughput.speedMbps)} — connecting now, "
                                    + "still filling the reserve."
                            )
                        }
                    },
                )
                warmed.remove(warm)
                measurement.delayMillis = knownDelay
                measurements.add(measurement)
            }
        } finally {
            withContext(NonCancellable) {
                warming?.await()
                synchronized(warmed) { warmed.toList() }.forEach { it.stop() }
                warmed.clear()
            }
        }

        measurements
    }

    fun isAcceptable(measurement: AutoModeMeasurement, acceptThreshold: Double): Boolean =
        measurement.speedMbps > 0
            && measurement.delayMillis in 1..MAX_ACCEPTABLE_DELAY
            && measurement.speedMbps >= acceptThreshold

    /**
     * Puts one server into the top group so it can be connected to right away.
     *
     * Written through the same path the final ranking uses, so the guid it lands on is the
     * guid it keeps when the run finishes and rewrites the group. Without that the user
     * would be connected to a profile the end of the run then deleted.
     */
    private fun publishEarlyWinner(winner: AutoModeMeasurement) {
        val guids = publishWinners(listOf(winner), replaceGroup = false)
        guids.firstOrNull()?.let(onFirstAcceptable)
    }

    fun selectWinners(measured: List<AutoModeMeasurement>, store: AutoModeStore): List<AutoModeMeasurement> {
        val scored = measured
            .filter { it.speedMbps > 0 && it.delayMillis in 1..MAX_ACCEPTABLE_DELAY }
            // Throughput still decides, but in half-megabyte buckets, so that servers which
            // measured within noise of each other are separated by country and protocol
            // rather than by which one happened to catch a better second.
            .sortedWith { a, b -> AutoModeRanker.compareWinners(a, b) }

        if (store.countryFilter.isEmpty()) {
            return scored.take(store.topCount)
        }

        val wanted = store.countryFilter.map { it.uppercase() }.toSet()
        // Where the traffic actually came out, measured through the tunnel. Falls back to
        // the provider's label only when no measurement exists.
        val inCountry = scored.filter {
            val country = it.exitCountry ?: CountryHint.fromRemark(it.profile.remarks)
            country != null && wanted.contains(country)
        }

        // Filling the remaining slots from outside the wanted countries beats handing back
        // a half-empty list; the ones that do match are still ranked first.
        val winners = inCountry.take(store.topCount).toMutableList()
        if (winners.size < store.topCount) {
            winners.addAll(scored.filter { m -> inCountry.none { it.guid == m.guid } }.take(store.topCount - winners.size))
        }

        return winners
    }

    /**
     * Move the winners into the top group and label them with their measured numbers.
     *
     * @param replaceGroup true for the run's final ranking, which also drops the entries
     *        that lost their slot. False for the early winner published mid-run, which is
     *        added to the group without disturbing what is already in it — the run is not
     *        finished and has no standing to evict anything yet.
     * @return the guids the winners were published under, in order.
     */
    private fun publishWinners(
        winners: List<AutoModeMeasurement>,
        replaceGroup: Boolean = true,
    ): List<String> {
        if (winners.isEmpty()) {
            return emptyList()
        }

        ensureGroup(TOP_GROUP_ID, TOP_GROUP_REMARKS)

        val existing = MmkvManager.decodeServerList(TOP_GROUP_ID)
        val previous = existing.toSet()
        val store = AutoModeSourceManager.getStore()
        val speeds = store.speedByGuid
        val countries = store.countryByGuid

        val keptGuids = mutableListOf<String>()
        winners.forEachIndexed { index, winner ->
            // The measured exit country when there is one, and the provider's claim only as a
            // fallback — a country shown to the user should mean where the traffic came out,
            // not where a remark said it would.
            val country = winner.exitCountry ?: CountryHint.fromRemark(winner.profile.remarks)
            val profile = winner.profile.copy(
                subscriptionId = TOP_GROUP_ID,
                remarks = label(index, winner, country),
            )

            // Three ways a guid can be settled, in order of precedence:
            //
            //  - It was already published earlier in this run, as the early winner. That
            //    entry is very likely the one the tunnel is now running on, so the final
            //    ranking must land on the same guid rather than mint a second copy and
            //    delete the live one.
            //  - It is a champion from a previous run, which keeps its guid for the same
            //    reason across runs.
            //  - It is new, and still lives in a scratch group about to be deleted
            //    wholesale, so it is copied out under a guid of its own.
            val guid = publishedGuids[winner.guid]
                ?: winner.guid.takeIf { previous.contains(it) }
                ?: Utils.getUuid()
            publishedGuids[winner.guid] = guid

            MmkvManager.encodeServerConfig(guid, profile)
            MmkvManager.encodeServerTestDelayMillis(guid, winner.delayMillis)
            speeds[guid] = winner.speedMbps
            country?.let { countries[guid] = it }
            keptGuids.add(guid)
        }

        if (replaceGroup) {
            // Written in one go so the group is never observed half-replaced, then the
            // entries that lost their slot are dropped.
            MmkvManager.encodeServerList(keptGuids.toMutableList(), TOP_GROUP_ID)
            previous.filterNot { keptGuids.contains(it) }.forEach { MmkvManager.removeServer(it) }
            // The speeds of evicted servers would otherwise accumulate forever, and would
            // be read back for a guid that no longer names anything.
            speeds.keys.retainAll(keptGuids.toSet())
            countries.keys.retainAll(keptGuids.toSet())
        } else {
            val merged = (keptGuids + existing).distinct().toMutableList()
            MmkvManager.encodeServerList(merged, TOP_GROUP_ID)
        }

        AutoModeSourceManager.save()
        return keptGuids
    }

    private fun formatSpeed(mbPerSecond: Double): String =
        if (mbPerSecond <= 0) "?" else String.format(java.util.Locale.US, "%.1fMB/s", mbPerSecond)

    /**
     * What a winning server is called once it reaches the reserve.
     *
     * The provider's own remark used to be carried through to here, and it is the one part of
     * a public subscription written for the publisher rather than the user: overwhelmingly an
     * advert — a channel to join, a site to visit — often in a script and language the rest of
     * the app does not use. It was never load-bearing. Everything worth knowing about one of
     * these servers is measured rather than claimed, and all of it is already in this line.
     *
     * The country segment is dropped rather than filled with a placeholder when nothing could
     * be measured: "#3 2.1MB/s · 180ms" is a complete description of a server whose exit is
     * unknown, whereas a dash pretending to be a country is not.
     */
    fun label(index: Int, winner: AutoModeMeasurement, country: String?): String = buildString {
        append("#${index + 1} ")
        append(formatSpeed(winner.speedMbps))
        append(" · ${winner.delayMillis}ms")
        if (!country.isNullOrBlank()) {
            append(" · $country")
        }
    }

    //endregion stages

    //region helpers

    /**
     * Runs one batch of delay tests, handing each result to [onResult] as it lands and
     * stopping the whole batch the moment [onResult] answers false.
     *
     * [RealPingWorkerService] has always emitted a [RealPingEvent.Result] per server — it is
     * callback-shaped because upstream drives it from a notification. The old bridge here
     * collected those into a map and resumed only on [RealPingEvent.Finish], which threw the
     * streaming away: a stage that wanted twenty survivors still sat through all hundred
     * tests. Nothing about the tests themselves changed; the caller is simply now allowed to
     * say when it has enough.
     *
     * [onResult] is called from the worker's own threads, several at once, so it must be
     * safe to call concurrently.
     */
    private suspend fun runPingBatch(
        guids: List<String>,
        onlyTcp: Boolean,
        onResult: (guid: String, delayMillis: Long) -> Boolean = { _, _ -> true },
    ): Map<String, Long> {
        if (guids.isEmpty()) {
            return emptyMap()
        }

        return suspendCancellableCoroutine { cont ->
            val results = java.util.concurrent.ConcurrentHashMap<String, Long>()
            // The batch can now end two ways — enough survivors, or every test finished —
            // and both can be reached from several threads at once.
            val settled = AtomicBoolean(false)
            var worker: RealPingWorkerService? = null

            fun finish() {
                if (settled.compareAndSet(false, true) && cont.isActive) {
                    cont.resume(HashMap(results))
                }
            }

            worker = RealPingWorkerService(
                context = context,
                guids = guids,
                onlyTcp = onlyTcp,
                onEvent = { event ->
                    when (event) {
                        is RealPingEvent.Result -> {
                            results[event.guid] = event.delayMillis
                            MmkvManager.encodeServerTestDelayMillis(event.guid, event.delayMillis)
                            if (!settled.get() && !onResult(event.guid, event.delayMillis)) {
                                worker?.cancel()
                                finish()
                            }
                        }

                        is RealPingEvent.Finish -> finish()

                        is RealPingEvent.Progress -> Unit
                    }
                }
            )
            cont.invokeOnCancellation { worker.cancel() }
            worker.start()
        }
    }

    private fun ensureGroup(id: String, remarks: String) {
        if (MmkvManager.decodeSubscription(id) != null) {
            return
        }
        MmkvManager.encodeSubscription(
            id,
            SubscriptionItem(
                remarks = remarks,
                url = "",
                // Never touched by a normal "update all subscriptions" run.
                enabled = false,
            )
        )
    }

    private fun loadGroup(subId: String): List<ServerRef> =
        MmkvManager.decodeServerList(subId).mapNotNull { guid ->
            val profile = MmkvManager.decodeServerConfig(guid) ?: return@mapNotNull null
            if (profile.configType == EConfigType.CUSTOM) return@mapNotNull null
            if ((profile.serverPort?.toIntOrNull() ?: 0) <= 0) return@mapNotNull null
            ServerRef(guid, profile)
        }

    private fun bestSpeedPerSource(
        poolBySource: Map<String, List<ServerRef>>,
        measurements: List<AutoModeMeasurement>,
    ): Map<String, Double> {
        val speedByGuid = measurements.associate { it.guid to it.speedMbps }
        return poolBySource.mapValues { (_, refs) ->
            refs.mapNotNull { speedByGuid[it.guid] }.maxOrNull() ?: 0.0
        }
    }

    /**
     * Attribute this run's outcomes back to the sources that produced them. Every
     * candidate still carries the scratch group it was imported into, so which link
     * produced which working server is exact rather than inferred.
     */
    private fun finishSources(
        sources: List<AutoModeSource>,
        fetched: Map<String, FetchResult>,
        poolBySource: Map<String, List<ServerRef>>,
        realPingTestedIds: Set<String>,
        workingIds: Set<String>,
        winnerIds: Set<String>,
        bestSpeedBySource: Map<String, Double>,
    ) {
        for (source in sources) {
            val fetch = fetched[source.url]
            if (fetch == null || fetch.failed) {
                AutoModeSourceManager.applyResult(source, 0, 0, 0, true, null, 0, 0.0)
                continue
            }

            val mine = poolBySource[source.url].orEmpty()
            val tested = mine.count { realPingTestedIds.contains(it.guid) }
            val ok = mine.count { workingIds.contains(it.guid) }
            val won = mine.count { winnerIds.contains(it.guid) }

            AutoModeSourceManager.applyResult(
                source, tested, ok, won, false, fetch.hash, mine.size, bestSpeedBySource[source.url] ?: 0.0
            )
        }
    }

    /**
     * Drop every scratch group and the several hundred servers in it. Winners have
     * already been copied out, so nothing worth keeping is lost.
     */
    private fun cleanupPools() {
        MmkvManager.decodeSubscriptions()
            .filter { it.guid.startsWith(POOL_GROUP_PREFIX) }
            .forEach { MmkvManager.removeSubscription(it.guid) }
    }

    private fun report(message: String) {
        LogUtil.i(AppConfig.TAG, "AutoMode: $message")
        onProgress(message)
    }

    private fun estimateSeconds(seconds: Double) {
        onEstimate(max(0.0, seconds * 1000).toLong())
    }

    private fun md5(text: String): String =
        MessageDigest.getInstance("MD5").digest(text.toByteArray())
            .joinToString("") { "%02x".format(it) }

    //endregion helpers
}
