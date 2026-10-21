package dev.lumen.core.brain

import dev.lumen.core.model.Action
import dev.lumen.core.model.Assertion
import dev.lumen.core.model.Goal
import dev.lumen.core.model.Observation
import dev.lumen.core.model.RiskClass
import dev.lumen.core.model.Step
import dev.lumen.core.model.Verdict

/**
 * A step the brain proposed.
 *
 * The `riskClass` is the brain's *opinion*. The policy engine decides the
 * real one, and the brain's opinion can only make it stricter.
 */
public data class PlannedStep(
    val seq: Int,
    val intent: String,
    val reason: String,
    val action: Action,
    val expectation: Assertion? = null,
    val riskClass: RiskClass = RiskClass.NONE,
)

/** The brain's answer to a planning request. */
public data class BrainReply(
    val thought: String,
    val steps: List<PlannedStep>,
    val raw: String = "",
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
) {
    /** Whether the reply contains anything actionable. */
    public val isEmpty: Boolean get() = steps.isEmpty()
}

/** The brain's judgement of a step that has run. */
public data class Critique(
    val verdict: Verdict,
    val reason: String,
    val observationSummary: String? = null,
)

/** The request to plan a goal. */
public data class PlanRequest(
    val goal: Goal,
    val deviceState: DeviceState = DeviceState(),
    val observations: List<Observation> = emptyList(),
    val remainingSteps: Int = 0,
    val promptVersion: String = "1",
    val seed: Long = 0L,
)

/** The request to revise a plan. */
public data class ReplanRequest(
    val goal: Goal,
    val failedStep: Step,
    val observation: Observation?,
    val previousPlanVersion: Int,
    val deviceState: DeviceState = DeviceState(),
    val remainingSteps: Int = 0,
    val promptVersion: String = "1",
    val seed: Long = 0L,
)

/** The request to judge a step. */
public data class CritiqueRequest(
    val goal: Goal,
    val step: Step,
    val observation: Observation?,
    val deviceState: DeviceState = DeviceState(),
    val promptVersion: String = "1",
    val seed: Long = 0L,
)

/** What the agent knows about the device it is driving. */
public data class DeviceState(
    val foregroundPackage: String? = null,
    val isScreenOn: Boolean = true,
    val isKeyguardLocked: Boolean = false,
    val batteryPercent: Int = 100,
    val isCharging: Boolean = true,
    val allowedPackages: Set<String> = emptySet(),
    val thermalStatus: String = "NONE",
)

/**
 * The reasoning port.
 *
 * Every implementation — local, cloud, or a fake in a test — answers the
 * same three questions: what is the plan, what is the revised plan, and was
 * that step a success.
 */
public interface BrainPort {

    /** Produces a plan for [request]. */
    public suspend fun plan(request: PlanRequest): BrainReply

    /** Revises the plan after a step failed. */
    public suspend fun replan(request: ReplanRequest): BrainReply

    /** Judges the outcome of a step. */
    public suspend fun critique(request: CritiqueRequest): Critique

    /** Which brain this is. */
    public val route: BrainRoute
}

/** Which brain served a request. */
public enum class BrainRoute {
    LOCAL,
    CLOUD,
}
