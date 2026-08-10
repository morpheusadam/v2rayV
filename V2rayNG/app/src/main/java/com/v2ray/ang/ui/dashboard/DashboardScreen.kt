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
import androidx.compose.material3.TextButton
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
    autoModeRemainingMillis: Long,
    autoModeStage: com.v2ray.ang.automode.AutoModeStage,
    onTogglePower: () -> Unit,
    onNextConnection: () -> Unit,
    onAutoModeSettings: () -> Unit,
    onOpenServers: () -> Unit,
    onOpenMenu: () -> Unit,
    onNoticeAction: () -> Unit = {},
    onNoticeDismiss: () -> Unit = {},
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

            // Live rates. While a test is running the tunnel's own counters read zero —
            // the traffic belongs to throwaway cores, not the tunnel — so the download
            // card borrows the measurement in flight rather than sitting dead through the
            // one part of the app that is visibly working hardest.
            //
            // Once connected it goes back to the real download rate even if a run is still
            // going, because from then on the tunnel's own throughput is the number the
            // user came to the screen for, and a background refresh is not.
            val showTestMeter = state.testing && !state.connected
            Row(horizontalArrangement = Arrangement.spacedBy(Securo.CardGap)) {
                MetricCard(
                    label = stringResource(
                        if (showTestMeter) R.string.dashboard_testing else R.string.dashboard_download
                    ),
                    value = if (showTestMeter) {
                        formatMeasured(state.testingMbps)
                    } else {
                        formatSpeedValue(state.downSpeed)
                    },
                    unit = "MB/s",
                    accent = Securo.Green,
                    fraction = if (showTestMeter) {
                        ratioOf(state.testingMbps, maxOf(state.lineMbps, state.testingMbps))
                    } else {
                        ratio(state.downSpeed, state.peakDown)
                    },
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

            // Measured results rather than live rates: what this line does on its own, and
            // what the selected server does through it. Shown side by side because neither
            // means much alone — the pair is the answer to "is this server any good", and
            // the shared scale makes the shortfall readable without doing the division.
            Row(horizontalArrangement = Arrangement.spacedBy(Securo.CardGap)) {
                val scale = maxOf(state.lineMbps, state.vpnMbps)
                MetricCard(
                    label = stringResource(R.string.dashboard_line_speed),
                    value = formatMeasured(state.lineMbps),
                    unit = "MB/s",
                    accent = Securo.Violet,
                    fraction = ratioOf(state.lineMbps, scale),
                    modifier = Modifier.weight(1f),
                )
                MetricCard(
                    // The flag rides on this card's label rather than getting a card of
                    // its own: "through VPN" and "which country the VPN comes out in" are
                    // one fact, and splitting them would cost a box to say half of it.
                    label = countryFlag(state.serverCountry)?.let {
                        "$it  ${stringResource(R.string.dashboard_vpn_speed)}"
                    } ?: stringResource(R.string.dashboard_vpn_speed),
                    value = formatMeasured(state.vpnMbps),
                    unit = "MB/s",
                    accent = Securo.Green,
                    fraction = ratioOf(state.vpnMbps, scale),
                    modifier = Modifier.weight(1f),
                )
            }

            AutoModeCard(
                running = autoModeRunning,
                message = autoModeMessage,
                reservePosition = state.reservePosition,
                reserveTotal = state.reserveTotal,
                onNext = onNextConnection,
                onSettings = onAutoModeSettings,
            )

            // One slot, two tenants. While a run is finding a server the space belongs to
            // the countdown — that is the moment the user is actually waiting on something
            // and wants to know how long. The rest of the time it is the notice, which is
            // nearly always nothing at all.
            //
            // Not while connected, though. A run keeps going after the first acceptable
            // server in order to refill the reserve, and a clock counting down to a
            // connection that already happened is telling the user to wait for something
            // they already have.
            if (autoModeRunning && !state.connected) {
                ConnectingCard(
                    stage = autoModeStage,
                    remainingMillis = autoModeRemainingMillis,
                )
            } else {
                NoticeCard(
                    notice = state.notice,
                    onAction = onNoticeAction,
                    onDismiss = onNoticeDismiss,
                )
            }

            Spacer(Modifier.navigationBarsPadding())
        }
    }
}

private fun ratio(value: Long, max: Long): Float =
    if (max <= 0L) 0f else (value.toFloat() / max.toFloat()).coerceIn(0f, 1f)

private fun ratioOf(value: Double, max: Double): Float =
    if (max <= 0.0) 0f else (value / max).toFloat().coerceIn(0f, 1f)

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
 *
 * One button and one gear. The row used to carry three icons — run, settings, server list
 * — which asked the user to know the difference between them. What they actually want when
 * a connection disappoints is the next one, so that is the button; the run it may trigger
 * is a consequence rather than a thing to choose.
 */
@Composable
private fun AutoModeCard(
    running: Boolean,
    message: String,
    reservePosition: Int,
    reserveTotal: Int,
    onNext: () -> Unit,
    onSettings: () -> Unit,
) {
    SecuroCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp)
            ) {
                // No clock here. The countdown card below owns it, and the two disagreed
                // by a few seconds — this one is the engine's raw projection, that one is
                // smoothed — which reads as a bug even though neither is wrong. The
                // progress line underneath already says a run is going.
                SecuroLabel(
                    text = stringResource(R.string.automode_button),
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

            // Disabled while a run is in flight: there is no "next" to move to until it
            // has produced one, and a second run would fight the first over the same
            // scratch groups.
            SecuroPillButton(
                text = if (reserveTotal > 0 && reservePosition > 0) {
                    stringResource(R.string.dashboard_next_connection_n, reservePosition, reserveTotal)
                } else {
                    stringResource(R.string.dashboard_next_connection)
                },
                onClick = onNext,
                enabled = !running,
                subdued = running,
            )
            IconButton(onClick = onSettings) {
                Icon(
                    painter = painterResource(R.drawable.ic_settings_24dp),
                    contentDescription = stringResource(R.string.automode_acc_settings),
                    tint = Securo.TextSecondary,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}
