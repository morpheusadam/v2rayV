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

    /**
     * The list every run imports from regardless of what the user has added, and the proxy
     * list used to reach it when the network blocks the host. Blank means the built-in
     * default; they are stored so a user on a network that blocks even the mirrors can
     * point the app somewhere else without a new build.
     */
    var subsUrl: String = "",
    var proxiesUrl: String = "",

    /** Last proxy known to reach the subscription host, as "PROTOCOL|host|port". */
    var lastProxy: String? = null,
    var lastProxyMillis: Long = 0,

    /**
     * Which rung of the route ladder answered last — an index into
     * [AutoModeNetwork.mirrorsFor]'s list, with 0 meaning the host itself.
     *
     * The proxy has always been remembered; the route was not, so a network that blocks the
     * host paid the whole ladder again on every fetch of every run. The rung generalises
     * across URLs because every mirror is derived from the same template, so knowing that
     * jsdelivr worked for one file is a good prior for the next.
     */
    var lastRouteIndex: Int = 0,
    var lastRouteMillis: Long = 0,

    /**
     * The user's own line speed in MB/s, measured with the same single-stream method the
     * servers are measured with so the two numbers can be compared at all.
     */
    var baselineMbps: Double = 0.0,
    var baselineMillis: Long = 0,

    /** Which network the baseline was taken on, so a move from wifi to mobile re-measures. */
    var baselineNetwork: String = "",

    /**
     * Fraction of the baseline at which a server is good enough to stop looking and
     * connect. The rest of the run keeps going to refill the reserve.
     */
    var acceptFraction: Double = 0.70,

    /** Servers kept ready so that every connection after the first is immediate. */
    var reserveCount: Int = 10,

    /**
     * Measured throughput per kept server, in MB/s, keyed by guid.
     *
     * The dashboard shows the speed of whichever server is selected, so this cannot be a
     * single "last result" — the user may pick any entry in the list. It is bounded by the
     * size of the reserve and pruned whenever the reserve is rewritten.
     */
    var speedByGuid: MutableMap<String, Double> = mutableMapOf(),

    /**
     * Exit country per kept server, ISO code, as measured through the tunnel rather than
     * read off the provider's remark. Pruned with the reserve.
     */
    var countryByGuid: MutableMap<String, String> = mutableMapOf(),

    /** Whether pressing power with nothing ready should run Auto Mode rather than refuse. */
    var autoRunOnConnect: Boolean = true,

    /**
     * Whether a connection that stops carrying traffic should move itself to the next
     * server in the reserve. Off by default, and deliberately: it drops every open
     * connection when it fires, which is the right trade only for someone who has decided
     * it is. See [SmartSwitch].
     */
    var smartSwitch: Boolean = false,

    /**
     * Whether the mirror ladder may be walked at all when the list host cannot be reached.
     *
     * Off by default, and this is a privacy decision rather than a technical one. Fetching the
     * lists from GitHub tells GitHub that somebody wanted them. Fetching them from a mirror
     * tells whoever runs that mirror the same thing, and the mirrors are third parties the user
     * never chose — a CDN, or the author of this app. Where this software is most useful, "who
     * asked for a subscription list, and from which address" is not a harmless fact, and it is
     * not ours to disclose on the user's behalf because it made a fetch more likely to succeed.
     *
     * So the fallback exists, works, and stays switched off until somebody decides the trade is
     * worth it. With it off the app fetches only from the host the list actually lives on.
     */
    var mirrorsEnabled: Boolean = false,

    /**
     * Which mirror to use, as an index into [AutoModeNetwork.MIRRORS]. Out-of-range values fall
     * back to the first entry rather than disabling the feature, so a stored index left behind
     * by a build that offered more mirrors does not silently turn this off.
     */
    var mirrorIndex: Int = 0,
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

    /** The user's own line speed this run was judged against, in MB/s. Zero if unmeasured. */
    var baselineMbps: Double = 0.0,

    /** Throughput of the server that cleared the bar and was connected to, in MB/s. */
    var acceptedMbps: Double = 0.0,

    /**
     * Wall clock each stage actually spent, already formatted — "line 8.1s · fetch 7.6s · …".
     *
     * Every latency decision about this pipeline has so far been argued from the cost model
     * in `estimateSeconds`, which exists to drive a countdown and was never meant to be
     * evidence. This is the clock, and it is carried into the result so a screenshot from a
     * censored network says where the time went rather than only that it was slow.
     */
    var timings: String = "",

    var message: String = "",
)

/**
 * The stages of a run, in the order they happen.
 *
 * Reported as an enum rather than parsed out of the progress text, so the timeline on the
 * dashboard cannot drift out of step with the engine when a message is reworded.
 */
enum class AutoModeStage(
    /** Short name used in the timing line at the end of a run. */
    val label: String,
) {
    /** Establishing what this connection does on its own. */
    MEASURING("line"),

    /** Deciding whether the sources are reachable, and finding a proxy if not. */
    ROUTING("route"),

    /** Downloading the catalog and the source lists. */
    FETCHING("fetch"),

    /** Turning the downloads into candidate servers. */
    IMPORTING("import"),

    /** Dropping endpoints that answer nothing. */
    PROBING("probe"),

    /** Proving which candidates actually carry a request through the tunnel. */
    TUNNELING("tunnel"),

    /** Measuring throughput, one server at a time. */
    MEASURING_SERVERS("speed"),

    DONE("done"),
}

/**
 * What the button shows while a run is in flight. [remainingMillis] is projected from the
 * stage that is actually running rather than from a fixed guess, so it corrects itself on
 * a slow network instead of counting down to zero and sitting there.
 */
data class AutoModeProgress(
    val running: Boolean = false,
    val message: String = "",
    val remainingMillis: Long = 0,
    val stage: AutoModeStage = AutoModeStage.MEASURING,
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
