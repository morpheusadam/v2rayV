package com.v2ray.ang.ui.dashboard

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.v2ray.ang.R
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * A dark rounded panel. Everything on the dashboard sits in one of these.
 */
@Composable
fun SecuroCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(Securo.CardRadius))
            .background(Securo.Card)
            .border(1.dp, Securo.CardBorder, RoundedCornerShape(Securo.CardRadius))
            .padding(18.dp)
    ) {
        content()
    }
}

/**
 * A pill that reads as something you press.
 *
 * The dashboard is not a Material surface, so it has no Material buttons; the first
 * attempt at "next connection" was a bare `TextButton`, which on a dark panel full of
 * labels looked like one more label. A press target has to announce itself — a filled
 * ground, a border and a shape nothing else on the screen has.
 *
 * @param subdued renders it as present but inert, for when pressing it would do nothing.
 */
@Composable
fun SecuroPillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Color = Securo.Green,
    enabled: Boolean = true,
    subdued: Boolean = false,
) {
    val tint = if (enabled && !subdued) accent else Securo.TextSecondary
    val shape = RoundedCornerShape(percent = 50)

    Box(
        modifier = modifier
            .clip(shape)
            .background(tint.copy(alpha = if (enabled && !subdued) 0.14f else 0.06f))
            .border(1.dp, tint.copy(alpha = if (enabled && !subdued) 0.55f else 0.25f), shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text.uppercase(), style = Securo.Label, color = tint)
    }
}

@Composable
fun SecuroLabel(text: String, color: Color = Securo.TextSecondary, modifier: Modifier = Modifier) {
    Text(text = text.uppercase(), style = Securo.Label, color = color, modifier = modifier)
}

/**
 * The power control: a ring of radial ticks around a round button.
 *
 * The ring is a gauge as well as decoration — [progress] fills it clockwise, which is what
 * carries "connecting" as motion rather than as a word.
 *
 * 🔴 It used to say "colour alone separates the states", and it had two: red, and green
 * once the tunnel was up. Pressing it started a run that takes minutes, and for those
 * minutes the button was **pixel-identical to the idle button** — same red, same power
 * glyph, and an unfilled ring, because the fraction it was given came from a `connecting`
 * flag that nothing in the app ever set to true. So the one control on the screen gave no
 * sign it had been pressed, on the one path where the wait is long enough to need it.
 *
 * There are three states, not two, and each one answers a different question:
 *
 * | state | what a press means | glyph | accent |
 * |---|---|---|---|
 * | idle | connect | power | red |
 * | [working] | stop what is running | stop | violet |
 * | [connected] | disconnect | stop | green |
 *
 * The glyph carries it as well as the colour, deliberately: colour alone excludes anyone
 * who cannot separate red from green, and this is the only control on the screen.
 *
 * While [working] the ring sweeps rather than sitting full. A static full ring is
 * indistinguishable from a finished one, and the point of the motion is to say the app is
 * still doing something — the run has no completion fraction to show honestly, so the ring
 * must not imply one.
 */
