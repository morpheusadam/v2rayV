package com.v2ray.ang.automode

/**
 * Decides when the server you are connected to has stopped being worth staying on.
 *
 * A free server does not usually die cleanly. It gets oversubscribed, or its upstream gets
 * throttled, and what the user sees is a page that will not finish loading — while the
 * tunnel is still up, still handshaking, still reporting itself as connected. Nothing in
 * the app noticed that, because the only measurement of a server was taken once, at the
 * moment it was chosen.
 *
 * This watches the live traffic counters instead, and the whole difficulty is in one
 * distinction:
 *
 * **An idle server and a broken server look exactly the same.** Both report zero. A phone
 * in a pocket moves no bytes for hours, and switching servers under it would be pure
 * damage. So nothing here judges throughput on its own — a verdict is only ever reached
 * while the user is demonstrably asking for something, which the uplink counter shows:
 * requests go out even when nothing comes back.
 *
 * That gives two shapes of failure worth acting on, and one that is not:
 *
 *  - **Stalled** — requests leaving, almost nothing returning. The clearest signal there
 *    is, and the one a user actually experiences as "it's connected but nothing loads".
 *  - **Slow** — traffic flowing, but far under what this server measured when it was
 *    picked. Judged against its own measurement rather than an absolute figure, for the
 *    same reason the acceptance threshold is: a number that is poor on fibre is fine on a
 *    weak mobile signal.
 *  - **Quiet** — neither direction moving. Not a verdict. Never a verdict.
 *
 * Everything is deliberately slow to fire. A switch costs a reconnection, drops every open
 * connection the user has, and is very visible; being wrong is worse than being late.
 */
class SmartSwitch(
    /**
     * What the current server measured at when Auto Mode chose it, in MB/s. Zero when
     * unknown — a hand-picked server, or one whose measurement has been lost — in which
     * case only the stall test applies, because there is nothing to be slow relative to.
     */
    private val referenceMbps: Double,
    private val config: Config = Config(),
) {

    data class Config(
        /**
         * Uplink above which the user is taken to be asking for something, in bytes/sec.
         * A few hundred bytes is keepalives and DNS; a couple of kilobytes is requests.
         */
        val requestFloorBps: Long = 2_000,

        /** Downlink below which, while requesting, the tunnel counts as stalled. */
        val stallFloorBps: Long = 4_000,

        /** Fraction of [referenceMbps] under which sustained throughput counts as slow. */
        val slowFraction: Double = 0.25,

        /**
         * Consecutive bad seconds before a verdict. Stats tick once a second, so this is
         * how long a page has to be visibly failing to load.
         */
        val badSamplesToSwitch: Int = 8,

        /**
         * How long after a switch before another can be considered. A fresh tunnel has to
         * re-establish every connection the last one was carrying, and it looks terrible
         * for the first few seconds of that whatever the server is worth.
         */
        val cooldownMillis: Long = 45_000,

        /**
         * Switches allowed in an hour. Past this the problem is almost certainly the
         * network rather than the server, and reconnecting repeatedly only makes it worse.
         */
        val maxSwitchesPerHour: Int = 4,
    )

    sealed interface Verdict {
        /** Nothing to do — including when there is simply nothing happening. */
        data object Stay : Verdict

        /** Move on, and why, in words that can go straight into a toast. */
        data class Switch(val reason: String) : Verdict
    }

    private var badSamples = 0
    private var lastSwitchMillis = 0L
    private val switchTimes = ArrayDeque<Long>()

    /** MB/s under which this server is judged slow. Zero when it has no measurement. */
    private val slowFloorMbps: Double
        get() = if (referenceMbps <= 0) 0.0 else referenceMbps * config.slowFraction

    /**
     * Feeds one second of traffic counters and asks what to do.
     *
     * @param upBytesPerSec proxied uplink over the last tick.
     * @param downBytesPerSec proxied downlink over the last tick.
     * @param nowMillis wall clock, passed in rather than read so this stays testable.
     */
    fun onSample(upBytesPerSec: Long, downBytesPerSec: Long, nowMillis: Long): Verdict {
        if (!canSwitch(nowMillis)) {
            // Still counting would mean firing the instant the cooldown lapsed, on evidence
            // gathered while the tunnel was legitimately settling.
            badSamples = 0
            return Verdict.Stay
        }

        // The idle case, and the only thing standing between this feature and a phone that
        // reconnects all night in someone's pocket.
        if (upBytesPerSec < config.requestFloorBps) {
            badSamples = 0
            return Verdict.Stay
        }

        val stalled = downBytesPerSec < config.stallFloorBps
        val downMbps = downBytesPerSec / 1_048_576.0
        val slow = slowFloorMbps > 0 && downMbps < slowFloorMbps

        if (!stalled && !slow) {
            badSamples = 0
            return Verdict.Stay
        }

        badSamples++
        if (badSamples < config.badSamplesToSwitch) {
            return Verdict.Stay
        }

        badSamples = 0
        recordSwitch(nowMillis)

        return Verdict.Switch(
            if (stalled) "This server stopped responding — moving to the next one."
            else "This server slowed to a fraction of what it measured — moving to the next one."
        )
    }

    /** Called when the connection changes under us, so evidence about the old one is dropped. */
    fun reset(nowMillis: Long) {
        badSamples = 0
        lastSwitchMillis = nowMillis
    }

    private fun canSwitch(nowMillis: Long): Boolean {
        if (nowMillis - lastSwitchMillis < config.cooldownMillis) {
            return false
        }
        pruneOldSwitches(nowMillis)
        return switchTimes.size < config.maxSwitchesPerHour
    }

    private fun recordSwitch(nowMillis: Long) {
        lastSwitchMillis = nowMillis
        switchTimes.addLast(nowMillis)
        pruneOldSwitches(nowMillis)
    }

    private fun pruneOldSwitches(nowMillis: Long) {
        while (switchTimes.isNotEmpty() && nowMillis - switchTimes.first() > HOUR_MILLIS) {
            switchTimes.removeFirst()
        }
    }

    private companion object {
        const val HOUR_MILLIS = 60L * 60 * 1000
    }
}
