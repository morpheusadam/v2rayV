package com.v2ray.ang.dto

import java.io.Serializable

/**
 * One tick of live traffic, sent from the core's process to the UI.
 *
 * The core's stats counters can only be read where the controller lives, and reading them
 * resets them, so there is exactly one reader and it publishes what it found rather than
 * letting each screen ask for itself.
 *
 * @param upSpeed bytes per second out, over the interval just measured
 * @param downSpeed bytes per second in, over the interval just measured
 * @param upTotal bytes out since the tunnel came up
 * @param downTotal bytes in since the tunnel came up
 * @param elapsedMillis how long the tunnel has been up
 */
data class TrafficStatsMessage(
    val upSpeed: Long = 0,
    val downSpeed: Long = 0,
    val upTotal: Long = 0,
    val downTotal: Long = 0,
    val elapsedMillis: Long = 0,
) : Serializable
