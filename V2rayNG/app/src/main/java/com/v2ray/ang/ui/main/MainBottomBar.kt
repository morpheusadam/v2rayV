package com.v2ray.ang.ui.main

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.v2ray.ang.R
import com.v2ray.ang.automode.AutoModeProgress
import com.v2ray.ang.ui.compose.AppDivider
import com.v2ray.ang.ui.compose.colorFabActive
import com.v2ray.ang.ui.compose.colorFabInactiveDark
import com.v2ray.ang.ui.compose.colorFabInactiveLight

@Composable
fun MainBottomBar(
    displayText: String,
    isRunning: Boolean,
    isDarkTheme: Boolean,
    autoMode: AutoModeProgress,
    onAutoModeSettings: () -> Unit,
    onAction: (MainAction) -> Unit
) {
    Box(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth()) {
            AppDivider()
            AutoModeRow(
                autoMode = autoMode,
                onToggle = { onAction(MainAction.ToggleAutoMode) },
                onSettings = onAutoModeSettings,
            )
            AppDivider()
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .height(64.dp)
                    .clickable(onClick = { onAction(MainAction.TestCurrentServer) }),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 0.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(text = displayText, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        FloatingActionButton(
            onClick = { onAction(MainAction.ToggleService) },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(end = 24.dp)
                .offset(y = (-28).dp)
                .navigationBarsPadding(),
            containerColor = if (isRunning) colorFabActive
            else if (isDarkTheme) colorFabInactiveDark
            else colorFabInactiveLight
        ) {
            Icon(
                painter = if (isRunning) painterResource(R.drawable.ic_stop_24dp)
                else painterResource(R.drawable.ic_play_24dp),
                contentDescription = stringResource(
                    if (isRunning) R.string.acc_stop else R.string.acc_start
                ),
                tint = Color.White,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

/**
 * One press runs the whole pipeline, and the same press stops it. While a run is in
 * flight the button carries the stage it is on and roughly how much longer it has, so the
 * user knows whether to wait or walk away — the alternative is a spinner that says
 * nothing for four minutes.
 */
@Composable
private fun AutoModeRow(
    autoMode: AutoModeProgress,
    onToggle: () -> Unit,
    onSettings: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // The floating action button hangs over this row's trailing edge, so the
                // gear has to clear it or the two overlap and neither is reliably hittable.
                .padding(start = 16.dp, end = 88.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable(onClick = onToggle)
                    .padding(vertical = 8.dp)
            ) {
                Text(
                    text = if (autoMode.running) {
                        stringResource(R.string.automode_stop_button, formatRemaining(autoMode.remainingMillis))
                    } else {
                        stringResource(R.string.automode_button)
                    },
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                if (autoMode.running && autoMode.message.isNotBlank()) {
                    Text(
                        text = autoMode.message,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            IconButton(onClick = onSettings) {
                Icon(
                    painter = painterResource(R.drawable.ic_settings_24dp),
                    contentDescription = stringResource(R.string.automode_acc_settings),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

/** Blank rather than "0:00" before the first stage has produced a figure to project. */
private fun formatRemaining(remainingMillis: Long): String {
    if (remainingMillis <= 0) return ""
    val totalSeconds = remainingMillis / 1000
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}
