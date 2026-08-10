package com.v2ray.ang.dto

import java.io.Serializable

/** Command sent to [com.v2ray.ang.service.AutoModeRunService]. */
data class AutoModeMessage(
    val key: Int,
) : Serializable

/**
 * One progress tick on the way back to the UI. The run lives in the core's own process,
 * so this crosses a broadcast rather than a flow.
 */
data class AutoModeProgressMessage(
    val running: Boolean,
    val message: String,
    val remainingMillis: Long,
    /** [com.v2ray.ang.automode.AutoModeStage] by name, for the timeline on the dashboard. */
    val stage: String = "",
) : Serializable

/**
 * One live throughput sample from a measurement in flight.
 *
 * [baseline] separates the two things a run measures: the user's own line, and a server
 * under test. Only the second is a candidate for the "through VPN" figure.
 */
data class AutoModeSpeedMessage(
    val mbps: Double,
    val baseline: Boolean,
) : Serializable
