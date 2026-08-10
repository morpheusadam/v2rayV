package com.v2ray.ang.automode

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.telephony.TelephonyManager
import com.v2ray.ang.AppConfig
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * How fast this connection is, before any server is involved.
 *
 * Everything downstream is a ratio against this number: a server is worth connecting to
 * when it delivers [AutoModeStore.acceptFraction] of what the bare connection delivers.
 * Ranking servers against each other alone cannot say that — the fastest of ten bad
 * servers is still a bad server, and on a 20 MB/s line a 1 MB/s "winner" is a failure the
 * old pipeline would have reported as a success.
 *
 * Two things make measuring this possible at all while the tunnel is up:
 *
 *  - The VPN excludes this app from its own routing (`addDisallowedApplication` on the
 *    package itself, in every branch of the per-app configuration), so a socket opened
 *    here goes over the real network rather than back through the tunnel. The measurement
 *    therefore does not have to wait for a disconnection, and does not disturb one.
 *  - It is measured with [ThroughputProbe], the same single-stream method used on the
 *    servers, so the ratio compares like with like.
 */
object AutoModeBaseline {

    /** Re-measure after this long even on the same network. Lines change; so does load. */
    private const val TTL_MILLIS = 6L * 60 * 60 * 1000

    /**
     * Floor on a usable baseline, in MB/s. Below this the measurement is more likely to be
     * a stalled probe than a real line, and dividing by it would set an absurdly low bar
     * that the first dead server would clear.
     */
    private const val MIN_CREDIBLE_MBPS = 0.05

    /**
     * Fallback when the line cannot be measured at all — the connection is down, or the
     * probe host is blocked too. Servers are then judged on the old absolute terms rather
     * than against a number that was never taken.
     */
    const val UNKNOWN = 0.0

    /**
     * The current baseline, measured if the stored one is missing, stale, or was taken on
     * a different kind of network.
     *
     * @return MB/s, or [UNKNOWN] when it could not be measured.
     */
    suspend fun get(
        context: Context,
        force: Boolean = false,
        onProgress: (String) -> Unit = {},
        onSample: (Double) -> Unit = {},
    ): Double =
        withContext(Dispatchers.IO) {
            val store = AutoModeSourceManager.getStore()
            val network = networkKey(context)

            if (!force && isFresh(store, network)) {
                LogUtil.i(AppConfig.TAG, "AutoMode: reusing baseline ${store.baselineMbps} MB/s on $network")
                return@withContext store.baselineMbps
            }

            onProgress("Measuring your connection…")
            val measured = ThroughputProbe.measure(onSample = onSample)

            if (measured < MIN_CREDIBLE_MBPS) {
                LogUtil.w(AppConfig.TAG, "AutoMode: baseline probe returned $measured, treating as unknown")
                onProgress("Could not measure your connection — judging servers on their own terms.")
                return@withContext UNKNOWN
            }

            store.baselineMbps = measured
            store.baselineMillis = System.currentTimeMillis()
            store.baselineNetwork = network
            AutoModeSourceManager.save()

            onProgress("Your connection: ${format(measured)}.")
            measured
        }

    private fun isFresh(store: AutoModeStore, network: String): Boolean =
        store.baselineMbps >= MIN_CREDIBLE_MBPS
            && store.baselineNetwork == network
            && System.currentTimeMillis() - store.baselineMillis < TTL_MILLIS

    /** The throughput a server must reach to be good enough to stop looking and connect. */
    fun acceptThreshold(baselineMbps: Double, store: AutoModeStore): Double =
        if (baselineMbps <= UNKNOWN) 0.0 else baselineMbps * store.acceptFraction

    fun format(mbPerSecond: Double): String =
        if (mbPerSecond <= 0) "?" else String.format(java.util.Locale.US, "%.1f MB/s", mbPerSecond)

    /**
     * Identifies the connection closely enough to know when the baseline no longer applies.
     *
     * Deliberately coarse. The precise identity of a wifi network is its SSID, which needs
     * a location permission on modern Android — a permission prompt the user did not ask
     * for, to slightly improve a cache key. Transport type plus the mobile operator changes
     * on every move that actually matters (wifi to mobile, one carrier to another), and the
     * time-to-live catches the rest.
     */
    fun networkKey(context: Context): String {
        return try {
            val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return "unknown"
            val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return "offline"

            when {
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> {
                    val telephony = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
                    "cellular:${telephony?.networkOperatorName.orEmpty()}"
                }

                else -> "other"
            }
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "AutoMode: could not identify the network", e)
            "unknown"
        }
    }
}
