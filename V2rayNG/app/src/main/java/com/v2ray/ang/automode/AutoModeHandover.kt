package com.v2ray.ang.automode

import android.content.Context
import com.v2ray.ang.AppConfig
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.helper.MessageHelper
import com.v2ray.ang.util.LogUtil
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

/**
 * What happens to the choice of server when the line under the tunnel changes.
 *
 * Restarting the core on a new network — which is what the reload already did on its own — puts
 * back exactly the server that was running before, and asks nothing about it. That is the right
 * thing for the common case: most servers work from most networks, and a silent reload is
 * cheaper and far less visible than a search. It is the wrong thing for the case the user
 * actually notices, which is a server reachable from home wifi and blocked, throttled or simply
 * unrouteable from a mobile carrier. The tunnel comes back up, the notification says connected,
 * and nothing loads — because a working core is not the same claim as a working connection.
 *
 * So the reload is kept and a question is added after it: does traffic still come out the other
 * end? Only a server that fails to answer costs anything, and what it costs is a move to the next
 * server in the reserve, which is already ordered best-first and already tested.
 *
 * Deliberately not done here:
 *
 *  - No full Auto Mode run. A search downloads lists, starts a dozen throwaway cores and
 *    saturates the radio for minutes. Starting one from a background service the moment somebody
 *    walks out of their front door and their phone drops to mobile data would be worse than the
 *    problem. The reserve is what the reserve is for; when it is empty this says so and stops.
 *  - No re-ranking on speed. The reserve was ordered by throughput measured on another line and
 *    that ordering is now a guess, but re-measuring is the expensive thing being avoided. The
 *    stale baseline is dropped instead, so the next run — background or manual — re-measures
 *    rather than judging the new line by the old line's numbers.
 */
object AutoModeHandover {

    /**
     * How long the probe may take end to end. Generous, because it is competing with a radio
     * that has just changed state and a core that has just restarted, and a false negative here
     * costs the user a server switch they did not need.
     */
    private const val PROBE_TIMEOUT_MILLIS = 8_000L

    /**
     * Attempts before the server is called dead. A core that has been running for one second is
     * not the same as a core that has settled: the first request through a fresh Reality or
     * Hysteria handshake can lose to a radio that is still attaching, and calling that a dead
     * server would switch on every handover.
     */
    private const val PROBE_ATTEMPTS = 3

    /** Between attempts. Long enough for the radio to finish attaching, short enough to not be felt. */
    private const val PROBE_RETRY_DELAY_MILLIS = 2_000L

    /**
     * Called after the core has been reloaded onto the new network.
     *
     * Blocking, and expects to be called from the reload's own background thread — it is doing
     * network I/O and there is nothing useful to do concurrently with it.
     *
     * @return true when the tunnel carries traffic on the new line, false when it does not,
     *   whether or not a replacement server was available.
     */
    fun onNetworkChanged(context: Context, reason: String): Boolean {
        LogUtil.i(AppConfig.TAG, "AutoMode: handover ($reason), rechecking the tunnel")

        // Whatever the line measured before, it was a different line.
        try {
            AutoModeBaseline.invalidate()
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "AutoMode: could not drop the baseline", e)
        }

        // Samples taken through the old network say nothing about this one, and a switch decided
        // on the strength of them would fire on the dead seconds of the handover itself.
        SmartSwitchController.reset()

        if (isTunnelAlive()) {
            LogUtil.i(AppConfig.TAG, "AutoMode: server still carries traffic after the handover")
            return true
        }

        val guid = MmkvManager.getSelectServer()
        LogUtil.w(AppConfig.TAG, "AutoMode: no traffic after the handover, current server is not usable here")
        advance(context, guid)
        return false
    }

    /**
     * Whether anything at all comes back through the running core.
     *
     * Asked through the core's own SOCKS inbound rather than by opening an ordinary socket. This
     * app is excluded from its own VPN routing — `addDisallowedApplication` on the package, in
     * every branch of the per-app configuration — so an ordinary socket from here goes over the
     * bare network and would answer a question nobody asked. Pointing it at 127.0.0.1 forces the
     * request through the tunnel, which is the thing under suspicion.
     */
    private fun isTunnelAlive(): Boolean {
        val port = try {
            SettingsManager.getSocksPort()
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "AutoMode: could not read the socks port", e)
            return true // Unknown is not the same as dead; do not switch on a missing setting.
        }

        val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress(AppConfig.LOOPBACK, port))
        val client = OkHttpClient.Builder()
            .proxy(proxy)
            .connectTimeout(PROBE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
            .readTimeout(PROBE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
            .callTimeout(PROBE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
            .build()

        // Two hosts, because one of them being down or blocked is not the server's fault.
        val urls = listOf(AppConfig.DELAY_TEST_URL, AppConfig.DELAY_TEST_URL2)

        repeat(PROBE_ATTEMPTS) { attempt ->
            if (attempt > 0) {
                try {
                    Thread.sleep(PROBE_RETRY_DELAY_MILLIS)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return true // Being torn down; not a verdict on the server.
                }
            }
            if (urls.any { probe(client, it) }) {
                return true
            }
            LogUtil.i(AppConfig.TAG, "AutoMode: tunnel probe ${attempt + 1}/$PROBE_ATTEMPTS found nothing")
        }
        return false
    }

    private fun probe(client: OkHttpClient, url: String): Boolean = try {
        client.newCall(Request.Builder().url(url).get().header("Connection", "close").build())
            .execute().use { it.isSuccessful || it.code == 204 }
    } catch (e: Exception) {
        LogUtil.d(AppConfig.TAG, "AutoMode: tunnel probe to $url failed: ${e.message}")
        false
    }

    /**
     * Moves to the next server in the reserve and asks the service to restart onto it — the same
     * path SmartSwitch and the notification's restart button already take.
     */
    private fun advance(context: Context, currentGuid: String?) {
        when (val next = AutoModeReserve.next(currentGuid)) {
            is AutoModeReserve.Next.Server -> {
                LogUtil.i(AppConfig.TAG, "AutoMode: handover moving to ${next.position}/${next.total}")
                MmkvManager.setSelectServer(next.guid)
                MessageHelper.sendMsg2UI(context, AppConfig.MSG_SMART_SWITCH, HANDOVER_REASON)
                MessageHelper.sendMsg2Service(context, AppConfig.MSG_STATE_RESTART, "")
            }

            AutoModeReserve.Next.Exhausted -> {
                // Nothing tested is left. Saying so is useful; starting a search unprompted from
                // a background service is the expensive, visible thing this class avoids.
                LogUtil.i(AppConfig.TAG, "AutoMode: reserve exhausted after the handover, staying put")
            }
        }
    }

    /** Shown to the user when a network change is what moved them. */
    private const val HANDOVER_REASON = "Your network changed and this server stopped responding"
}
