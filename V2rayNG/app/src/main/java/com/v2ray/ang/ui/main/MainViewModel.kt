package com.v2ray.ang.ui.main

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.automode.AutoModeProgress
import com.v2ray.ang.automode.AutoModeReserve
import com.v2ray.ang.automode.AutoModeSourceManager
import com.v2ray.ang.automode.AutoModeStage
import com.v2ray.ang.automode.CountryHint
import com.v2ray.ang.automode.SmartSwitch
import com.v2ray.ang.notice.NoticeInstaller
import com.v2ray.ang.notice.NoticeManager
import com.v2ray.ang.dto.GroupMapItem
import com.v2ray.ang.dto.LocateTarget
import com.v2ray.ang.dto.TestServiceMessage
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.dto.entities.ServersCache
import com.v2ray.ang.dto.entities.SubscriptionCache
import com.v2ray.ang.extension.isComplexType
import com.v2ray.ang.extension.matchesPattern
import com.v2ray.ang.extension.moveItem
import com.v2ray.ang.ui.base.BaseViewModel
import com.v2ray.ang.ui.dashboard.DashboardState
import com.v2ray.ang.ui.dashboard.SAMPLE_WINDOW
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.PatternSyntaxException

class MainViewModel(
    application: Application,
    private val dataSource: MainDataSource
) : BaseViewModel(application) {

    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
    private val defaultDispatcher: CoroutineDispatcher = Dispatchers.Default
    private val preloadDispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1)

    private val disconnectedText: String = dataSource.getString(R.string.connection_not_connected)
    private val connectedText: String = dataSource.getString(R.string.connection_connected)

    // ---------- UI state ----------
    private val _uiState = MutableStateFlow(
        MainUiState(
            selectedGroupId = dataSource.getSelectedSubscriptionId(),
            selectedGuid = dataSource.getSelectServer(),
            statusText = disconnectedText,
            confirmRemove = dataSource.getConfirmRemove(),
            doubleColumnDisplay = dataSource.getDoubleColumnDisplay()
        )
    )
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    /** Tries at the exit-location lookup before giving up for this session. */
    private val IP_INFO_ATTEMPTS = 3

    // ---------- Keyword filtering ----------
    @Volatile
    private var keywordFilter: String = ""
    private var filterJob: Job? = null

    // ---------- Groups & cache ----------
    private val cacheMutex = Mutex()
    private val groupDataCache = mutableMapOf<String, List<ServersCache>>()
    private val groupPageFlows = ConcurrentHashMap<String, MutableStateFlow<List<ServersCache>>>()
    private val groupLoadMutexes = ConcurrentHashMap<String, Mutex>()
    private val serverOrderPersistenceJobs = mutableMapOf<String, Job>()

    private var setupGroupJob: Job? = null
    private var preloadJob: Job? = null
    private var ipInfoJob: Job? = null
    private var selectedGroupLoadJob: Job? = null
    private var reloadJob: Job? = null

    @Volatile
    private var testingGroupId: String? = null

    /**
     * Set when a run was started by the power button rather than by the Auto Mode button,
     * so the tunnel is brought up on the first acceptable server the run reports. A run
     * the user started deliberately leaves this false and only refreshes the list.
     */
    @Volatile
    private var pendingAutoConnect: Boolean = false

    /**
     * The unmeasured server this run connected on to end the wait, while the tunnel is still
     * running it. Null for a connection the user made themselves, which a background refresh
     * must never tear down.
     *
     * The guid is held rather than a flag so the later measured winner can be compared
     * against it directly. Comparing against the selected guid instead would race: the run
     * writes its choice to the shared store before announcing it, so by the time the message
     * arrives the selection can already be the new one.
     */
    @Volatile
    private var provisionalGuid: String? = null

    private val initialPageReady = CompletableDeferred<Unit>()

    // ---------- Service events ----------
    init {
        collectServiceEvents()
        setupGroupTab()
    }

    private fun collectServiceEvents() {
        viewModelScope.launch {
            dataSource.mainServiceEvent.collect { event ->
                handleServiceEvent(event)
            }
        }
    }

    private fun handleServiceEvent(event: MainServiceEvent) {
        when (event) {
            MainServiceEvent.StateRunning -> updateRunningState(true, clearTestingText = false)
            MainServiceEvent.StateNotRunning -> updateRunningState(false, clearTestingText = false)
            MainServiceEvent.StateStartSuccess -> {
                toastSuccess(R.string.toast_services_success)
                updateRunningState(true)
            }

            is MainServiceEvent.StateStartFailure -> {
                val error = event.errorMessage
                if (error.isNotBlank()) {
                    toastError(error)
                } else {
                    toastError(R.string.toast_services_failure)
                }
                updateRunningState(false)
            }

            MainServiceEvent.StateStopSuccess -> {
                // The tunnel this run put up is gone, so there is nothing left for a
                // measured server to replace.
                provisionalGuid = null
                updateRunningState(false)
            }
            is MainServiceEvent.MeasureDelaySuccess -> {
                _uiState.update { it.copy(statusText = event.content) }
            }

            MainServiceEvent.MeasureConfigSuccess -> {
                viewModelScope.launch(ioDispatcher) {
                    val gid = testingGroupId ?: uiState.value.selectedGroupId
                    cacheMutex.withLock { groupDataCache.remove(gid) }
                    updateGroupUi(gid, loadGroup(gid, forceRefresh = true))
                }
            }

            is MainServiceEvent.MeasureConfigNotify -> {
                _uiState.update {
                    it.copy(
                        statusText = dataSource.getString(
                            R.string.connection_running_task_left,
                            event.progress
                        )
                    )
                }
            }

            is MainServiceEvent.MeasureConfigFinish -> {
                onTestsFinished()
            }

            is MainServiceEvent.AutoModeProgress -> {
                _uiState.update {
                    it.copy(
                        autoMode = AutoModeProgress(
                            running = event.running,
                            message = event.message,
                            remainingMillis = event.remainingMillis,
                            stage = runCatching { AutoModeStage.valueOf(event.stage) }
                                .getOrDefault(AutoModeStage.MEASURING),
                        ),
                        // The live meter belongs to a run; when the run ends it has nothing
                        // to show and must not be left holding the last sample.
                        //
                        // `connecting` finally means something. It was dead state — nothing
                        // in the app ever set it true — and the dashboard had three
                        // expressions reading it. It now carries the one distinction the
                        // screen could not otherwise make: a run started by the power
                        // button ends in a connection, and a run started from the Auto Mode
                        // card or by the background schedule does not. Both look identical
                        // from `running` alone, and saying "Connecting" for the second is a
                        // plain false statement to someone who only wanted a refresh.
                        dashboard = it.dashboard.copy(
                            testing = event.running,
                            connecting = event.running && pendingAutoConnect,
                        ),
                    )
                }
            }

            is MainServiceEvent.AutoModeSpeed -> {
                _uiState.update { state ->
                    val dash = state.dashboard
                    state.copy(
                        // Live samples drive the needle only. The two figures on the cards
                        // are averages over a whole measurement, which a stream of
                        // instantaneous rates cannot be reduced to here — they are read
                        // back from the store once the measurement has finished.
                        dashboard = dash.copy(testing = true, testingMbps = event.mbps)
                    )
                }
            }

            is MainServiceEvent.SmartSwitched -> {
                // The service has already changed servers and restarted onto the new one;
                // this only tells the user why the connection blinked, and re-reads which
                // server is now selected so the dashboard is not naming the old one.
                if (event.reason.isNotBlank()) {
                    toast(event.reason)
                }
                viewModelScope.launch(ioDispatcher) { refreshSelectedGuid() }
            }

            is MainServiceEvent.TrafficStats -> {
                _uiState.update { state ->
                    val dash = state.dashboard
                    state.copy(
                        dashboard = dash.copy(
                            downSpeed = event.downSpeed,
                            upSpeed = event.upSpeed,
                            downTotal = event.downTotal,
                            upTotal = event.upTotal,
                            elapsedMillis = event.elapsedMillis,
                            downSamples = (dash.downSamples + event.downSpeed).takeLast(SAMPLE_WINDOW),
                            upSamples = (dash.upSamples + event.upSpeed).takeLast(SAMPLE_WINDOW),
                        )
                    )
                }
            }

            is MainServiceEvent.AutoModeReady -> {
                // Only when this run was started by the power button. A run the user
                // started deliberately must not connect the tunnel behind their back.
                if (pendingAutoConnect && !uiState.value.isRunning) {
                    pendingAutoConnect = false
                    provisionalGuid = event.guid
                    dataSource.startTunnel(event.guid)
                }
                refreshSelectedGuid()
            }

            is MainServiceEvent.AutoModeUpgrade -> {
                // The run connected on the first server that worked so the user did not sit
                // through the measuring. This is the measured replacement for it.
                //
                // Two conditions, both load-bearing. The tunnel has to be one this run put
                // up, because the same run refreshes the reserve underneath connections the
                // user made themselves and those must not be torn down. And it has to be a
                // different server: the provisional pick goes into the speed test like any
                // other, so the ordinary outcome is that it turns out fine and wins its own
                // slot, and restarting then would drop every open connection to install the
                // server already running.
                val running = provisionalGuid
                provisionalGuid = null

                if (running != null && running != event.guid && uiState.value.isRunning) {
                    dataSource.restartTunnel(event.guid)
                }
                refreshSelectedGuid()
            }

            is MainServiceEvent.AutoModeFinish -> {
                pendingAutoConnect = false
                // Scoped to the run that made the provisional connection. Left set, it would
                // let some later refresh restart a tunnel it had nothing to do with.
                provisionalGuid = null
                _uiState.update {
                    it.copy(
                        autoMode = AutoModeProgress(),
                        dashboard = it.dashboard.copy(testing = false, testingMbps = 0.0),
                    )
                }
                refreshMeasuredSpeeds()
                if (event.message.isNotBlank()) {
                    toast(event.message)
                }
                // A run rewrites the Auto Mode group wholesale, and the group itself may
                // not have existed before this run, so both the tabs and the list behind
                // them have to be rebuilt rather than refreshed in place.
                setupGroupTab(forceRefresh = true)
            }
        }
    }

    // ---------- Public state accessors ----------
    fun serversForGroup(groupId: String): StateFlow<List<ServersCache>> =
        groupPageFlows.computeIfAbsent(groupId) { MutableStateFlow(emptyList()) }
            .asStateFlow()

    private fun mutableServersForGroup(groupId: String): MutableStateFlow<List<ServersCache>> =
        groupPageFlows.computeIfAbsent(groupId) { MutableStateFlow(emptyList()) }

    private fun currentServers(): List<ServersCache> =
        mutableServersForGroup(uiState.value.selectedGroupId).value

    // ---------- Action handler ----------
    fun onAction(action: MainAction) {
        when (action) {
            // Needs an Activity to start the chooser from, so MainActivity intercepts it
            // before this ever sees it. Present only because the when is exhaustive.
            MainAction.ShareApp -> Unit
            // Also needs an Activity; MainActivity intercepts it before this sees it.
            MainAction.RequestKeepAlive -> Unit
            MainAction.Initialize -> initialize()
            MainAction.RefreshGroups -> setupGroupTab(forceRefresh = true)
            MainAction.TestAllServers -> testAllRealPing(true)
            MainAction.TestRealAllServers -> testAllRealPing()
            MainAction.CancelTesting -> cancelAllPing()
            MainAction.RemoveAllServers -> removeAllServerAsync()
            MainAction.RemoveDuplicateServers -> removeDuplicateServerAsync()
            MainAction.RemoveInvalidServers -> removeInvalidServerAsync()
            MainAction.SortByTestResults -> sortByTestResultsAsync()
            MainAction.UpdateSubscriptions -> importConfigViaSub()
            MainAction.ExportAll -> exportAllAsync()
            MainAction.ToggleAutoMode -> toggleAutoMode()
            is MainAction.SelectGroup -> subscriptionIdChanged(action.groupId)
            is MainAction.SelectServer -> updateSelectedGuid(action.guid)
            is MainAction.RemoveServer -> removeServerAndRefresh(action.guid)
            is MainAction.Search -> filterConfig(action.query)
            is MainAction.ImportBatchConfig -> importBatchConfig(action.configText)
            is MainAction.LocateHandled -> consumeLocateTarget(action.target)
            is MainAction.ShareQRCode -> {
                val bitmap = dataSource.share2QRCode(action.guid)
                _uiState.update { it.copy(shareQRCodeBitmap = bitmap) }
            }

            MainAction.DismissQRCodeDialog -> {
                _uiState.update { it.copy(shareQRCodeBitmap = null) }
            }

            MainAction.ToggleService,
            MainAction.TestCurrentServer,
            MainAction.ImportQRcode,
            MainAction.ImportClipboard,
            MainAction.ImportConfigLocal,
            is MainAction.ImportManually,
            MainAction.RestartService,
            MainAction.LocateSelectedServer,
            is MainAction.EditServer,
            is MainAction.ShareClipboard,
            is MainAction.ShareFullContent -> {
                // Handled by Activity via its onAction lambda
            }
        }
    }

    // ---------- Initialization ----------
    fun initialize() {
        viewModelScope.launch(preloadDispatcher) {
            try {
                initialPageReady.await()
                delay(32L)
                dataSource.initAssets()
                dataSource.syncSubscriptions()
                dataSource.scheduleAutoModeRefresh()
                refreshMeasuredSpeeds()
                refreshNotice()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                LogUtil.e(AppConfig.TAG, "Main background initialization failed", error)
            }
        }
    }

    // ---------- The notice slot ----------

    /**
     * Shows the cached notice immediately, then goes and looks for a newer one.
     *
     * Cache first because the slot must never be the reason the dashboard waits on the
     * network, and because a notice that flickers in a second after the screen settles is
     * more startling than one that was simply there.
     */
    private fun refreshNotice() {
        publishNotice()
        viewModelScope.launch(ioDispatcher) {
            try {
                NoticeManager.refresh()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                LogUtil.e(AppConfig.TAG, "Notice refresh failed", error)
            }
            publishNotice()
        }
    }

    private fun publishNotice() {
        val notice = NoticeManager.current()
        _uiState.update { it.copy(dashboard = it.dashboard.copy(notice = notice)) }
    }

    fun dismissNotice() {
        uiState.value.dashboard.notice?.let { NoticeManager.dismiss(it.id) }
        publishNotice()
    }

    /**
     * Runs the notice's button. Only two things can happen: a URL opens, or an update is
     * downloaded and handed to Android's installer.
     */
    fun onNoticeAction() {
        val action = uiState.value.dashboard.notice?.action ?: return
        if (!action.isUsable) {
            return
        }

        if (action.isOpenUrl) {
            dataSource.openUri(action.url)
            return
        }

        viewModelScope.launch(ioDispatcher) {
            when (val result = dataSource.installUpdate(action.url)) {
                is NoticeInstaller.Result.Launched -> Unit
                is NoticeInstaller.Result.NeedsPermission -> dataSource.startIntent(result.intent)
                is NoticeInstaller.Result.Failed -> toastError(result.reason)
            }
        }
    }

    fun refreshUiSettings() {
        _uiState.update {
            it.copy(
                confirmRemove = dataSource.getConfirmRemove(),
                doubleColumnDisplay = dataSource.getDoubleColumnDisplay()
            )
        }
    }

    // ---------- Group & server loading ----------
    private suspend fun buildServersCache(guids: List<String>): List<ServersCache> =
        guids.mapNotNull { guid ->
            currentCoroutineContext().ensureActive()
            val profile = dataSource.decodeServerConfig(guid) ?: return@mapNotNull null
            val affiliation = dataSource.decodeAffiliationInfo(guid)
            ServersCache(
                guid = guid,
                profile = profile.copy(),
                testDelayMillis = affiliation?.testDelayMillis ?: 0L,
                testDelayString = affiliation?.getTestDelayString().orEmpty()
            )
        }

    private suspend fun loadGroup(
        groupId: String,
        forceRefresh: Boolean = false
    ): List<ServersCache> {
        val loadMutex = groupLoadMutexes.computeIfAbsent(groupId) { Mutex() }
        return loadMutex.withLock {
            if (!forceRefresh) {
                cacheMutex.withLock { groupDataCache[groupId]?.let { return@withLock it } }
            }
            val servers = buildServersCache(dataSource.getServerGuidList(groupId))
            currentCoroutineContext().ensureActive()
            cacheMutex.withLock { groupDataCache[groupId] = servers }
            servers
        }
    }

    private fun applyKeywordFilter(servers: List<ServersCache>): List<ServersCache> {
        val keyword = keywordFilter.trim()
        if (keyword.isEmpty()) return servers
        val regex = try {
            Regex(keyword, RegexOption.IGNORE_CASE)
        } catch (_: PatternSyntaxException) {
            return servers
        }
        return servers.filter { cache ->
            val profile = cache.profile
            profile.remarks.matchesPattern(regex, keyword) ||
                    profile.description.orEmpty().matchesPattern(regex, keyword) ||
                    profile.server.orEmpty().matchesPattern(regex, keyword) ||
                    profile.configType.name.matchesPattern(regex, keyword)
        }
    }

    private fun updateGroupUi(groupId: String, servers: List<ServersCache>) {
        mutableServersForGroup(groupId).value = applyKeywordFilter(servers)
    }

    fun getSubscriptions(): List<SubscriptionCache> = dataSource.getSubscriptions()

    private fun resolveSelectedGroup(groups: List<GroupMapItem>): String {
        val current = uiState.value.selectedGroupId
        val resolved = when {
            groups.isEmpty() -> ""
            groups.any { it.id == current } -> current
            else -> groups.first().id
        }
        if (resolved != current) {
            dataSource.setSelectedSubscriptionId(resolved)
        }
        return resolved
    }

    private fun radialPreloadOrder(groups: List<GroupMapItem>, selectedIndex: Int): List<String> {
        if (groups.isEmpty()) return emptyList()
        val result = ArrayList<String>((groups.size - 1).coerceAtLeast(0))
        for (distance in 1 until groups.size) {
            val right = selectedIndex + distance
            val left = selectedIndex - distance
            if (right in groups.indices) result += groups[right].id
            if (left in groups.indices) result += groups[left].id
        }
        return result
    }

    fun setupGroupTab(forceRefresh: Boolean = false): Job {
        setupGroupJob?.cancel()
        preloadJob?.cancel()
        selectedGroupLoadJob?.cancel()

        return viewModelScope.launch(ioDispatcher) {
            try {
                if (forceRefresh) {
                    cacheMutex.withLock { groupDataCache.clear() }
                }
                val groups = dataSource.getSubscriptions().map {
                    GroupMapItem(id = it.guid, remarks = it.subscription.remarks)
                }
                val selectedGroup = resolveSelectedGroup(groups)
                val validIds = groups.mapTo(HashSet()) { it.id }
                groupPageFlows.keys.removeAll { it !in validIds }
                groupLoadMutexes.keys.removeAll { it !in validIds }

                _uiState.update {
                    it.copy(
                        groups = groups,
                        selectedGroupId = selectedGroup,
                        selectedGuid = dataSource.getSelectServer()
                    )
                }
                groups.forEach { mutableServersForGroup(it.id) }

                if (groups.isEmpty()) {
                    cacheMutex.withLock { groupDataCache.clear() }
                    return@launch
                }

                val selectedServers = loadGroup(selectedGroup, forceRefresh)
                updateGroupUi(selectedGroup, selectedServers)

                if (!initialPageReady.isCompleted) {
                    initialPageReady.complete(Unit)
                }

                val selectedIndex =
                    groups.indexOfFirst { it.id == selectedGroup }.coerceAtLeast(0)
                val preloadOrder = radialPreloadOrder(groups, selectedIndex)
                preloadJob = viewModelScope.launch(preloadDispatcher) {
                    preloadOrder.forEach { groupId ->
                        ensureActive()
                        delay(32L)
                        val servers = loadGroup(groupId, forceRefresh)
                        updateGroupUi(groupId, servers)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                LogUtil.e(AppConfig.TAG, "Failed to set up group tabs", error)
            } finally {
                if (!initialPageReady.isCompleted) {
                    initialPageReady.complete(Unit)
                }
            }
        }.also { setupGroupJob = it }
    }

    // ---------- Business actions (coroutine-based) ----------
    private fun importBatchConfig(configText: String) {
        launchLoading {
            withContext(ioDispatcher) {
                try {
                    val (count, countSub) = dataSource.importBatchConfig(
                        configText, uiState.value.selectedGroupId, true
                    )
                    when {
                        count > 0 -> {
                            toast(dataSource.getString(R.string.title_import_config_count, count))
                            setupGroupTab(forceRefresh = true)
                        }

                        countSub > 0 -> setupGroupTab(forceRefresh = true)
                        else -> toastError(R.string.toast_failure)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    LogUtil.e(AppConfig.TAG, "Failed to import batch config", e)
                    toastError(R.string.toast_failure)
                }
            }
        }
    }

    private fun importConfigViaSub() {
        val subId = uiState.value.selectedGroupId
        launchLoading {
            withContext(ioDispatcher) {
                try {
                    val result = if (subId.isEmpty()) {
                        dataSource.updateConfigViaSubAll()
                    } else {
                        val item = dataSource.getSubscriptionItem(subId) ?: return@withContext
                        dataSource.updateConfigViaSub(SubscriptionCache(subId, item))
                    }
                    when {
                        result.successCount + result.failureCount + result.skipCount == 0 ->
                            toast(R.string.title_update_subscription_no_subscription)

                        result.successCount > 0 && result.failureCount + result.skipCount == 0 ->
                            toast(dataSource.getString(R.string.title_update_config_count, result.configCount))

                        else ->
                            toast(dataSource.getString(R.string.title_update_subscription_result, result.configCount, result.successCount, result.failureCount, result.skipCount))
                    }
                    if (result.configCount > 0) {
                        setupGroupTab(forceRefresh = true)
                        refreshSelectedGuid()
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    LogUtil.e(AppConfig.TAG, "Subscription update failed", e)
                    toastError(R.string.toast_failure)
                }
            }
        }
    }

    private fun exportAllAsync() {
        launchLoading {
            withContext(ioDispatcher) {
                try {
                    val groupId = uiState.value.selectedGroupId
                    val list = if (groupId.isEmpty() && keywordFilter.isEmpty()) {
                        dataSource.getServerGuidList("")
                    } else {
                        currentServers().map { it.guid }
                    }
                    val ret = dataSource.shareNonCustomConfigsToClipboard(list)
                    if (ret > 0) {
                        toast(dataSource.getString(R.string.title_export_config_count, ret))
                    } else {
                        toastError(R.string.toast_failure)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    LogUtil.e(AppConfig.TAG, "Export failed", e)
                    toastError(R.string.toast_failure)
                }
            }
        }
    }

    private fun removeAllServerAsync() {
        launchLoading {
            withContext(ioDispatcher) {
                try {
                    val count =
                        if (uiState.value.selectedGroupId.isEmpty() && keywordFilter.isEmpty()) {
                            dataSource.removeAllServer()
                        } else {
                            val guids = currentServers().map { it.guid }
                            guids.forEach { dataSource.removeServer(it) }
                            guids.size
                        }
                    viewModelScope.launch(ioDispatcher) {
                        cacheMutex.withLock { groupDataCache.clear() }
                    }
                    setupGroupTab(forceRefresh = true)
                    toast(dataSource.getString(R.string.title_del_config_count, count))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    LogUtil.e(AppConfig.TAG, "Delete all failed", e)
                    toastError(R.string.toast_failure)
                }
            }
        }
    }

    private fun removeDuplicateServerAsync() {
        launchLoading {
            withContext(ioDispatcher) {
                try {
                    val seen = HashSet<ProfileItem>()
                    val duplicates = ArrayList<String>()
                    currentServers().forEach { server ->
                        val profile = server.profile
                        if (!profile.configType.isComplexType()) {
                            val identity = profile.duplicateIdentity()
                            if (!seen.add(identity)) duplicates += server.guid
                        }
                    }
                    duplicates.forEach { dataSource.removeServer(it) }
                    setupGroupTab(forceRefresh = true)
                    toast(dataSource.getString(R.string.title_del_duplicate_config_count, duplicates.size))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    LogUtil.e(AppConfig.TAG, "Delete duplicate failed", e)
                    toastError(R.string.toast_failure)
                }
            }
        }
    }

    private fun removeInvalidServerAsync() {
        launchLoading {
            withContext(ioDispatcher) {
                try {
                    val count = removeInvalidServerInternal()
                    viewModelScope.launch(ioDispatcher) {
                        cacheMutex.withLock { groupDataCache.clear() }
                        setupGroupTab(forceRefresh = true)
                    }
                    toast(dataSource.getString(R.string.title_del_config_count, count))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    LogUtil.e(AppConfig.TAG, "Delete invalid failed", e)
                    toastError(R.string.toast_failure)
                }
            }
        }
    }

    private fun removeInvalidServerInternal(): Int {
        val visibleServersOnly =
            uiState.value.selectedGroupId.isNotEmpty() || keywordFilter.isNotBlank()
        return if (visibleServersOnly) {
            currentServers().sumOf { server ->
                dataSource.removeInvalidServerByGuid(server.guid)
            }
        } else {
            dataSource.removeInvalidServersInGroup("")
        }
    }

    private fun sortByTestResultsAsync() {
        launchLoading {
            withContext(ioDispatcher) {
                try {
                    sortByTestResultsInternal()
                    cacheMutex.withLock { groupDataCache.clear() }
                    setupGroupTab(forceRefresh = true)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    LogUtil.e(AppConfig.TAG, "Sort by test results failed", e)
                    toastError(R.string.toast_failure)
                }
            }
        }
    }

    private fun sortByTestResultsInternal() {
        val subs = if (uiState.value.selectedGroupId.isEmpty()) {
            dataSource.getSubsList()
        } else {
            listOf(uiState.value.selectedGroupId)
        }
        subs.forEach { dataSource.sortByTestResultsForSub(it) }
    }

    fun subscriptionIdChanged(id: String) {
        if (_uiState.value.groups.none { it.id == id }) return
        mutableServersForGroup(id)
        if (uiState.value.selectedGroupId != id) {
            dataSource.setSelectedSubscriptionId(id)
            _uiState.update { it.copy(selectedGroupId = id) }
        }
        selectedGroupLoadJob?.cancel()
        selectedGroupLoadJob = viewModelScope.launch(ioDispatcher) {
            try {
                updateGroupUi(id, loadGroup(id))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                LogUtil.e(AppConfig.TAG, "Failed to load selected group: $id", error)
            }
        }
    }

    fun reloadServerList() {
        val groupId = uiState.value.selectedGroupId
        selectedGroupLoadJob?.cancel()
        selectedGroupLoadJob = viewModelScope.launch(ioDispatcher) {
            updateGroupUi(groupId, loadGroup(groupId, forceRefresh = true))
        }
    }

    fun reloadAllGroups(groupIds: List<String>) {
        reloadJob?.cancel()
        reloadJob = viewModelScope.launch(preloadDispatcher) {
            val selected = uiState.value.selectedGroupId
            val order = buildList {
                if (selected in groupIds) add(selected)
                addAll(groupIds.filter { it != selected })
            }
            order.forEachIndexed { index, groupId ->
                ensureActive()
                if (index > 0) delay(32L)
                updateGroupUi(groupId, loadGroup(groupId, forceRefresh = true))
            }
        }
    }

    fun filterConfig(keyword: String) {
        if (keyword == keywordFilter) return
        keywordFilter = keyword
        filterJob?.cancel()
        filterJob = viewModelScope.launch(defaultDispatcher) {
            delay(300L)
            val snapshot = cacheMutex.withLock { groupDataCache.toMap() }
            ensureActive()
            snapshot.forEach { (groupId, servers) ->
                ensureActive()
                updateGroupUi(groupId, servers)
            }
        }
    }

    fun updateSelectedGuid(guid: String) {
        dataSource.setSelectServer(guid)
        _uiState.update { it.copy(selectedGuid = guid) }
        // The "through VPN" figure belongs to whichever server is selected.
        refreshMeasuredSpeeds()
    }

    fun refreshSelectedGuid() {
        _uiState.update { it.copy(selectedGuid = dataSource.getSelectServer()) }
        refreshMeasuredSpeeds()
    }

    /**
     * Pulls the two measured figures the bottom cards show out of the Auto Mode store.
     *
     * Read rather than tracked, for two reasons. Both are averages over a whole download,
     * which cannot be reconstructed from the stream of instantaneous samples the meter
     * uses. And the server figure follows the *selected* server, so it changes when the
     * user picks a different one — not only when a run finishes.
     */
    fun refreshMeasuredSpeeds() {
        // Off the main thread on purpose: this parses the whole Auto Mode store and decodes
        // every profile in the reserve, which is real disk and JSON work and does not
        // belong on the thread drawing the screen.
        viewModelScope.launch(ioDispatcher) {
            val info = dataSource.dashboardServerInfo(dataSource.getSelectServer())
            _uiState.update {
                it.copy(
                    dashboard = it.dashboard.copy(
                        lineMbps = info.lineMbps,
                        vpnMbps = info.vpnMbps,
                        serverCountry = info.country,
                        reservePosition = info.reservePosition,
                        reserveTotal = info.reserveTotal,
                    )
                )
            }
        }
    }

    fun removeServerAndRefresh(guid: String) {
        if (guid == uiState.value.selectedGuid) {
            toast(R.string.toast_action_not_allowed)
            return
        }
        viewModelScope.launch(ioDispatcher) {
            dataSource.removeServer(guid)
            cacheMutex.withLock { groupDataCache.clear() }
            setupGroupTab(forceRefresh = true).join()
        }
    }

    fun moveServer(groupId: String, fromPosition: Int, toPosition: Int) {
        val servers = mutableServersForGroup(groupId).value.toMutableList()
        if (!servers.moveItem(fromPosition, toPosition)) return
        val guids = servers.map { it.guid }
        mutableServersForGroup(groupId).value = servers
        // A drag emits several moves; serialize writes so an older order cannot overwrite a newer one.
        val previousPersistenceJob = serverOrderPersistenceJobs[groupId]
        serverOrderPersistenceJobs[groupId] = viewModelScope.launch(ioDispatcher) {
            previousPersistenceJob?.join()
            dataSource.encodeServerList(guids, groupId)
            cacheMutex.withLock { groupDataCache[groupId] = servers }
        }
    }

    // ---------- Testing ----------
    fun cancelAllPing() {
        dataSource.cancelAllPing()
        testingGroupId = null
        _uiState.update {
            it.copy(
                isTesting = false,
                statusText = if (it.isRunning) connectedText else disconnectedText
            )
        }
    }

    /**
     * Same button starts and stops a run. Stopping is a request rather than a kill: the
     * service still has to unwind its scratch groups.
     */
    private fun toggleAutoMode() {
        if (uiState.value.autoMode.running) {
            pendingAutoConnect = false
            dataSource.cancelAutoMode()
            return
        }
        startAutoModeRun()
    }

    /** True when the power button can connect right now rather than having to find a server. */
    fun hasReadyServer(): Boolean = dataSource.hasReadyServer()

    /**
     * Move to the next server Auto Mode kept, or go and find more when they are used up.
     *
     * This is the answer to "this connection is no good", and it has to be cheap: the
     * reserve exists precisely so that the answer is a switch rather than a search. Only
     * when the user has worked through the whole list does it cost a run — and that they
     * did is itself the evidence that a run is warranted.
     */
    fun nextConnection() {
        if (uiState.value.autoMode.running) {
            return
        }

        viewModelScope.launch(ioDispatcher) {
            when (val next = dataSource.nextReserveServer(dataSource.getSelectServer())) {
                is AutoModeReserve.Next.Server -> {
                    dataSource.setSelectServer(next.guid)
                    refreshSelectedGuid()
                    // Restarted rather than merely selected: changing the selection under a
                    // running tunnel changes nothing the user can feel. startTunnel was the
                    // wrong call for that, and silently so, since the daemon refuses to start
                    // a core that is already running and goes on serving the old server.
                    if (uiState.value.isRunning) {
                        dataSource.restartTunnel(next.guid)
                    }
                }

                AutoModeReserve.Next.Exhausted -> {
                    toast(R.string.dashboard_reserve_exhausted)
                    // Armed, so the fresh run connects on its first acceptable server the
                    // way a power press would.
                    pendingAutoConnect = uiState.value.isRunning
                    startAutoModeRun()
                }
            }
        }
    }



    /**
     * The power button pressed with nothing to connect to.
     *
     * Rather than refusing — which is what the app used to do, and which leaves a new user
     * with a button that does nothing and no idea why — this runs Auto Mode and connects
     * on the first server that clears the bar. The run reports its own progress and a
     * countdown, so the wait is visible rather than a frozen button.
     */
    fun connectViaAutoMode() {
        if (uiState.value.autoMode.running) {
            // A run is already in flight; just arm the connection for when it reports one.
            pendingAutoConnect = true
            return
        }
        pendingAutoConnect = true
        startAutoModeRun()
    }

    private fun startAutoModeRun() {
        viewModelScope.launch(ioDispatcher) {
            // Ping tests and a run would fight over the same cores and the same radio.
            dataSource.cancelAllPing()
            dataSource.startAutoMode()
            _uiState.update {
                it.copy(
                    autoMode = AutoModeProgress(
                        running = true,
                        message = dataSource.getString(R.string.automode_running),
                    )
                )
            }
        }
    }

    fun testAllRealPing(onlyTcp: Boolean = false) {
        dataSource.cancelAllPing()
        val groupId = uiState.value.selectedGroupId
        val servers = currentServers()
        dataSource.clearAllTestDelayResults(servers.map { it.guid })
        if (servers.isEmpty()) {
            _uiState.update { it.copy(isTesting = false) }
            return
        }
        testingGroupId = groupId
        _uiState.update {
            it.copy(
                isTesting = true,
                statusText = dataSource.getString(R.string.connection_test_testing)
            )
        }
        viewModelScope.launch(ioDispatcher) {
            cacheMutex.withLock { groupDataCache.remove(groupId) }
            dataSource.sendMsg2TestService(
                TestServiceMessage(
                    key = AppConfig.MSG_MEASURE_CONFIG_START,
                    subscriptionId = groupId,
                    serverGuids = if (keywordFilter.isNotEmpty()) servers.map { it.guid } else emptyList(),
                    onlyTcp = onlyTcp
                )
            )
        }
    }

    fun testCurrentServerRealPing() {
        _uiState.update {
            it.copy(
                statusText = dataSource.getString(R.string.connection_test_testing)
            )
        }
        dataSource.testCurrentServerRealPing()
    }

    private fun onTestsFinished() {
        viewModelScope.launch(ioDispatcher) {
            cacheMutex.withLock { groupDataCache.clear() }
            testingGroupId = null
            _uiState.update {
                it.copy(
                    isTesting = false,
                    statusText = if (it.isRunning) connectedText else disconnectedText
                )
            }
            reloadAllGroups(_uiState.value.groups.map { it.id })
        }
    }

    fun triggerLocateSelectedServer() {
        val selected = dataSource.getSelectServer() ?: return
        val profile = dataSource.decodeServerConfig(selected) ?: return
        val groupId = profile.subscriptionId
        val groupIndex =
            _uiState.value.groups.indexOfFirst { it.id == groupId }.takeIf { it >= 0 } ?: return
        viewModelScope.launch(ioDispatcher) {
            val position =
                loadGroup(groupId).indexOfFirst { it.guid == selected }.takeIf { it >= 0 }
                    ?: return@launch
            _uiState.update {
                it.copy(locateTarget = LocateTarget(groupId, groupIndex, position))
            }
        }
    }

    fun getPosition(guid: String): Int = currentServers().indexOfFirst { it.guid == guid }

    private fun consumeLocateTarget(target: LocateTarget) {
        _uiState.update { state ->
            if (state.locateTarget == target) state.copy(locateTarget = null) else state
        }
    }

    // ---------- Running state ----------
    private fun updateRunningState(running: Boolean, clearTestingText: Boolean = true) {
        _uiState.update { state ->
            state.copy(
                isRunning = running,
                statusText = if (!clearTestingText && state.isTesting) state.statusText
                else if (running) connectedText else disconnectedText,
                dashboard = if (running) {
                    state.dashboard.copy(
                        connected = true,
                        connecting = false,
                    )
                } else {
                    // A dropped tunnel invalidates every *live* figure on the dashboard,
                    // including the exit country — leaving the last reading up would be a
                    // lie. The two measured ones are not live readings but results of a
                    // test, and they stay: "this server does 0.7 of your 3.9" is equally
                    // true whether or not the tunnel happens to be up right now.
                    DashboardState(
                        lineMbps = state.dashboard.lineMbps,
                        vpnMbps = state.dashboard.vpnMbps,
                        notice = state.dashboard.notice,
                    )
                }
            )
        }

        if (running) {
            refreshExitLocation()
        } else {
            ipInfoJob?.cancel()
        }
    }

    /**
     * Ask, through the tunnel, where the traffic actually comes out.
     *
     * Retried a few times because the lookup usually loses a race with the tunnel: the
     * core reports running before its outbound has finished dialling, and the first
     * request after that goes nowhere.
     */
    private fun refreshExitLocation() {
        ipInfoJob?.cancel()
        ipInfoJob = viewModelScope.launch(ioDispatcher) {
            repeat(IP_INFO_ATTEMPTS) { attempt ->
                delay(if (attempt == 0) 1500L else 4000L)
                if (!uiState.value.isRunning) return@launch

                val info = runCatching { dataSource.queryRemoteIpInfo() }.getOrNull()
                if (!info.isNullOrBlank()) {
                    val country = CountryHint.fromIpInfo(info)
                    val address = info.substringAfter(')').trim().ifBlank { null }
                    _uiState.update {
                        it.copy(dashboard = it.dashboard.copy(country = country, ipAddress = address))
                    }
                    return@launch
                }
            }
        }
    }

    override fun onCleared() {
        setupGroupJob?.cancel()
        preloadJob?.cancel()
        ipInfoJob?.cancel()
        selectedGroupLoadJob?.cancel()
        reloadJob?.cancel()
        filterJob?.cancel()
        cancelAllPing()
        dataSource.close()
        super.onCleared()
    }

    // ---------- Factory ----------
    class Factory(private val application: Application, private val dataSource: MainDataSource) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
                return MainViewModel(application, dataSource) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}
