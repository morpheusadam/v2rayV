package com.v2ray.ang.automode

import android.content.Context
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.v2ray.ang.AppConfig
import com.v2ray.ang.core.CoreConfigManager
import com.v2ray.ang.core.CoreNativeManager
import com.v2ray.ang.dto.IPAPIInfo
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

/**
 * Measures how much traffic a server actually carries.
 *
 * v2rayNG upstream only ever measures delay, because `Libv2ray.measureOutboundDelay`
 * does the whole thing inside the core with no inbound to speak to. Throughput needs a
 * real client, so this runs a throwaway core instance of its own with a loopback SOCKS
 * inbound, downloads through it, and shuts it down again — the same shape as the desktop
 * client's speed test, which points a `socks5://127.0.0.1:port` proxy at a large file.
 *
 * Tests are deliberately run one at a time by the caller: two downloads racing over one
 * radio measure the radio rather than the servers.
 *
 * The download itself lives in [ThroughputProbe], which also measures the user's own line.
 * The two results are compared as a ratio, so they must be the same measurement rather
 * than merely similar ones.
 */
object AutoModeSpeedTester {

    /** Give the core a moment to bind the inbound before dialling into it. */
    private const val CORE_START_TIMEOUT_MILLIS = 3_000L
    private const val CORE_POLL_INTERVAL_MILLIS = 100L

    private const val IP_INFO_TIMEOUT_MILLIS = 5_000

    private const val LOOPBACK = "127.0.0.1"

    /**
     * Runs one server through a download and an exit-country lookup.
     *
     * @return the measurement, with [AutoModeMeasurement.speedMbps] left at zero when the
     *         server could not carry the download at all.
     */
    fun measure(
        context: Context,
        guid: String,
        onSample: (Double) -> Unit = {},
    ): AutoModeMeasurement? {
        val profile = MmkvManager.decodeServerConfig(guid) ?: return null
        val measurement = AutoModeMeasurement(guid = guid, profile = profile)

        val port = Utils.findRandomFreePort()
        val config = buildConfigWithSocksInbound(context, guid, port) ?: return measurement

        var controller: CoreController? = null
        try {
            controller = CoreNativeManager.newCoreController(SilentCallback())
            controller.startLoop(config, 0)
            if (!awaitCoreRunning(controller)) {
                LogUtil.w(AppConfig.TAG, "AutoMode: test core did not come up for $guid")
                return measurement
            }

            val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress(LOOPBACK, port))
            // Shared with the baseline measurement on purpose — the two numbers are
            // divided by one another, so they have to be the same measurement.
            measurement.speedMbps = ThroughputProbe.measure(proxy, onSample)
            // Only worth a round trip when the tunnel proved it carries traffic.
            if (measurement.speedMbps > 0) {
                measurement.exitCountry = CountryHint.fromIpInfo(lookupIpInfo(proxy))
            }
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "AutoMode: speed test failed for $guid", e)
        } finally {
            try {
                controller?.stopLoop()
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "AutoMode: failed to stop test core", e)
            }
        }

        return measurement
    }

    /**
     * Takes the config the delay test would use — which has had its inbounds, routing,
     * DNS and stats stripped — and gives it back exactly one inbound to talk to. With no
     * routing rules left, the core sends everything to the first outbound, which is the
     * server under test.
     */
    private fun buildConfigWithSocksInbound(context: Context, guid: String, port: Int): String? {
        val result = CoreConfigManager.getV2rayConfig4Speedtest(context, guid)
        if (!result.status) {
            return null
        }

        val root = JsonUtil.parseString(result.content) ?: return null

        val settings = JsonObject().apply {
            addProperty("auth", "noauth")
            // The download is TCP only, and a UDP relay on a throwaway inbound is one
            // more thing that can fail for reasons unrelated to the server.
            addProperty("udp", false)
        }
        val inbound = JsonObject().apply {
            addProperty("tag", "socks-speedtest")
            addProperty("listen", LOOPBACK)
            addProperty("port", port)
            addProperty("protocol", "socks")
            add("settings", settings)
        }

        root.add("inbounds", JsonArray().apply { add(inbound) })
        return root.toString()
    }

    private fun awaitCoreRunning(controller: CoreController): Boolean {
        val deadline = System.currentTimeMillis() + CORE_START_TIMEOUT_MILLIS
        while (System.currentTimeMillis() < deadline) {
            if (controller.isRunning) {
                return true
            }
            try {
                Thread.sleep(CORE_POLL_INTERVAL_MILLIS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        return controller.isRunning
    }

    /**
     * Where the traffic actually comes out, asked through the tunnel itself. This is the
     * only country evidence worth ranking on — a remark saying "Netherlands" is a claim,
     * this is a measurement.
     */
    private fun lookupIpInfo(proxy: Proxy): String? {
        val url = MmkvManager.decodeSettingsString(AppConfig.PREF_IP_API_URL)
            ?.takeIf { it.isNotBlank() } ?: AppConfig.IP_API_URL

        val client = OkHttpClient.Builder()
            .proxy(proxy)
            .connectTimeout(IP_INFO_TIMEOUT_MILLIS.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(IP_INFO_TIMEOUT_MILLIS.toLong(), TimeUnit.MILLISECONDS)
            .callTimeout(IP_INFO_TIMEOUT_MILLIS.toLong() * 2, TimeUnit.MILLISECONDS)
            .build()

        return try {
            client.newCall(Request.Builder().url(url).get().header("Connection", "close").build())
                .execute().use { response ->
                    if (!response.isSuccessful) {
                        return null
                    }
                    val content = response.body.string()
                    val info = JsonUtil.fromJsonSafe(content, IPAPIInfo::class.java) ?: return null

                    val country = listOf(
                        info.country_code,
                        info.country,
                        info.countryCode,
                        info.location?.country_code
                    ).firstOrNull { !it.isNullOrBlank() } ?: return null

                    "($country)"
                }
        } catch (e: Exception) {
            LogUtil.d(AppConfig.TAG, "AutoMode: ip info lookup failed: ${e.message}")
            null
        }
    }

    /**
     * The test core reports to nobody: a throwaway instance must never be able to stop
     * the user's actual VPN service the way the shared controller's callback does.
     */
    private class SilentCallback : CoreCallbackHandler {
        override fun startup(): Long = 0
        override fun shutdown(): Long = 0
        override fun onEmitStatus(l: Long, s: String?): Long = 0
    }
}
