package com.v2ray.ang.automode

/**
 * Health record for a single subscription source used by Auto Mode.
 *
 * Free subscription links decay: they stop updating, then stop working entirely.
 * The alpha/beta pair is the Beta-distribution evidence a Thompson sampler draws from,
 * and both are decayed on every run so old evidence stops dominating.
 */
data class AutoModeSource(
    var url: String = "",

    /** Set false by the user, or automatically after too many dead runs. */
    var enabled: Boolean = true,

    /** True when Auto Mode disabled it, so a resurrection probe may re-enable it. */
    var autoDisabled: Boolean = false,

    /** Beta(alpha, beta) evidence. Both start at 1 = uniform prior. */
    var alpha: Double = 1.0,
    var beta: Double = 1.0,

    /** Times this source was selected for a run. */
    var tried: Int = 0,

    /** Configs from this source that passed the real-ping stage, all time. */
    var greenTotal: Int = 0,

    /** Configs from this source that reached the real-ping stage, all time. */
    var testedTotal: Int = 0,

    /** Configs from this source that made the final top list, all time. */
    var winnerTotal: Int = 0,

    var lastTriedMillis: Long = 0,
    var lastGreenMillis: Long = 0,

    /** Hash of the last fetched body, used to detect a source that stopped updating. */
    var lastHash: String? = null,

    var lastConfigCount: Int = 0,

    /** Consecutive fetches that returned byte-identical content. */
    var staleRuns: Int = 0,

    /** Consecutive runs that produced no working config (or failed to fetch). */
    var deadStreak: Int = 0,

    var bestSpeedMbps: Double = 0.0,
) {
    /** Posterior mean of the success rate — what the UI shows as "quality". */
    val score: Double get() = alpha / (alpha + beta)
}

/**
 * Persisted Auto Mode state: the source list plus the tuning that adapts between runs.
 * Stored as JSON in its own MMKV store so it survives upgrades and can be exported.
 */
data class AutoModeStore(
    var version: Int = 1,

    /** How many servers the final list keeps. */
    var topCount: Int = 10,

    /** How many sources to fetch per run. Adapts to how well recent runs went. */
    var sourcesPerRun: Int = 8,

    var runCount: Int = 0,

    var lastRunMillis: Long = 0,

    /**
     * Protocols worth testing, by [com.v2ray.ang.enums.EConfigType] name. Empty means any.
     */
    var protocolFilter: MutableList<String> = mutableListOf(),

    /**
     * Wanted countries as ISO codes. Empty means any. A remark's label decides what is
     * worth testing; the country measured through the tunnel decides what is kept.
     */
    var countryFilter: MutableList<String> = mutableListOf(),

    var sources: MutableList<AutoModeSource> = mutableListOf(),
)

/** Result of one Auto Mode run, for the summary message. */
data class AutoModeRunResult(
    var success: Boolean = false,
    var sourcesUsed: Int = 0,
    var fetched: Int = 0,
    var candidates: Int = 0,
    var tcpAlive: Int = 0,
    var realPingOk: Int = 0,
    var speedTested: Int = 0,
    var topCount: Int = 0,
    var message: String = "",
)

/**
 * What the button shows while a run is in flight. [remainingMillis] is projected from the
 * stage that is actually running rather than from a fixed guess, so it corrects itself on
 * a slow network instead of counting down to zero and sitting there.
 */
data class AutoModeProgress(
    val running: Boolean = false,
    val message: String = "",
    val remainingMillis: Long = 0,
)

/** One measured server, carried from the speed-test stage into winner selection. */
data class AutoModeMeasurement(
    val guid: String,
    val profile: com.v2ray.ang.dto.entities.ProfileItem,
    /** Throughput in MB/s. Zero means the download never got going. */
    var speedMbps: Double = 0.0,
    /** Real-ping delay in milliseconds, or -1. */
    var delayMillis: Long = -1,
    /** Exit country measured through the tunnel, ISO code, or null. */
    var exitCountry: String? = null,
)
