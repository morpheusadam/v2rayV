package com.v2ray.ang.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.v2ray.ang.R

/**
 * The screen the app opens on: is the tunnel up, through where, how fast, how much.
 *
 * Deliberately not a Material surface — it is a fixed dark instrument panel with its own
 * palette, so it looks the same whichever theme the rest of the app is running in.
 */
@Composable
fun DashboardScreen(
    state: DashboardState,
    autoModeRunning: Boolean,
    autoModeMessage: String,
    autoModeRemaining: String,
    onTogglePower: () -> Unit,
    onToggleAutoMode: () -> Unit,
    onAutoModeSettings: () -> Unit,
    onOpenServers: () -> Unit,
    onOpenMenu: () -> Unit,
) {
    val accent = if (state.connected) Securo.Green else Securo.Red

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Securo.Background)
    ) {
        // The bloom behind the status card. In the mockups the glow spills from behind the
        // top of the phone, which is what stops the black from reading as an empty screen.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(340.dp)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            accent.copy(alpha = 0.30f),
                            accent.copy(alpha = 0.06f),
                            Color.Transparent,
                        )
                    )
                )
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .statusBarsPadding()
                .padding(horizontal = Securo.ScreenPadding)
                .padding(top = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(Securo.CardGap),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onOpenMenu) {
                    Icon(
                        painter = painterResource(R.drawable.ic_menu_24dp),
                        contentDescription = stringResource(R.string.acc_open_menu),
                        tint = Securo.TextPrimary,
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(
                    text = state.serverName.ifBlank { stringResource(R.string.dashboard_no_server) },
                    style = Securo.Unit,
                    color = Securo.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(end = 4.dp),
                )
            }

            StatusCard(state = state, onTogglePower = onTogglePower)

            Row(horizontalArrangement = Arrangement.spacedBy(Securo.CardGap)) {
                MetricCard(
                    label = stringResource(R.string.dashboard_download),
                    value = formatSpeedValue(state.downSpeed),
                    unit = "MB/s",
                    accent = Securo.Green,
                    fraction = ratio(state.downSpeed, state.peakDown),
                    samples = state.downSamples,
                    modifier = Modifier.weight(1f),
                )
                MetricCard(
                    label = stringResource(R.string.dashboard_upload),
                    value = formatSpeedValue(state.upSpeed),
                    unit = "MB/s",
                    accent = Securo.Violet,
                    fraction = ratio(state.upSpeed, state.peakUp),
                    samples = state.upSamples,
                    modifier = Modifier.weight(1f),
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(Securo.CardGap)) {
                MetricCard(
                    label = stringResource(R.string.dashboard_downloaded),
                    value = formatVolumeValue(state.downTotal),
                    unit = volumeUnit(state.downTotal),
                    accent = Securo.Green,
                    // Against the session's own larger channel, so the pair reads as a
                    // split rather than each being scaled to itself.
                    fraction = ratio(state.downTotal, maxOf(state.downTotal, state.upTotal)),
                    modifier = Modifier.weight(1f),
                )
                MetricCard(
                    label = stringResource(R.string.dashboard_uploaded),
                    value = formatVolumeValue(state.upTotal),
                    unit = volumeUnit(state.upTotal),
                    accent = Securo.Violet,
                    fraction = ratio(state.upTotal, maxOf(state.downTotal, state.upTotal)),
                    modifier = Modifier.weight(1f),
                )
            }

            AutoModeCard(
                running = autoModeRunning,
                message = autoModeMessage,
                remaining = autoModeRemaining,
                onToggle = onToggleAutoMode,
                onSettings = onAutoModeSettings,
                onOpenServers = onOpenServers,
            )

            Spacer(Modifier.navigationBarsPadding())
        }
    }
}

private fun ratio(value: Long, max: Long): Float =
    if (max <= 0L) 0f else (value.toFloat() / max.toFloat()).coerceIn(0f, 1f)

@Composable
private fun StatusCard(state: DashboardState, onTogglePower: () -> Unit) {
    SecuroCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                val flag = countryFlag(state.country)
                if (flag != null) {
                    Text(text = flag, fontSize = 26.sp)
                    Spacer(Modifier.height(6.dp))
                }

                SecuroLabel(
                    text = stringResource(
                        when {
                            state.connecting -> R.string.dashboard_connecting
                            state.connected -> R.string.dashboard_connected
                            else -> R.string.dashboard_not_connected
                        }
                    ),
                    color = if (state.connected) Securo.Green else Securo.Red,
                )

                Spacer(Modifier.height(6.dp))
                Text(
                    text = formatElapsed(state.elapsedMillis),
                    style = Securo.Readout,
                    color = Securo.TextPrimary,
                )

                Spacer(Modifier.height(8.dp))
                // The exit address, not the server's name — the name is already in the
                // bar above, and repeating it here would waste the one line that says
                // where the traffic actually comes out.
                Text(
                    text = state.ipAddress ?: stringResource(R.string.dashboard_no_address),
                    style = Securo.Unit,
                    color = Securo.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            PowerRing(
                connected = state.connected,
                // A connecting tunnel has no measurement to show yet, so the ring runs
                // full as a state indicator rather than pretending to a reading.
                progress = if (state.connected || state.connecting) 1f else 0f,
                onClick = onTogglePower,
                modifier = Modifier.size(128.dp),
            )
        }
    }
}

/**
 * Auto Mode lives on the dashboard rather than in a menu: it is the one control that
 * changes what the power button will connect to.
 */
@Composable
private fun AutoModeCard(
    running: Boolean,
    message: String,
    remaining: String,
    onToggle: () -> Unit,
    onSettings: () -> Unit,
    onOpenServers: () -> Unit,
) {
    SecuroCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp)
            ) {
                SecuroLabel(
                    text = if (running) {
                        stringResource(R.string.dashboard_automode_running, remaining)
                    } else {
                        stringResource(R.string.automode_button)
                    },
                    color = Securo.Green,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = message.ifBlank { stringResource(R.string.dashboard_automode_hint) },
                    style = Securo.Unit,
                    color = Securo.TextSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            IconButton(onClick = onToggle) {
                Icon(
                    painter = painterResource(
                        if (running) R.drawable.ic_stop_24dp else R.drawable.ic_play_24dp
                    ),
                    contentDescription = stringResource(R.string.automode_button),
                    tint = Securo.Green,
                    modifier = Modifier.size(24.dp),
                )
            }
            IconButton(onClick = onSettings) {
                Icon(
                    painter = painterResource(R.drawable.ic_settings_24dp),
                    contentDescription = stringResource(R.string.automode_acc_settings),
                    tint = Securo.TextSecondary,
                    modifier = Modifier.size(22.dp),
                )
            }
            IconButton(onClick = onOpenServers) {
                Icon(
                    painter = painterResource(R.drawable.ic_subscriptions_24dp),
                    contentDescription = stringResource(R.string.dashboard_open_servers),
                    tint = Securo.TextSecondary,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}
