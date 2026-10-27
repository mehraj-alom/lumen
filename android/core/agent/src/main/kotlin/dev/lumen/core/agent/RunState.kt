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
    val plan: List<PlanNode> = emptyList(),
)

/** One step of the plan, as the console draws it in the plan graph. */
public data class PlanNode(
    val stepId: String,
    val intent: String,
    val state: String,
    val active: Boolean = false,
    val riskClass: String = "NONE",
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

/**
 * The indicator mode named [value], or [IndicatorMode.EDGE_GLOW].
 *
 * The name arrives over the wire from a settings screen, so it is input rather
 * than a constant. Falling back to the glow is the safe direction: an
 * unrecognised word cannot turn the indicator off, and `PRD.md` §9 makes the
 * indicator the thing that tells the user the agent is working.
 */
public fun String?.toIndicatorMode(): IndicatorMode = when (this?.uppercase()) {
    "STATUS_DOT" -> IndicatorMode.STATUS_DOT
    "OFF" -> IndicatorMode.OFF
    else -> IndicatorMode.EDGE_GLOW
}
