package com.v2ray.ang.ui.automode

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.v2ray.ang.automode.AutoModeSource
import com.v2ray.ang.automode.AutoModeSourceManager
import com.v2ray.ang.enums.EConfigType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AutoModeSettingsState(
    val sources: List<AutoModeSource> = emptyList(),
    val topCount: Int = 10,
    val protocolFilter: List<String> = emptyList(),
    val countryFilter: List<String> = emptyList(),
    val iranMode: Boolean = false,
    val runCount: Int = 0,
    val sourcesPerRun: Int = 8,
    val smartSwitch: Boolean = false,
    val mirrorsEnabled: Boolean = false,
    val mirrorIndex: Int = 0,
)

class AutoModeSourcesViewModel : ViewModel() {

    private val _state = MutableStateFlow(AutoModeSettingsState())
    val state: StateFlow<AutoModeSettingsState> = _state.asStateFlow()

    /**
     * Protocols the Android core can actually carry. The desktop client offers a longer
     * list; offering an option here that no parser produces would just be a filter that
     * silently keeps nothing.
     */
    val availableProtocols: List<EConfigType> = listOf(
        EConfigType.VLESS,
        EConfigType.VMESS,
        EConfigType.TROJAN,
        EConfigType.SHADOWSOCKS,
        EConfigType.HYSTERIA2,
        EConfigType.WIREGUARD,
        EConfigType.SOCKS,
        EConfigType.HTTP,
    )

    init {
        refresh()
    }

    /** Re-reads from disk: a run in the core's process may have rewritten the stats. */
    fun refresh() {
        viewModelScope.launch {
            val store = withContext(Dispatchers.IO) { AutoModeSourceManager.reload() }
            _state.value = AutoModeSettingsState(
                sources = store.sources.toList(),
                topCount = store.topCount,
                protocolFilter = store.protocolFilter.toList(),
                countryFilter = store.countryFilter.toList(),
                iranMode = store.iranMode,
                runCount = store.runCount,
                sourcesPerRun = store.sourcesPerRun,
                smartSwitch = store.smartSwitch,
                mirrorsEnabled = store.mirrorsEnabled,
                mirrorIndex = store.mirrorIndex,
            )
        }
    }

    /** @return how many distinct usable links the pasted text produced. */
    fun setSourcesText(text: String, onDone: (Int) -> Unit) {
        viewModelScope.launch {
            val count = withContext(Dispatchers.IO) {
                AutoModeSourceManager.setUrls(AutoModeSourceManager.parseUrls(text))
            }
            refresh()
            onDone(count)
        }
    }

    fun currentSourcesText(): String = _state.value.sources.joinToString("\n") { it.url }

    fun setSourceEnabled(url: String, enabled: Boolean) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { AutoModeSourceManager.setEnabled(url, enabled) }
            refresh()
        }
    }

    fun setTopCount(count: Int) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { AutoModeSourceManager.setTopCount(count) }
            refresh()
        }
    }

    fun setSmartSwitch(enabled: Boolean) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { AutoModeSourceManager.setSmartSwitch(enabled) }
            refresh()
        }
    }

    fun setMirrorsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { AutoModeSourceManager.setMirrorsEnabled(enabled) }
            refresh()
        }
    }

    fun setMirrorIndex(index: Int) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { AutoModeSourceManager.setMirrorIndex(index) }
            refresh()
        }
    }

    fun toggleProtocol(name: String) {
        viewModelScope.launch {
            val current = _state.value.protocolFilter.toMutableList()
            if (!current.remove(name)) current.add(name)
            withContext(Dispatchers.IO) { AutoModeSourceManager.setProtocolFilter(current) }
            refresh()
        }
    }

    fun setIranMode(enabled: Boolean) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { AutoModeSourceManager.setIranMode(enabled) }
            refresh()
        }
    }

    fun toggleCountry(code: String) {
        viewModelScope.launch {
            val current = _state.value.countryFilter.toMutableList()
            if (!current.remove(code)) current.add(code)
            withContext(Dispatchers.IO) { AutoModeSourceManager.setCountryFilter(current) }
            refresh()
        }
    }
}
