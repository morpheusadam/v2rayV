package com.v2ray.ang.ui.automode

import android.os.Bundle
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.v2ray.ang.R
import com.v2ray.ang.automode.AutoModeSource
import com.v2ray.ang.automode.CountryHint
import com.v2ray.ang.extension.toast
import com.v2ray.ang.ui.base.BaseComponentActivity
import com.v2ray.ang.ui.compose.AppTopBar
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt

/**
 * Everything Auto Mode decides from: which links to draw on, how many servers to keep,
 * and the protocol and country it is allowed to keep them from — plus the record each
 * link has built up, which is the part that explains why a run picked what it picked.
 */
class AutoModeSourcesActivity : BaseComponentActivity() {

    private val viewModel: AutoModeSourcesViewModel by viewModels()

    override fun onResume() {
        super.onResume()
        // A run finishing in the core's process leaves new statistics on disk.
        viewModel.refresh()
    }

    @Composable
    override fun ScreenContent() {
        AutoModeSourcesScreen(
            viewModel = viewModel,
            onBackClick = { finish() },
            onSourcesSaved = { count -> toast(getString(R.string.automode_sources_saved, count)) },
        )
    }
}

@Composable
private fun AutoModeSourcesScreen(
    viewModel: AutoModeSourcesViewModel,
    onBackClick: () -> Unit,
    onSourcesSaved: (Int) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var editingSources by remember { mutableStateOf<String?>(null) }

    if (editingSources != null) {
        SourcesEditDialog(
            initialText = editingSources.orEmpty(),
            onDismiss = { editingSources = null },
            onConfirm = { text ->
                editingSources = null
                viewModel.setSourcesText(text, onSourcesSaved)
            }
        )
    }

    Scaffold(
        topBar = {
            AppTopBar(
                title = stringResource(R.string.automode_settings_title),
                onBackClick = onBackClick,
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
        ) {
            item {
                SectionHeader(stringResource(R.string.automode_section_sources))
                Text(
                    text = stringResource(
                        R.string.automode_sources_summary,
                        state.sources.size,
                        state.sources.count { it.enabled },
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { editingSources = viewModel.currentSourcesText() }) {
                    Text(stringResource(R.string.automode_edit_sources))
                }
                Text(
                    text = stringResource(R.string.automode_runs_so_far, state.runCount, state.sourcesPerRun),
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
            }

            item {
                SectionHeader(stringResource(R.string.automode_section_keep))
                Text(
                    text = stringResource(R.string.automode_keep_count, state.topCount),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Slider(
                    value = state.topCount.toFloat(),
                    onValueChange = { viewModel.setTopCount(it.roundToInt()) },
                    valueRange = 1f..50f,
                    steps = 48,
                )
                HorizontalDivider()
            }

            item {
                SectionHeader(stringResource(R.string.automode_section_smart_switch))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.automode_smart_switch_label),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = state.smartSwitch,
                        onCheckedChange = { viewModel.setSmartSwitch(it) },
                    )
                }
                Text(
                    text = stringResource(R.string.automode_smart_switch_hint),
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
            }

            item {
                SectionHeader(stringResource(R.string.automode_section_protocol))
                Text(
                    text = stringResource(R.string.automode_filter_any_hint),
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    viewModel.availableProtocols.forEach { protocol ->
                        FilterChip(
                            selected = state.protocolFilter.contains(protocol.name),
                            onClick = { viewModel.toggleProtocol(protocol.name) },
                            label = { Text(protocol.name) },
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
            }

            item {
                SectionHeader(stringResource(R.string.automode_section_country))
                Text(
                    text = stringResource(R.string.automode_country_hint),
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CountryHint.pickerOptions.forEach { (code, name) ->
                        FilterChip(
                            selected = state.countryFilter.contains(code),
                            onClick = { viewModel.toggleCountry(code) },
                            label = { Text("$code — $name") },
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
            }

            item {
                SectionHeader(stringResource(R.string.automode_section_stats))
            }

            items(state.sources, key = { it.url }) { source ->
                SourceRow(
                    source = source,
                    onToggle = { enabled -> viewModel.setSourceEnabled(source.url, enabled) },
                )
                HorizontalDivider()
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Spacer(Modifier.height(16.dp))
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
    )
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun SourceRow(source: AutoModeSource, onToggle: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = source.url,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = sourceStatsLine(source),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val stateLine = sourceStateLine(source)
            if (stateLine != null) {
                Text(
                    text = stateLine,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        Switch(checked = source.enabled, onCheckedChange = onToggle)
    }
}

/**
 * Quality is the posterior mean, not a raw success rate: a link tried once and lucky
 * should not outrank one with a long record, and this is the number the sampler itself
 * draws around.
 */
private fun sourceStatsLine(source: AutoModeSource): String {
    val quality = (source.score * 100).roundToInt()
    val lastTried = if (source.lastTriedMillis > 0) {
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
            .format(Date(source.lastTriedMillis))
    } else {
        "never"
    }
    return "quality $quality% · ${source.greenTotal}/${source.testedTotal} worked · " +
        "${source.winnerTotal} kept · ${source.lastConfigCount} configs · tried ${source.tried}× · last $lastTried"
}

private fun sourceStateLine(source: AutoModeSource): String? = when {
    source.autoDisabled -> "parked after ${source.deadStreak} empty runs — still probed occasionally"
    source.deadStreak > 0 -> "${source.deadStreak} empty runs in a row"
    source.staleRuns > 2 -> "unchanged for ${source.staleRuns} runs"
    else -> null
}

@Composable
private fun SourcesEditDialog(
    initialText: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initialText) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.automode_edit_sources)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.automode_edit_sources_hint),
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 200.dp, max = 360.dp),
                    textStyle = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }) {
                Text(stringResource(R.string.tasker_setting_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}
