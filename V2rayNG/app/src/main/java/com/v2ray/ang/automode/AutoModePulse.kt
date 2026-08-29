package com.v2ray.ang.automode

import android.content.Context
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.RealPingEvent
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.service.RealPingWorkerService
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.ceil

/**
 * Asks the reserve whether it is still alive, cheaply.
 *
 * ## Why this exists
 *
 * Auto Mode had exactly two states: doing nothing, or a two-to-four minute run that
 * downloads hundreds of megabytes. Every "be more proactive about keeping servers ready"
 * idea died on that, because the only tool available was far too expensive to fire on a
 * hunch — and so the reserve was only ever refilled at the moment the user had already run
 * out, which is the one moment they are least willing to wait.
 *
 * But the expensive part of that pipeline is not the part that answers the only question
 * worth asking between runs. "Do the ten servers I already have still work?" is a real
 * ping each: one short request through a throwaway core, kilobytes, seconds. That is cheap
 * enough to run on a schedule, cheap enough to run when the app is merely opened, and
 * cheap enough to run on a metered connection without spending anything the user would
 * notice.
 *
 * ## What it is not
 *
 * It does not fetch, import, rank, or speed test, and it never touches the profile store
 * beyond recording what it measured. It cannot promote a server, demote one, or delete
 * one. A pulse that finds the reserve dead does not fix it — it records that, and lets
 * [AutoModeScheduler.isRefreshDue] reach the obvious conclusion the next time it is asked.
 *
 * Keeping it that narrow is deliberate. A background job that could rewrite the reserve
 * would be a second implementation of the thing [AutoModeEngine] already does, running
 * unsupervised, on someone else's battery.
 *
 * ## Running it with the tunnel up
 *
 * Permitted, and it is the only part of Auto Mode that is. `CoreVpnService` excludes this
 * package from its own routing, so the throwaway cores a ping starts go over the real
 * network rather than back down the tunnel. That matters more than it sounds: a user with
 * always-on VPN never reaches the one moment the existing catch-up refresh waits for — the
 * tunnel stopping — so before this, that user got no background maintenance at all, ever.
 */
object AutoModePulse {

    /**
     * How long a pulse result is trusted for.
     *
     * Public servers die in hours rather than days, so a twelve-hour-old "it answered" is
     * weak evidence — but it is evidence, and the alternative reading of an expired entry
     * is not "dead", it is "unknown", which the low-water test already treats as a reason
     * to refresh. Short enough to notice a reserve that died overnight; long enough that a
     * phone left in a drawer does not accumulate a refresh debt it will pay all at once.
     */
    val ALIVE_TTL_MILLIS: Long = TimeUnit.HOURS.toMillis(12)

    /**
     * How stale the last pulse must be before opening the app triggers another.
     *
     * Long enough that opening the app twice in a row costs nothing, short enough that a
     * user who opens it after lunch gets a current answer before they press anything.
     */
    val FOREGROUND_TTL_MILLIS: Long = TimeUnit.MINUTES.toMillis(45)

    /**
     * A server slower than this is not counted as alive.
     *
     * 🔴 It has to follow the same rule the pipeline uses, including Iran mode's looser
     * one. Hardcoding the ordinary 2500 ms here meant that with Iran mode on — where an
     * ordinary Iranian server answers in three or four seconds, which is the entire reason
     * the ceiling was raised — every pulse marked the whole reserve dead. `isRefreshDue`
     * then read that as "nothing works", and fired a full run, every six hours, forever,
     * against a reserve that was fine.
     */
    private fun maxDelay(iranMode: Boolean): Long =
        if (iranMode) IranMode.MAX_DELAY_MILLIS else 2500L

    /**
     * How much of the reserve has to be alive before a refresh is unnecessary.
     *
     * Higher than the old count-based low-water mark of a half, because the number now
     * means something: with a real signal the point is to top up *before* the user hits
     * the wall, and finding out at 0.5 that half the reserve is dead is later than it
     * needs to be.
     */
    private const val ALIVE_FRACTION = 0.6

