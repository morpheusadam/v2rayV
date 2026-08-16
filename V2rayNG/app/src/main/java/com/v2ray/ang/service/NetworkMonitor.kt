package com.v2ray.ang.service

import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.v2ray.ang.AppConfig
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Watches the network that carries the tunnel and reports topology changes.
 *
 * Cellular -> Wi-Fi is a make-before-break handover: the new network is announced while the old one
 * is still connected, so the socket to the server is never reset and the core keeps using a dead
 * connection. Deciding that a handover happened is what this class is for, acting on it is not.
 *
 * A change of [Network] is not the only way the line under the tunnel can move. The same Network
 * object survives a DHCP lease change, a carrier re-assigning the mobile address, a roam between
 * access points on one SSID, and coming back from a tunnel-level VPN reset — and in every one of
 * those the outbound sockets are dead while nothing has been announced as lost. What the core
 * cares about is the address it is sending from, so the addresses are what is tracked here, and
 * [onHandover] fires on the identity of the line changing rather than on the identity of the
 * Network object.
 *
 * Only used from Android P and above, see CoreServiceManager.startNetworkMonitor().
 * [onHandover] is invoked on a background thread after the debounce window and may block. Its
 * argument says what moved, for the log only.
 */
class NetworkMonitor(
    private val connectivity: ConnectivityManager,
    private val onUnderlyingNetworksChanged: (Array<Network>?) -> Unit,
    private val onHandover: (String) -> Unit,
) {
    private var upstream: Network? = null

    /** Addresses last seen on [upstream]; see [addressesOf] and [hasLostAddress]. */
    private var upstreamAddresses: Set<String> = emptySet()

    private var handoverJob: Job? = null
    private var registered = false

    /**
     * Unfortunately registerDefaultNetworkCallback is going to return our VPN interface:
     * https://android.googlesource.com/platform/frameworks/base/+/dda156ab0c5d66ad82bdcf76cda07cbc0a9c8a2e
     *
     * This makes doing a requestNetwork with REQUEST necessary so that we don't get ALL possible networks that
     * satisfies default network capabilities but only THE default network. Unfortunately we need to have
     * android.permission.CHANGE_NETWORK_STATE to be able to call requestNetwork.
     *
     * Source: https://android.googlesource.com/platform/frameworks/base/+/2df4c7d/services/core/java/com/android/server/ConnectivityService.java#887
     */
    private val request by lazy {
        NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
            .build()
    }

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val previous = upstream
            upstream = network
            // Left empty on purpose rather than read from the network here. A network that has
            // just become available has usually not finished being assigned its addresses, and
            // recording that half-built state as the baseline turns the rest of the assignment
            // into a change. The first onLinkPropertiesChanged establishes the baseline instead.
            upstreamAddresses = emptySet()
            onUnderlyingNetworksChanged(arrayOf(network))
            if (previous != null && previous != network) {
                scheduleHandover(network, "moved to another network")
            }
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            // it's a good idea to refresh capabilities
            onUnderlyingNetworksChanged(arrayOf(network))
        }

        /**
         * Where an address change surfaces. Fires on the network we are already on, so it cannot
         * be told from noise by the Network object alone — only by comparing what it carries.
         */
        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            if (network != upstream) return

            val current = addressesOf(linkProperties)
            val previous = upstreamAddresses
            upstreamAddresses = current

            if (!hasLostAddress(previous, current)) return
            scheduleHandover(network, "address changed to ${current.sorted().joinToString(",")}")
        }

        override fun onLost(network: Network) {
            if (network == upstream) {
                // Whatever comes next has to prove itself; the old addresses must not be
                // mistaken for the new ones and suppress the handover.
                upstreamAddresses = emptySet()
            }
            onUnderlyingNetworksChanged(null)
        }
    }

    /**
     * The addresses the tunnel would send from.
     *
     * Only the addresses are taken. Routes and DNS servers change constantly for reasons that do
     * not invalidate a single outbound socket, and including them would turn this into a generator
     * of spurious reloads.
     */
    private fun addressesOf(linkProperties: LinkProperties?): Set<String> =
        linkProperties?.linkAddresses
            ?.mapNotNull { it.address.hostAddress?.takeIf(String::isNotEmpty) }
            ?.toSet()
            .orEmpty()

    companion object {
        private const val HANDOVER_DEBOUNCE_MS = 1000L

        /**
         * Whether the set of addresses changed in a way that invalidates the sockets the core
         * already has open.
         *
         * The test is that something the network *had* is gone, not that the two sets differ.
         * Addresses arrive over several callbacks — a link-local one first, then IPv4 from DHCP,
         * then a global IPv6 from router advertisement — and every one of those arrivals makes the
         * set differ from the last without anything having moved. Treating an addition as a
         * handover would restart the core two or three times in the seconds after every connect,
         * which is a worse bug than the one this is here to fix.
         *
         * A disappearance is the opposite: an address the core may have bound an outbound socket to
         * is no longer on the interface. A lease that changed, a carrier that re-assigned the
         * mobile address, a roam between access points — all of them drop the old address, and all
         * of them leave the core sending from somewhere that no longer exists.
         *
         * An empty [previous] is a baseline that has not been established yet, never a loss.
         */
        internal fun hasLostAddress(previous: Set<String>, current: Set<String>): Boolean =
            previous.isNotEmpty() && previous.any { it !in current }
    }

    /**
     * Starts watching. Safe to call more than once, only the first call registers.
     */
    fun register() {
        if (registered) return
        try {
            connectivity.requestNetwork(request, callback)
            registered = true
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "NetworkMonitor: Failed to request network", e)
        }
    }

    /**
     * Stops watching and drops the tracked state. Safe to call more than once.
     */
    fun unregister() {
        handoverJob?.cancel()
        handoverJob = null
        upstream = null
        upstreamAddresses = emptySet()
        if (!registered) return
        registered = false
        try {
            connectivity.unregisterNetworkCallback(callback)
        } catch (e: Exception) {
            LogUtil.w(AppConfig.TAG, "NetworkMonitor: Failed to unregister callback", e)
        }
    }

    private fun scheduleHandover(network: Network, reason: String) {
        LogUtil.i(AppConfig.TAG, "NetworkMonitor: Upstream is now $network ($reason)")
        handoverJob?.cancel()
        handoverJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                delay(HANDOVER_DEBOUNCE_MS)
                onHandover(reason)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "NetworkMonitor: Failed to handle upstream change", e)
            }
        }
    }
}
