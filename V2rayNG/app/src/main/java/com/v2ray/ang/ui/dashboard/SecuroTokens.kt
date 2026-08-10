package com.v2ray.ang.ui.dashboard

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The dashboard's palette and type.
 *
 * Kept apart from the app's Material theme on purpose: this screen is a fixed dark
 * instrument panel, not a surface that follows the system light/dark setting. Every colour
 * here is absolute, so the screen looks the same whichever theme the rest of the app is in.
 */
object Securo {
    /** Page behind everything. Not pure black — cards need something to sit against. */
    val Background = Color(0xFF050705)

    /** Card fill. Barely lifted off the background; the border does the separating. */
    val Card = Color(0xFF101410)
    val CardBorder = Color(0x1AFFFFFF)

    /** Down / connected / healthy. */
    val Green = Color(0xFF2FE39B)
    val GreenDim = Color(0xFF1A7A55)
    val GreenGlow = Color(0x662FE39B)

    /** Up. The second channel needs to be distinguishable at a glance, not just by label. */
    val Violet = Color(0xFF7C5CFF)
    val VioletDim = Color(0xFF3D2E7A)

    /** Disconnected. */
    val Red = Color(0xFFFF3B5C)
    val RedDim = Color(0xFF7A1C2C)
    val RedGlow = Color(0x66FF3B5C)

    val TextPrimary = Color(0xFFF2F5F2)
    val TextSecondary = Color(0xFF8A938C)

    /** Unfilled track on every gauge and meter. */
    val Track = Color(0xFF23281F).copy(alpha = 0.9f)

    val CardRadius = 28.dp
    val ScreenPadding = 16.dp
    val CardGap = 12.dp

    /**
     * Small caps with wide tracking. Every label in the mockups is set this way, and the
     * tracking is what stops short words like "UPLOAD" reading as cramped.
     */
    val Label = TextStyle(
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 2.sp,
    )

    /** The large readouts. Tabular so a changing number does not jitter its own width. */
    val Readout = TextStyle(
        fontSize = 34.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp,
    )

    val ReadoutSmall = TextStyle(
        fontSize = 26.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp,
    )

    val Unit = TextStyle(
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 1.sp,
    )
}
