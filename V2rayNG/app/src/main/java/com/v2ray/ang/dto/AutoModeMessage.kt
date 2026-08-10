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
) : Serializable
