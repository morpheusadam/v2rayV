package com.v2ray.ang.ui.dashboard

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.v2ray.ang.R
import com.v2ray.ang.automode.AutoModeStage
import kotlinx.coroutines.delay
import java.util.Locale

/** Stages drawn on the timeline, in order. [AutoModeStage.DONE] is the end, not a step. */
private val TIMELINE_STAGES = listOf(
    AutoModeStage.MEASURING,
    AutoModeStage.ROUTING,
    AutoModeStage.FETCHING,
    AutoModeStage.IMPORTING,
    AutoModeStage.PROBING,
    AutoModeStage.TUNNELING,
    AutoModeStage.MEASURING_SERVERS,
)

/** Where the clock starts before the run has measured anything to project from. */
private const val DEFAULT_SECONDS = 59L

/**
 * What the dashboard shows between pressing power and being connected.
 *
 * The clock ticks locally, once a second, rather than redrawing whatever the engine last
 * projected. The engine's estimate is real — it is recomputed from the stage actually
 * running — but it arrives in jumps, and a number that leaps from 40 to 55 and back reads
 * as broken even when it is more accurate than a smooth one. So the estimate sets the
 * target and the display walks towards it.
 *
 * It also refuses to sit at zero. A run that overruns keeps counting at "0:01" rather than
 * showing 0:00 for a minute, because a finished countdown on an unfinished job is a lie
 * the user can see.
 */
@Composable
fun ConnectingCard(
    stage: AutoModeStage,
    remainingMillis: Long,
    modifier: Modifier = Modifier,
) {
    var secondsLeft by remember { mutableLongStateOf(DEFAULT_SECONDS) }

    // A fresh projection replaces the clock only when it differs enough to be worth the
    // jump; small corrections are absorbed by the tick below.
    LaunchedEffect(remainingMillis) {
        val projected = remainingMillis / 1000
        if (projected > 0 && kotlin.math.abs(projected - secondsLeft) > 5) {
            secondsLeft = projected
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            secondsLeft = if (secondsLeft > 1) secondsLeft - 1 else 1
        }
    }

    SecuroCard(modifier = modifier.fillMaxWidth()) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SecuroLabel(
                    text = stringResource(R.string.dashboard_connecting_in),
                    color = Securo.Green,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = formatCountdown(secondsLeft),
                    style = Securo.ReadoutSmall,
                    color = Securo.TextPrimary,
                )
            }

            Spacer(Modifier.height(12.dp))

            // The running commentary is not repeated here: the Auto Mode card directly
            // above already carries it, and printing it twice made the pair read as two
            // things happening rather than one.
            StageTimeline(stage = stage, modifier = Modifier.fillMaxWidth())
        }
    }
}

/**
 * A rail with one node per stage: filled behind the run, hollow ahead of it, and a wider
 * dot on the stage it is in.
 *
 * Segmented rather than a smooth bar for the same reason the meters above it are — the
 * work is a sequence of discrete steps, and drawing it as a continuous fill would imply
 * the run knows how far through a step it is, which it does not.
 */
@Composable
private fun StageTimeline(stage: AutoModeStage, modifier: Modifier = Modifier) {
    val index = TIMELINE_STAGES.indexOf(stage).let {
        // DONE, or anything unrecognised, reads as "past the last step".
        if (it < 0) TIMELINE_STAGES.size - 1 else it
    }
    val progress by animateFloatAsState(
        targetValue = index / (TIMELINE_STAGES.size - 1).toFloat(),
        animationSpec = tween(durationMillis = 600),
        label = "timeline",
    )

    Column(modifier = modifier) {
        Canvas(modifier = Modifier.fillMaxWidth().height(14.dp)) {
            val y = size.height / 2f
            val radius = 4.dp.toPx()
            val usable = size.width - radius * 2
            val step = usable / (TIMELINE_STAGES.size - 1)

            drawLine(
                color = Securo.Track,
                start = Offset(radius, y),
                end = Offset(radius + usable, y),
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round,
            )
            drawLine(
                color = Securo.GreenDim,
                start = Offset(radius, y),
                end = Offset(radius + usable * progress, y),
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round,
            )

            TIMELINE_STAGES.forEachIndexed { i, _ ->
                val x = radius + step * i
                when {
                    i < index -> drawCircle(Securo.GreenDim, radius * 0.7f, Offset(x, y))
                    i == index -> {
                        drawCircle(Securo.GreenGlow, radius * 1.6f, Offset(x, y))
                        drawCircle(Securo.Green, radius, Offset(x, y))
                    }

                    else -> drawCircle(Securo.Track, radius * 0.7f, Offset(x, y))
                }
            }
        }

        Spacer(Modifier.height(6.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(stageLabel(stage)),
                style = Securo.Unit,
                color = Securo.Green,
            )
            Text(
                text = String.format(Locale.US, "%d/%d", index + 1, TIMELINE_STAGES.size),
                style = Securo.Unit,
                color = Securo.TextSecondary,
            )
        }
    }
}

private fun stageLabel(stage: AutoModeStage): Int = when (stage) {
    AutoModeStage.MEASURING -> R.string.stage_measuring
    AutoModeStage.ROUTING -> R.string.stage_routing
    AutoModeStage.FETCHING -> R.string.stage_fetching
    AutoModeStage.IMPORTING -> R.string.stage_importing
    AutoModeStage.PROBING -> R.string.stage_probing
    AutoModeStage.TUNNELING -> R.string.stage_tunneling
    AutoModeStage.MEASURING_SERVERS -> R.string.stage_measuring_servers
    AutoModeStage.DONE -> R.string.stage_done
}

/** `m:ss`, and never `0:00` while the run is still going. */
fun formatCountdown(seconds: Long): String {
    val safe = seconds.coerceAtLeast(1)
    return String.format(Locale.US, "%d:%02d", safe / 60, safe % 60)
}
