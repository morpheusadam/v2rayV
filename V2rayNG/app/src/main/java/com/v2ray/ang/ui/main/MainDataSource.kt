package com.v2ray.ang.ui.main

import com.v2ray.ang.dto.SubscriptionUpdateResult
import com.v2ray.ang.dto.TestServiceMessage
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.dto.entities.ServerAffiliationInfo
import com.v2ray.ang.dto.entities.SubscriptionCache
import com.v2ray.ang.dto.entities.SubscriptionItem
import kotlinx.coroutines.flow.Flow
import java.io.Closeable

/** One read of everything the dashboard shows about the selected server. */
data class DashboardServerInfo(
    /** MB/s this connection manages on its own. */
    val lineMbps: Double = 0.0,
    /** MB/s the selected server managed through the tunnel. */
    val vpnMbps: Double = 0.0,
    /** Exit country measured for it, ISO code. */
    val country: String? = null,
    /** 1-based position in the reserve, or 0 when it is not one of them. */
    val reservePosition: Int = 0,
    val reserveTotal: Int = 0,
    val remarks: String = "",
)

interface MainDataSource : Closeable {
    val mainServiceEvent: Flow<MainServiceEvent>

    fun getSelectedSubscriptionId(): String
    fun setSelectedSubscriptionId(id: String)

    fun getSelectServer(): String?
    fun setSelectServer(guid: String)

    fun getConfirmRemove(): Boolean
    fun getDoubleColumnDisplay(): Boolean
    fun isGroupAllDisplayEnabled(): Boolean

    fun getString(resId: Int): String
    fun getString(resId: Int, vararg formatArgs: Any): String

    fun getSubscriptions(): List<SubscriptionCache>
    fun getSubscriptionItem(id: String): SubscriptionItem?

    fun getServerGuidList(groupId: String): List<String>
    fun decodeServerConfig(guid: String): ProfileItem?
    fun decodeAffiliationInfo(guid: String): ServerAffiliationInfo?

    fun encodeServerList(guids: List<String>, groupId: String)

    fun removeServer(guid: String)
    fun removeAllServer(): Int
    fun removeInvalidServerByGuid(guid: String): Int
    fun removeInvalidServersInGroup(groupId: String): Int

    fun clearAllTestDelayResults(guids: List<String>)
    fun sortByTestResultsForSub(subId: String)
    fun getSubsList(): List<String>

    suspend fun importBatchConfig(
        server: String?,
        subscriptionId: String,
        updateUI: Boolean
    ): Pair<Int, Int>

    fun updateConfigViaSubAll(): SubscriptionUpdateResult
    fun updateConfigViaSub(subscriptionCache: SubscriptionCache): SubscriptionUpdateResult

    fun shareNonCustomConfigsToClipboard(guids: List<String>): Int
    fun share2QRCode(guid: String): android.graphics.Bitmap?
    fun share2Clipboard(guid: String): Boolean

    fun sendMsg2Service(msgId: Int, content: String)
    fun sendMsg2TestService(msg: TestServiceMessage)
    fun cancelAllPing()
    fun testCurrentServerRealPing()

    fun startAutoMode()
    fun cancelAutoMode()

    /**
     * True when a server is selected and usable right now, so pressing power can connect
     * instead of having to go and find one first.
     */
    fun hasReadyServer(): Boolean

    /** Starts the tunnel on [guid], selecting it first. Does nothing if one is already up. */
    fun startTunnel(guid: String)

    /**
     * Moves a *running* tunnel onto [guid].
     *
     * Distinct from [startTunnel] because starting is refused while the core is running: the
     * daemon answers "already running" and keeps serving the old config, so selecting a
     * different server and starting again changes the selection and nothing else. Switching
     * has to go through the service's restart message, which stops the core and brings it
     * back up on whatever is selected by then.
     */
    fun restartTunnel(guid: String)

    /** Keeps the periodic run that refills the reserve of ready servers scheduled. */
    fun scheduleAutoModeRefresh()

    /**
     * Everything the dashboard needs to know about the selected server, read in one pass.
     *
     * Gathered together because each part of it used to reload and re-parse the whole Auto
     * Mode store separately, and the reserve lookup decodes every profile in it — three
     * times the disk and JSON work, on whatever thread happened to ask.
     */
    fun dashboardServerInfo(guid: String?): DashboardServerInfo

    /** Opens a URL in whatever the user browses with. */
    fun openUri(url: String)

    /** Starts a system intent, such as the "install unknown apps" settings page. */
    fun startIntent(intent: android.content.Intent)

    /** Downloads an update and raises Android's install dialog. */
    suspend fun installUpdate(url: String): com.v2ray.ang.notice.NoticeInstaller.Result

    /** The server after [guid] in the reserve, or that the reserve is used up. */
    fun nextReserveServer(guid: String?): com.v2ray.ang.automode.AutoModeReserve.Next


    /**
     * Country and address the traffic is coming out of, asked through the running tunnel.
     * Blocking, and null when nothing is connected.
     */
    fun queryRemoteIpInfo(): String?

    fun syncSubscriptions()
    fun initAssets()
}
