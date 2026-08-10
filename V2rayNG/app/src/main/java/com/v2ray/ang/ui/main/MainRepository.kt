package com.v2ray.ang.ui.main

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.v2ray.ang.AngApplication
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.automode.AutoModeScheduler
import com.v2ray.ang.automode.AutoModeSourceManager
import com.v2ray.ang.dto.AutoModeMessage
import com.v2ray.ang.dto.AutoModeProgressMessage
import com.v2ray.ang.dto.AutoModeSpeedMessage
import com.v2ray.ang.dto.SubscriptionUpdateResult
import com.v2ray.ang.dto.TestServiceMessage
import com.v2ray.ang.dto.TrafficStatsMessage
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.dto.entities.ServerAffiliationInfo
import com.v2ray.ang.dto.entities.SubscriptionCache
import com.v2ray.ang.dto.entities.SubscriptionItem
import com.v2ray.ang.core.LauncherManager
import com.v2ray.ang.notice.NoticeInstaller
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.AppLocaleManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.handler.SpeedtestManager
import com.v2ray.ang.handler.SubscriptionUpdater
import com.v2ray.ang.helper.MessageHelper
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.atomic.AtomicBoolean

class MainRepository(
    private val app: AngApplication
) : MainDataSource {

    private val localizedContext: Context
        get() = AppLocaleManager.localizedContext(app)

    private val closed = AtomicBoolean(false)

    private val _mainServiceEvent = MutableSharedFlow<MainServiceEvent>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    override val mainServiceEvent: SharedFlow<MainServiceEvent> = _mainServiceEvent.asSharedFlow()

    private val serviceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val safeIntent = intent ?: return
            val event = when (safeIntent.getIntExtra("key", 0)) {
                AppConfig.MSG_STATE_RUNNING -> MainServiceEvent.StateRunning
                AppConfig.MSG_STATE_NOT_RUNNING -> MainServiceEvent.StateNotRunning
                AppConfig.MSG_STATE_START_SUCCESS -> MainServiceEvent.StateStartSuccess
                AppConfig.MSG_STATE_START_FAILURE -> MainServiceEvent.StateStartFailure(
                    safeIntent.getStringExtra("content").orEmpty()
                )

                AppConfig.MSG_STATE_STOP_SUCCESS -> MainServiceEvent.StateStopSuccess
                AppConfig.MSG_MEASURE_DELAY_SUCCESS -> MainServiceEvent.MeasureDelaySuccess(
                    safeIntent.getStringExtra("content").orEmpty()
                )

                AppConfig.MSG_MEASURE_CONFIG_SUCCESS -> MainServiceEvent.MeasureConfigSuccess
                AppConfig.MSG_MEASURE_CONFIG_NOTIFY -> MainServiceEvent.MeasureConfigNotify(
                    safeIntent.getStringExtra("content").orEmpty()
                )

                AppConfig.MSG_MEASURE_CONFIG_FINISH -> MainServiceEvent.MeasureConfigFinish(
                    safeIntent.getStringExtra("content")
                )

                AppConfig.MSG_AUTOMODE_PROGRESS -> {
                    val progress = JsonUtil.fromJsonSafe(
                        safeIntent.getStringExtra("content").orEmpty(),
                        AutoModeProgressMessage::class.java
                    )
                    progress?.let {
                        MainServiceEvent.AutoModeProgress(
                            it.running, it.message, it.remainingMillis, it.stage
                        )
                    }
                }

                AppConfig.MSG_AUTOMODE_FINISH -> MainServiceEvent.AutoModeFinish(
                    safeIntent.getStringExtra("content").orEmpty()
                )

                AppConfig.MSG_AUTOMODE_READY -> MainServiceEvent.AutoModeReady(
                    safeIntent.getStringExtra("content").orEmpty()
                )

                AppConfig.MSG_AUTOMODE_SPEED -> {
                    val sample = JsonUtil.fromJsonSafe(
                        safeIntent.getStringExtra("content").orEmpty(),
                        AutoModeSpeedMessage::class.java
                    )
                    sample?.let { MainServiceEvent.AutoModeSpeed(it.mbps, it.baseline) }
                }

                AppConfig.MSG_TRAFFIC_STATS -> {
                    val stats = JsonUtil.fromJsonSafe(
                        safeIntent.getStringExtra("content").orEmpty(),
                        TrafficStatsMessage::class.java
                    )
                    stats?.let {
                        MainServiceEvent.TrafficStats(
                            it.upSpeed, it.downSpeed, it.upTotal, it.downTotal, it.elapsedMillis
                        )
                    }
                }

                else -> null
            }
            event?.let { _mainServiceEvent.tryEmit(it) }
        }
    }

    init {
        ContextCompat.registerReceiver(
            app,
            serviceReceiver,
            IntentFilter(AppConfig.BROADCAST_ACTION_ACTIVITY),
            Utils.receiverFlags()
        )
        MessageHelper.sendMsg2Service(app, AppConfig.MSG_REGISTER_CLIENT, "")
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching {
            MessageHelper.sendMsg2Service(app, AppConfig.MSG_UNREGISTER_CLIENT, "")
        }.onFailure {
            LogUtil.e(AppConfig.TAG, "Failed to unregister service client", it)
        }
        runCatching {
            app.unregisterReceiver(serviceReceiver)
        }.onFailure {
            LogUtil.e(AppConfig.TAG, "Failed to unregister main service receiver", it)
        }
    }

    override fun getSelectedSubscriptionId(): String =
        MmkvManager.decodeSettingsString(AppConfig.CACHE_SUBSCRIPTION_ID, "").orEmpty()

    override fun setSelectedSubscriptionId(id: String) {
        MmkvManager.encodeSettings(AppConfig.CACHE_SUBSCRIPTION_ID, id)
    }

    override fun getSelectServer(): String? = MmkvManager.getSelectServer()

    override fun setSelectServer(guid: String) = MmkvManager.setSelectServer(guid)

    override fun getConfirmRemove(): Boolean =
        MmkvManager.decodeSettingsBool(AppConfig.PREF_CONFIRM_REMOVE, false)

    override fun getDoubleColumnDisplay(): Boolean =
        MmkvManager.decodeSettingsBool(AppConfig.PREF_DOUBLE_COLUMN_DISPLAY, false)

    override fun isGroupAllDisplayEnabled(): Boolean =
        MmkvManager.decodeSettingsBool(AppConfig.PREF_GROUP_ALL_DISPLAY)

    override fun getString(resId: Int): String = localizedContext.getString(resId)

    override fun getString(resId: Int, vararg formatArgs: Any): String =
        localizedContext.getString(resId, *formatArgs)

    override fun getSubscriptions(): List<SubscriptionCache> {
        val result = mutableListOf<SubscriptionCache>()
        if (isGroupAllDisplayEnabled()) {
            result += SubscriptionCache(
                guid = "",
                subscription = SubscriptionItem().apply {
                    remarks = localizedContext.getString(R.string.filter_config_all)
                }
            )
        }
        result += MmkvManager.decodeSubscriptions()
        return result
    }

    override fun getSubscriptionItem(id: String): SubscriptionItem? =
        MmkvManager.decodeSubscription(id)

    override fun getServerGuidList(groupId: String): List<String> =
        if (groupId.isEmpty()) {
            MmkvManager.decodeAllServerList()
        } else {
            MmkvManager.decodeServerList(groupId)
        }

    override fun decodeServerConfig(guid: String): ProfileItem? =
        MmkvManager.decodeServerConfig(guid)

    override fun decodeAffiliationInfo(guid: String): ServerAffiliationInfo? =
        MmkvManager.decodeServerAffiliationInfo(guid)

    override fun encodeServerList(guids: List<String>, groupId: String) =
        MmkvManager.encodeServerList(ArrayList(guids), groupId)

    override fun removeServer(guid: String) = MmkvManager.removeServer(guid)

    override fun removeAllServer(): Int = MmkvManager.removeAllServer()

    override fun removeInvalidServerByGuid(guid: String): Int =
        MmkvManager.removeInvalidServer(guid)

    override fun removeInvalidServersInGroup(groupId: String): Int =
        if (groupId.isEmpty()) {
            MmkvManager.removeInvalidServer("")
        } else {
            getServerGuidList(groupId).sumOf(::removeInvalidServerByGuid)
        }

    override fun clearAllTestDelayResults(guids: List<String>) =
        MmkvManager.clearAllTestDelayResults(guids)

    override fun sortByTestResultsForSub(subId: String) {
        AngConfigManager.sortByTestResultsForSub(subId)
    }

    override fun getSubsList(): List<String> = MmkvManager.decodeSubsList()

    override suspend fun importBatchConfig(
        server: String?,
        subscriptionId: String,
        updateUI: Boolean
    ): Pair<Int, Int> = AngConfigManager.importBatchConfig(server, subscriptionId, updateUI)

    override fun updateConfigViaSubAll(): SubscriptionUpdateResult =
        AngConfigManager.updateConfigViaSubAll()

    override fun updateConfigViaSub(subscriptionCache: SubscriptionCache): SubscriptionUpdateResult =
        AngConfigManager.updateConfigViaSub(subscriptionCache)

    override fun shareNonCustomConfigsToClipboard(guids: List<String>): Int =
        AngConfigManager.shareNonCustomConfigsToClipboard(app, guids)

    override fun share2QRCode(guid: String): android.graphics.Bitmap? =
        AngConfigManager.share2QRCode(guid)

    override fun share2Clipboard(guid: String): Boolean =
        AngConfigManager.share2Clipboard(app, guid) == 0

    override fun sendMsg2Service(msgId: Int, content: String) =
        MessageHelper.sendMsg2Service(app, msgId, content)

    override fun sendMsg2TestService(msg: TestServiceMessage) =
        MessageHelper.sendMsg2TestService(app, msg)

    override fun cancelAllPing() {
        sendMsg2TestService(
            TestServiceMessage(key = AppConfig.MSG_MEASURE_CONFIG_CANCEL)
        )
    }

    override fun testCurrentServerRealPing() {
        sendMsg2Service(AppConfig.MSG_MEASURE_DELAY, "")
    }

    override fun startAutoMode() {
        MessageHelper.sendMsg2AutoModeService(app, AutoModeMessage(AppConfig.MSG_AUTOMODE_START))
    }

    override fun cancelAutoMode() {
        MessageHelper.sendMsg2AutoModeService(app, AutoModeMessage(AppConfig.MSG_AUTOMODE_CANCEL))
    }

    /**
     * A selected server whose config still decodes. The config check matters because the
     * previous run deletes the entries that lost their slot, so a selection can outlive
     * the profile it points at.
     */
    override fun hasReadyServer(): Boolean {
        val guid = MmkvManager.getSelectServer()?.takeIf { it.isNotEmpty() } ?: return false
        return MmkvManager.decodeServerConfig(guid) != null
    }

    override fun startTunnel(guid: String) {
        LauncherManager.startService(app, guid)
    }

    override fun scheduleAutoModeRefresh() {
        AutoModeScheduler.schedule(app)
    }

    override fun openUri(url: String) {
        Utils.openUri(app, url)
    }

    override fun startIntent(intent: android.content.Intent) {
        try {
            // Started from application context rather than an Activity, so it needs its
            // own task; the notice's button may outlive the screen that raised it.
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            app.startActivity(intent)
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to start intent", e)
        }
    }

    override suspend fun installUpdate(url: String): NoticeInstaller.Result =
        NoticeInstaller.downloadAndInstall(app, url)

    override fun measuredSpeeds(guid: String?): Pair<Double, Double> {
        // reload() rather than the cached copy: a run writes its results in the core's
        // process, so the UI process's copy is stale the moment one finishes.
        val store = AutoModeSourceManager.reload()
        return store.baselineMbps to (guid?.let { store.speedByGuid[it] } ?: 0.0)
    }

    override fun queryRemoteIpInfo(): String? = SpeedtestManager.getRemoteIPInfo()

    override fun syncSubscriptions() {
        SubscriptionUpdater.sync(app)
    }

    override fun initAssets() {
        SettingsManager.initAssets(app, app.assets)
    }
}