    /**
     * The whole reserve at once, gently.
     *
     * The engine's stages fan out to sixteen by default because a run is a race against
     * the user's patience. A pulse is not — nobody is waiting for it, it is at most ten
     * servers, and a background job that opens sixteen cores at once on a phone that woke
     * up for something else is exactly the kind of thing that gets an app blamed for
     * battery.
     */
    private const val CONCURRENCY = 4

    /** Everything a pulse learned, for the caller to log or act on. */
    data class Result(val checked: Int, val alive: Int)

    /**
     * Pings every server in the reserve and records which ones answered.
     *
     * @return what it found, or null when there was nothing to check.
     */
    suspend fun run(context: Context): Result? {
        // A run is about to replace the whole reserve, so measuring it would be work
        // thrown away — and worse, the two would be writing the same store and starting
        // throwaway cores over the same radio at the same time. The pulse is the one that
        // defers: it is seconds long and nobody is waiting for it.
        // Claimed rather than merely read. A plain check is a check-then-act with several
        // seconds of act after it: a run starting a fraction of a second later would reach
        // its speed test with the pulse's cores still open, and that stage divides against
        // a single-stream baseline — extra cores on the radio depress it and bias
        // acceptance toward rejecting good servers. See 07-decisions.md.
        if (!AutoModeEngine.tryClaimForPulse()) {
            LogUtil.i(AppConfig.TAG, "AutoMode: a run is in flight, skipping the pulse")
            return null
        }
        try {
            return pulse(context)
        } finally {
            AutoModeEngine.releasePulseClaim()
        }
    }

    private suspend fun pulse(context: Context): Result? {

        val guids = MmkvManager.decodeServerList(AutoModeEngine.TOP_GROUP_ID)
            .filter { MmkvManager.decodeServerConfig(it) != null }

        if (guids.isEmpty()) {
            // Recorded even though nothing was measured. Leaving the timestamp at zero
            // means isPulseDue answers true forever, so every app open would ask for a
            // pulse, which binds the core's process and loads the Go runtime into it just
            // to find the same empty list again. There is nothing to check, and saying so
            // is a real answer.
            AutoModeSourceManager.recordLiveness(
                alive = emptyMap(),
                dead = emptyList(),
                present = emptySet(),
                checkedAt = System.currentTimeMillis(),
            )
            LogUtil.i(AppConfig.TAG, "AutoMode: nothing in the reserve to pulse")
            return null
        }

        // Read once, and before the ping, so the probe and the ceiling it is judged
        // against are the same mode's.
        val iranMode = try {
            AutoModeSourceManager.getStore().iranMode
        } catch (e: Exception) {
            LogUtil.w(AppConfig.TAG, "AutoMode: could not read Iran mode for the pulse: ${e.message}")
            false
        }

        val delays = ping(context, guids, iranMode)
        val now = System.currentTimeMillis()

        // Entries that failed are removed rather than zeroed: an absent entry and an
        // expired one mean the same thing to every reader — "not known to be alive" — and
        // a tombstone would grow the map without adding anything to it.
        val ceiling = maxDelay(iranMode)
        val (aliveGuids, deadGuids) = guids.partition { (delays[it] ?: -1L) in 1..ceiling }

        AutoModeSourceManager.recordLiveness(
            alive = aliveGuids.associateWith { now },
            dead = deadGuids,
            present = guids.toSet(),
            checkedAt = now,
        )

        LogUtil.i(AppConfig.TAG, "AutoMode: pulse — ${aliveGuids.size} of ${guids.size} still answering")
        return Result(checked = guids.size, alive = aliveGuids.size)
    }

    /**
     * How many of the reserve are known to be alive right now.
     *
     * Entries older than [ALIVE_TTL_MILLIS] do not count, and neither does an entry for a
     * server the reserve no longer holds. A clock moved backwards would otherwise make
     * every entry look like it was written in the future and never expire, so the age is
     * floored at zero the same way [AutoModeScheduler.reserveAgeMillis] does it.
     */
    fun aliveCount(store: AutoModeStore, reserve: Set<String>): Int {
        val now = System.currentTimeMillis()
        return store.aliveByGuid.count { (guid, at) ->
            guid in reserve && (now - at).coerceAtLeast(0L) < ALIVE_TTL_MILLIS
        }
    }