@Composable
fun PowerRing(
    connected: Boolean,
    working: Boolean,
    progress: Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = when {
        connected -> Securo.Green
        working -> Securo.Violet
        else -> Securo.Red
    }
    val dim = when {
        connected -> Securo.GreenDim
        working -> Securo.VioletDim
        else -> Securo.RedDim
    }
    val glow = when {
        connected -> Securo.GreenGlow
        working -> Securo.VioletGlow
        else -> Securo.RedGlow
    }

    // Connected wins over working: a run that carries on filling the reserve behind a live
    // tunnel must not repaint the button as though the connection were still being looked
    // for. Once the user is through, the button's job is to say so.
    val sweeping = working && !connected

    // 🔴 The transition is created inside the branch, not above it.
    //
    // rememberInfiniteTransition starts an animation the moment it is composed and runs a
    // frame loop for as long as it exists, whether or not anybody reads the value. Built
    // unconditionally it did two things wrong: it woke the main thread every frame for the
    // whole time the dashboard was open in *any* state, and — the visible one — it had
    // already been running for however long the user had been looking at the screen, so
    // the first frame of a press jumped to whatever phase it happened to be at and then
    // snapped back. On the one interaction this button exists to make legible.
    //
    // Conditional composition is fine here: each branch is its own group, and leaving one
    // disposes what it remembered, which is exactly the wanted lifetime.
    val animatedProgress = if (sweeping) {
        val sweep = rememberInfiniteTransition(label = "power-ring-sweep")
        val swept by sweep.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1400, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "power-ring-sweep-fraction",
        )
        swept
    } else {
        val settled by animateFloatAsState(
            targetValue = progress.coerceIn(0f, 1f),
            animationSpec = tween(durationMillis = 600),
            label = "power-ring",
        )
        settled
    }

    Box(modifier = modifier.clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val radius = min(size.width, size.height) / 2f
            drawTickRing(
                centre = center,
                outer = radius * 0.98f,
                inner = radius * 0.74f,
                ticks = 40,
                filled = animatedProgress,
                on = accent,
                off = Securo.Track,
            )
            // A soft bloom under the button, which is what makes the ring read as lit
            // rather than printed.
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(glow, Color.Transparent),
                    center = center,
                    radius = radius * 0.72f,
                ),
                radius = radius * 0.72f,
            )
            drawCircle(color = Securo.Card, radius = radius * 0.52f)
            drawCircle(
                color = dim,
                radius = radius * 0.52f,
                style = Stroke(width = 2.dp.toPx()),
            )
        }
        // Stop, not power, whenever a press would stop something — a run in flight or a
        // live tunnel. The glyph is the part of the change that survives a colourblind
        // user and a glance too short to register a hue.
        val stops = connected || working
        Icon(
            painter = painterResource(
                if (stops) R.drawable.ic_stop_24dp else R.drawable.ic_power_settings_24dp
            ),
            // Spoken, so it has to name what the press actually does rather than what the
            // button generally is. MainActivity.handleFabAction tests the run FIRST, so
            // while one is in flight a press stops the scan and leaves the tunnel alone —
            // even when the tunnel is up. Announcing "stop service" there is not vague, it
            // is wrong, and a screen-reader user has nothing else to go on.
            contentDescription = stringResource(
                when {
                    working -> R.string.acc_stop_scan
                    connected -> R.string.acc_stop
                    else -> R.string.acc_start
                }
            ),
            tint = accent,
            modifier = Modifier.size(34.dp),
        )
    }
}

/**
 * A circular speed gauge: the same tick ring, opened at the bottom, with the reading in
 * the middle.
 */
@Composable
fun SpeedGauge(
    value: Float,
    max: Float,
    label: String,
    unit: String,
    accent: Color = Securo.Green,
    modifier: Modifier = Modifier,
) {
    val fraction = if (max <= 0f) 0f else (value / max).coerceIn(0f, 1f)
    val animated by animateFloatAsState(
        targetValue = fraction,
        animationSpec = tween(durationMillis = 500),
        label = "speed-gauge",
    )

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val radius = min(size.width, size.height) / 2f
            // Opened 90 degrees at the bottom so the gap reads as a scale with a start and
            // an end, rather than a closed loop with no zero.
            rotate(degrees = 135f) {
                drawTickRing(
                    centre = center,
                    outer = radius * 0.98f,
                    inner = radius * 0.76f,
                    ticks = 34,
                    filled = animated,
                    on = accent,
                    off = Securo.Track,
                    sweepDegrees = 270f,
                )
            }
            drawCircle(color = Securo.Card, radius = radius * 0.6f)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = label,
                style = Securo.Readout,
                color = Securo.TextPrimary,
                textAlign = TextAlign.Center,
            )
            Text(text = unit.uppercase(), style = Securo.Unit, color = Securo.TextSecondary)
        }
    }
}

/**
 * Draws [ticks] radial bars around [centre], the first [filled] fraction of them lit.
 *
 * Discrete bars rather than a smooth arc: a segmented meter reads as an instrument, and it
 * also quantises the value, which stops a jittering measurement from looking precise.
 */
