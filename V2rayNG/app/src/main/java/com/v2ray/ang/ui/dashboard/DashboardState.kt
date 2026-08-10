package com.v2ray.ang.ui.dashboard

import java.util.Locale

/**
 * Everything the dashboard draws.
 *
 * [downSamples] and [upSamples] are the rolling window behind the traces; they are held
 * here rather than in the composable so a trip through the server list does not wipe the
 * history the moment the screen comes back.
 */
data class DashboardState(
    val connected: Boolean = false,
    val connecting: Boolean = false,
    val serverName: String = "",
    /** ISO country code measured through the tunnel, or null before the lookup lands. */
    val country: String? = null,
    val ipAddress: String? = null,
    val elapsedMillis: Long = 0,
    val downSpeed: Long = 0,
    val upSpeed: Long = 0,
    val downTotal: Long = 0,
    val upTotal: Long = 0,
    val downSamples: List<Long> = emptyList(),
    val upSamples: List<Long> = emptyList(),
) {
    /** Peak seen this session, which is what the meters are scaled against. */
    val peakDown: Long get() = downSamples.maxOrNull() ?: 0L
    val peakUp: Long get() = upSamples.maxOrNull() ?: 0L
}

/** Number of ticks kept for the traces. At one per second this is a two-minute window. */
const val SAMPLE_WINDOW = 120

private const val MB = 1024.0 * 1024.0
private const val GB = MB * 1024.0

/** Speed in MB/s, to one decimal — the unit the mockups read in. */
fun formatSpeedValue(bytesPerSecond: Long): String =
    String.format(Locale.US, "%.1f", bytesPerSecond / MB)

/**
 * Session volume, switching unit at a gigabyte so the figure keeps its precision instead
 * of running to five digits.
 */
fun formatVolumeValue(bytes: Long): String =
    if (bytes >= GB) {
        String.format(Locale.US, "%.2f", bytes / GB)
    } else {
        String.format(Locale.US, "%.1f", bytes / MB)
    }

fun volumeUnit(bytes: Long): String = if (bytes >= GB) "GB" else "MB"

/** Elapsed time as hh:mm:ss, which is what the status card shows. */
fun formatElapsed(millis: Long): String {
    val totalSeconds = (millis / 1000).coerceAtLeast(0)
    return String.format(
        Locale.US,
        "%02d:%02d:%02d",
        totalSeconds / 3600,
        (totalSeconds % 3600) / 60,
        totalSeconds % 60,
    )
}

/**
 * Turns a two-letter country code into its flag emoji by mapping each letter onto its
 * regional indicator symbol — no table, and it covers every country rather than the
 * handful an asset folder would.
 */
fun countryFlag(code: String?): String? {
    if (code == null || code.length != 2 || !code.all { it.isLetter() }) {
        return null
    }
    val upper = code.uppercase()
    val first = 0x1F1E6 + (upper[0] - 'A')
    val second = 0x1F1E6 + (upper[1] - 'A')
    return String(Character.toChars(first)) + String(Character.toChars(second))
}
