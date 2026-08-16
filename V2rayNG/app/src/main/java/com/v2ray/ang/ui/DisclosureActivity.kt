package com.v2ray.ang.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.automode.AutoModeNetwork
import com.v2ray.ang.automode.AutoModeSourceManager
import com.v2ray.ang.automode.ThroughputProbe
import com.v2ray.ang.ui.base.BaseComponentActivity
import com.v2ray.ang.ui.compose.AppTopBar

/**
 * Every host this app talks to, why, and when — read out of the constants that actually
 * govern it rather than written down separately and left to drift.
 *
 * A VPN client asks for more trust than almost any other kind of app: it is handed every
 * packet the device sends. The honest response to that is not a privacy policy on a website
 * nobody opens, it is a list the user can hold next to the source code. Anything here that
 * is wrong is falsifiable in a packet capture, which is the point — a claim that cannot be
 * checked is not worth making.
 *
 * The list is exhaustive for the app itself. It says nothing about where a *server* sends
 * traffic once the tunnel is up, because that is the server operator's business and this
 * screen would be lying if it implied otherwise.
 */
class DisclosureActivity : BaseComponentActivity() {

    @Composable
    override fun ScreenContent() {
        DisclosureScreen(onBackClick = { finish() })
    }
}

private data class Destination(
    val host: String,
    val whenContacted: Int,
    val why: Int,
)

@Composable
fun DisclosureScreen(onBackClick: () -> Unit) {
    val mirror = runCatching {
        val store = AutoModeSourceManager.getStore()
        if (!store.mirrorsEnabled) null
        else AutoModeNetwork.MIRRORS.getOrNull(store.mirrorIndex) ?: AutoModeNetwork.MIRRORS.first()
    }.getOrNull()

    val destinations = buildList {
        add(
            Destination(
                host = "raw.githubusercontent.com",
                whenContacted = R.string.disclosure_when_run,
                why = R.string.disclosure_why_lists,
            )
        )
        mirror?.let {
            add(
                Destination(
                    host = it.name,
                    whenContacted = R.string.disclosure_when_blocked,
                    why = R.string.disclosure_why_mirror,
                )
            )
        }
        add(
            Destination(
                host = hostOf(ThroughputProbe.DOWNLOAD_URL),
                whenContacted = R.string.disclosure_when_run,
                why = R.string.disclosure_why_baseline,
            )
        )
        add(
            Destination(
                host = "${hostOf(AppConfig.DELAY_TEST_URL)} · ${hostOf(AppConfig.DELAY_TEST_URL2)}",
                whenContacted = R.string.disclosure_when_run,
                why = R.string.disclosure_why_delay,
            )
        )
        add(
            Destination(
                host = hostOf(AppConfig.IP_API_URL),
                whenContacted = R.string.disclosure_when_connected,
                why = R.string.disclosure_why_country,
            )
        )
        add(
            Destination(
                host = hostOf(AppConfig.APP_API_URL),
                whenContacted = R.string.disclosure_when_update,
                why = R.string.disclosure_why_update,
            )
        )
        add(
            Destination(
                host = stringResourceHost(),
                whenContacted = R.string.disclosure_when_connected,
                why = R.string.disclosure_why_servers,
            )
        )
    }

    Scaffold(
        contentWindowInsets = ScaffoldDefaults.contentWindowInsets,
        topBar = {
            AppTopBar(title = stringResource(R.string.title_disclosure), onBackClick = onBackClick)
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Text(
                text = stringResource(R.string.disclosure_intro),
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(16.dp))

            destinations.forEach { destination ->
                Text(destination.host, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(2.dp))
                Text(
                    text = stringResource(destination.why),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = stringResource(destination.whenContacted),
                    style = MaterialTheme.typography.labelSmall,
                )
                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))
            }

            Text(
                text = stringResource(R.string.disclosure_never_title),
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.disclosure_never),
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun stringResourceHost(): String = stringResource(R.string.disclosure_host_servers)

/** Host part of a URL, for display. The full URL is noise on a list meant to be scanned. */
private fun hostOf(url: String): String =
    runCatching { java.net.URI(url).host.orEmpty() }.getOrNull()?.takeIf { it.isNotEmpty() } ?: url
