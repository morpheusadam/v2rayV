package com.v2ray.ang.ui.main

sealed class MainServiceEvent {
    data object StateRunning : MainServiceEvent()
    data object StateNotRunning : MainServiceEvent()
    data object StateStartSuccess : MainServiceEvent()
    data class StateStartFailure(val errorMessage: String) : MainServiceEvent()
    data object StateStopSuccess : MainServiceEvent()
    data class MeasureDelaySuccess(val content: String) : MainServiceEvent()
    data object MeasureConfigSuccess : MainServiceEvent()
    data class MeasureConfigNotify(val progress: String) : MainServiceEvent()
    data class MeasureConfigFinish(val finishedCount: String?) : MainServiceEvent()
    data class AutoModeProgress(
        val running: Boolean,
        val message: String,
        val remainingMillis: Long,
        val stage: String,
    ) : MainServiceEvent()
    data class AutoModeFinish(val message: String) : MainServiceEvent()

    /** A run has selected a server that clears the bar, mid-run. */
    data class AutoModeReady(val guid: String) : MainServiceEvent()

    /** Live throughput from a measurement in flight. */
    data class AutoModeSpeed(val mbps: Double, val baseline: Boolean) : MainServiceEvent()
    data class TrafficStats(
        val upSpeed: Long,
        val downSpeed: Long,
        val upTotal: Long,
        val downTotal: Long,
        val elapsedMillis: Long,
    ) : MainServiceEvent()
}
