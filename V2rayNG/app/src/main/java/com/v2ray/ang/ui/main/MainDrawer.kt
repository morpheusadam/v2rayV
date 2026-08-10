package com.v2ray.ang.ui.main

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DrawerState
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.v2ray.ang.R
import com.v2ray.ang.ui.dashboard.Securo

enum class MainDestination(@DrawableRes val iconRes: Int, @StringRes val labelRes: Int) {
    AutoMode(R.drawable.ic_check_update_24dp, R.string.automode_settings_title),
    Subscriptions(R.drawable.ic_subscriptions_24dp, R.string.title_sub_setting),
    Routing(R.drawable.ic_routing_24dp, R.string.routing_settings_title),
    UserAssets(R.drawable.ic_file_24dp, R.string.title_user_asset_setting),
    Settings(R.drawable.ic_settings_24dp, R.string.title_settings),
    Logcat(R.drawable.ic_logcat_24dp, R.string.title_logcat),
    CheckUpdate(R.drawable.ic_check_update_24dp, R.string.update_check_for_update),
    BackupRestore(R.drawable.ic_restore_24dp, R.string.title_configuration_backup_restore),
    About(R.drawable.ic_about_24dp, R.string.title_about)
}

/**
 * What the app is for, in the order someone reaches for it. Auto Mode is the app; the
 * server list is where you go to look at what it found; the rest is configuration.
 */
private val runItems = listOf(MainDestination.AutoMode)

private val configItems = listOf(
    MainDestination.Subscriptions,
    MainDestination.Routing,
    MainDestination.UserAssets,
    MainDestination.Settings,
)

private val aboutItems = listOf(
    MainDestination.Logcat,
    MainDestination.CheckUpdate,
    MainDestination.BackupRestore,
    MainDestination.About,
)

/**
 * The drawer, in the dashboard's language rather than Material's.
 *
 * The screen behind it is a fixed dark instrument panel whose colours never follow the
 * system theme, and a stock Material sheet sliding over that read as a different app. So
 * this takes its palette from [Securo] and is likewise absolute.
 *
 * Grouped rather than listed flat: the old drawer was nine items of equal weight, which
 * made the one that matters — Auto Mode — no easier to find than the licence screen.
 */
@Composable
fun MainDrawerContent(
    drawerState: DrawerState,
    onNavigate: (MainDestination) -> Unit,
    onOpenServers: () -> Unit,
) {
    val scrollState = rememberScrollState()

    ModalDrawerSheet(
        drawerState = drawerState,
        modifier = Modifier.fillMaxWidth(0.80f),
        drawerContainerColor = Securo.Background,
        drawerContentColor = Securo.TextPrimary,
        drawerShape = RoundedCornerShape(topEnd = Securo.CardRadius, bottomEnd = Securo.CardRadius),
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .verticalScroll(scrollState)
                .padding(bottom = 24.dp)
        ) {
            DrawerHeader()

            Spacer(Modifier.height(8.dp))

            runItems.forEach { item ->
                DrawerRow(item.iconRes, stringResource(item.labelRes), emphasised = true) {
                    onNavigate(item)
                }
            }
            // Not a MainDestination: the server list lives inside the main screen rather
            // than in an activity of its own, so it is opened rather than navigated to.
            DrawerRow(R.drawable.ic_subscriptions_24dp, stringResource(R.string.drawer_servers), emphasised = true) {
                onOpenServers()
            }

            DrawerSection(stringResource(R.string.drawer_section_configure))
            configItems.forEach { item ->
                DrawerRow(item.iconRes, stringResource(item.labelRes)) { onNavigate(item) }
            }

            DrawerSection(stringResource(R.string.drawer_section_app))
            aboutItems.forEach { item ->
                DrawerRow(item.iconRes, stringResource(item.labelRes)) { onNavigate(item) }
            }
        }
    }
}

/**
 * The mark, the name and the tagline over a soft green wash.
 *
 * The wash is the only gradient in the app and it earns its place here: the header is the
 * one surface with nothing to read on it, so it can carry the brand colour without
 * competing with a number.
 */
@Composable
private fun DrawerHeader() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(168.dp)
            .background(
                Brush.verticalGradient(
                    listOf(Securo.GreenDim.copy(alpha = 0.28f), Securo.Background)
                )
            )
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 20.dp, bottom = 20.dp, end = 20.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_logo),
                contentDescription = null,
                tint = Color.Unspecified,
                modifier = Modifier.size(44.dp),
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.app_name),
                color = Securo.TextPrimary,
                style = Securo.ReadoutSmall,
            )
            Text(
                text = stringResource(R.string.app_tagline),
                color = Securo.Green,
                style = Securo.Label,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/** A hairline and a small-caps label — the same device the dashboard uses over each card. */
@Composable
private fun DrawerSection(title: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = title, color = Securo.TextSecondary, style = Securo.Label)
        Spacer(Modifier.width(12.dp))
        Box(
            Modifier
                .weight(1f)
                .height(1.dp)
                .background(Securo.CardBorder)
        )
    }
}

/**
 * One row. [emphasised] gives it the card treatment the dashboard uses — a filled, bordered
 * block — so the two things a user actually presses do not read as list items.
 */
@Composable
private fun DrawerRow(
    @DrawableRes iconRes: Int,
    label: String,
    emphasised: Boolean = false,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(if (emphasised) 16.dp else 12.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 3.dp)
            .clip(shape)
            .then(
                if (emphasised) {
                    Modifier
                        .background(Securo.Card)
                        .border(1.dp, Securo.CardBorder, shape)
                } else {
                    Modifier
                }
            )
            .clickable(onClick = onClick)
            .padding(PaddingValues(horizontal = 14.dp, vertical = if (emphasised) 14.dp else 12.dp)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = if (emphasised) Securo.Green else Securo.TextSecondary,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(16.dp))
        Text(
            text = label,
            color = if (emphasised) Securo.TextPrimary else Securo.TextSecondary,
        )
    }
}