private fun DrawScope.drawTickRing(
    centre: Offset,
    outer: Float,
    inner: Float,
    ticks: Int,
    filled: Float,
    on: Color,
    off: Color,
    sweepDegrees: Float = 360f,
) {
    val lit = (ticks * filled).toInt()
    val step = sweepDegrees / ticks
    val strokeWidth = (outer - inner) * 0.42f

    for (i in 0 until ticks) {
        val angle = Math.toRadians((i * step - 90.0))
        val cosA = cos(angle).toFloat()
        val sinA = sin(angle).toFloat()
        drawLine(
            color = if (i < lit) on else off,
            start = Offset(centre.x + cosA * inner, centre.y + sinA * inner),
            end = Offset(centre.x + cosA * outer, centre.y + sinA * outer),
            strokeWidth = strokeWidth,
        )
    }
}

/**
 * The horizontal segmented meter under each figure. Same idea as the ring, unrolled.
 */
@Composable
fun SegmentedBar(
    fraction: Float,
    accent: Color,
    modifier: Modifier = Modifier,
    segments: Int = 22,
) {
    val animated by animateFloatAsState(
        targetValue = fraction.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 500),
        label = "segmented-bar",
    )

    Canvas(modifier = modifier.height(10.dp)) {
        val gap = size.width * 0.012f
        val segWidth = (size.width - gap * (segments - 1)) / segments
        val lit = (segments * animated).toInt()
        for (i in 0 until segments) {
            drawRect(
                color = if (i < lit) accent else Securo.Track,
                topLeft = Offset(i * (segWidth + gap), 0f),
                size = Size(segWidth, size.height),
            )
        }
    }
}

/**
 * A rolling trace of recent samples.
 *
 * Scaled to the window's own maximum rather than an absolute ceiling, so the shape of what
 * just happened stays readable whether the link is doing 40 MB/s or 40 KB/s. That means
 * the height is not comparable between the two cards — the figure underneath is what to
 * compare, and the trace is only there for shape.
 */
@Composable
fun Sparkline(
    samples: List<Long>,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        if (samples.size < 2) return@Canvas

        val peak = (samples.maxOrNull() ?: 0L).coerceAtLeast(1L).toFloat()
        val stepX = size.width / (samples.size - 1).toFloat()

        val points = samples.mapIndexed { index, value ->
            Offset(
                x = index * stepX,
                y = size.height - (value / peak) * size.height * 0.92f,
            )
        }

        // Fill first, so the line sits on top of its own shading.
        val fill = androidx.compose.ui.graphics.Path().apply {
            moveTo(0f, size.height)
            points.forEach { lineTo(it.x, it.y) }
            lineTo(size.width, size.height)
            close()
        }
        drawPath(
            path = fill,
            brush = Brush.verticalGradient(
                colors = listOf(accent.copy(alpha = 0.35f), Color.Transparent),
            ),
        )

        for (i in 0 until points.size - 1) {
            drawLine(
                color = accent,
                start = points[i],
                end = points[i + 1],
                strokeWidth = 2.dp.toPx(),
            )
        }
    }
}

/**
 * The two-up cards: a label, a trace or nothing, a big figure with its unit, and a meter.
 */
@Composable
fun MetricCard(
    label: String,
    value: String,
    unit: String,
    accent: Color,
    fraction: Float,
    modifier: Modifier = Modifier,
    samples: List<Long>? = null,
) {
    SecuroCard(modifier = modifier) {
        Column(modifier = Modifier.fillMaxWidth()) {
            SecuroLabel(label)

            // Two points is the minimum that draws a line. Below that the card closes the
            // gap rather than reserving a block of empty space for a chart that is not
            // there yet — an idle tunnel should look calm, not broken.
            if (samples != null && samples.size >= 2) {
                Sparkline(
                    samples = samples,
                    accent = accent,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .padding(top = 10.dp, bottom = 6.dp),
                )
            }

            Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 10.dp)) {
                Text(text = value, style = Securo.ReadoutSmall, color = Securo.TextPrimary)
                Text(
                    text = unit.uppercase(),
                    style = Securo.Unit,
                    color = Securo.TextSecondary,
                    modifier = Modifier.padding(start = 6.dp, bottom = 4.dp),
                )
            }

            SegmentedBar(
                fraction = fraction,
                accent = accent,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
            )
        }
    }
}
