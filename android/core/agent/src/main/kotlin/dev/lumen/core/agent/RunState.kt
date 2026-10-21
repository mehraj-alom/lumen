package dev.lumen.core.agent

import dev.lumen.core.model.ApiError
import dev.lumen.core.model.Phase

/**
 * The observable state of a run.
 *
 * The runtime owns this. The console renders it, and the state stream
 * pushes it. It is the whole of what a viewer needs to know.
 */
public data class RunState(
    val runId: String,
    val phase: Phase = Phase.IDLE,
    val stepIndex: Int = 0,
    val stepCount: Int = 0,
    val currentIntent: String? = null,
    val currentReason: String? = null,
    val planVersion: Int = 1,
    val indicatorMode: IndicatorMode = IndicatorMode.EDGE_GLOW,
    val indicatorState: IndicatorState = IndicatorState.IDLE,
    val brainRoute: String = "AUTO",
    val takeoverActive: Boolean = false,
    val lastError: ApiError? = null,
)

/** What the indicator is showing. */
public enum class IndicatorState {
    IDLE,
    WORKING,
    AWAITING_APPROVAL,
    BLOCKED,
    ERROR,
    ABORTING,
}

/** How the indicator is shown. */
public enum class IndicatorMode {
    EDGE_GLOW,
    STATUS_DOT,
    OFF,
}