    /** [aliveCount] against the reserve as it currently stands. */
    fun aliveCount(store: AutoModeStore): Int =
        aliveCount(store, MmkvManager.decodeServerList(AutoModeEngine.TOP_GROUP_ID).toSet())

    /**
     * Whether the reserve is healthy enough to leave alone.
     *
     * 🔴 The fraction is of what the reserve **holds**, not of what it was configured to
     * hold, and the difference is a bug rather than a nicety.
     *
     * `reserveCount` is a target. A run that only finds seven good servers leaves seven,
     * and that is a perfectly good reserve — the row-count low-water test above this one in
     * `isRefreshDue` is what decides whether it is too small to bother with. Measuring
     * liveness against the target instead meant a reserve could sit permanently between the
     * two bars: with a target of ten, a reserve of five rows passes the low-water test
     * (5 is not below 5) and can never reach the six alive that the target demanded. Every
     * scheduled refresh would then fire, find five again, and fire again six hours later,
     * for as long as the app was installed — a full run, indefinitely, on a reserve that
     * was working.
     *
     * Asking what fraction of the servers actually held are answering is the question that
     * was meant, and it composes correctly with the low-water test rather than fighting it.
     *
     * A reserve that has never been pulsed answers false — not because it is known to be
     * bad, but because it is not known to be good, and "unknown" is exactly the state the
     * old count-based test silently read as "fine".
     */
    fun isReserveHealthy(store: AutoModeStore, reserve: Set<String>): Boolean {
        if (store.reserveCheckedMillis <= 0) {
            return false
        }
        if (reserve.isEmpty()) {
            return false
        }
        val wanted = ceil(reserve.size * ALIVE_FRACTION).toInt()
        return aliveCount(store, reserve) >= wanted
    }

    /** [isReserveHealthy] against the reserve as it currently stands. */
    fun isReserveHealthy(store: AutoModeStore): Boolean =
        isReserveHealthy(store, MmkvManager.decodeServerList(AutoModeEngine.TOP_GROUP_ID).toSet())

    /** Whether opening the app should spend a pulse. */
    fun isPulseDue(store: AutoModeStore): Boolean {
        if (store.reserveCheckedMillis <= 0) {
            return true
        }
        val since = (System.currentTimeMillis() - store.reserveCheckedMillis).coerceAtLeast(0L)
        return since >= FOREGROUND_TTL_MILLIS
    }

    /**
     * The ping itself, over the existing worker.
     *
     * Deliberately the same worker the engine's stages use, rather than a second pinger
     * that would have to be kept correct alongside it. `onlyTcp = false` because a TCP
     * handshake proves the port is open and nothing else — a dead proxy behind a live CDN
     * edge answers it — and the whole value of this is that it asks the same question the
     * run asks.
     */
    private suspend fun ping(
        context: Context,
        guids: List<String>,
        iranMode: Boolean,
    ): Map<String, Long> =
        suspendCancellableCoroutine { cont ->
            val results = ConcurrentHashMap<String, Long>()
            val settled = AtomicBoolean(false)

            fun finish() {
                if (settled.compareAndSet(false, true) && cont.isActive) {
                    cont.resume(HashMap(results)) { _, _, _ -> }
                }
            }

            val worker = RealPingWorkerService(
                context = context,
                guids = guids,
                onlyTcp = false,
                concurrencyOverride = CONCURRENCY,
                delayTestUrl = if (iranMode) IranMode.probeUrl() else null,
                onEvent = { event ->
                    when (event) {
                        is RealPingEvent.Result -> {
                            results[event.guid] = event.delayMillis
                            MmkvManager.encodeServerTestDelayMillis(event.guid, event.delayMillis)
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
