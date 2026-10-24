package dev.lumen.agent.ipc

import dev.lumen.core.brain.BrainConfig
import dev.lumen.core.model.BrainPin
import dev.lumen.core.model.Goal
import kotlinx.serialization.Serializable

/**
 * The payloads of the agent IPC, in their wire form.
 *
 * The contract in `API_SPEC.md` §2 is written as structured payloads. These
 * are the same payloads, serialised, so that the AIDL interface does not
 * have to carry a Parcelable for every one of them.
 */

/** The state of a run. */
@Serializable
public data class AgentStatePayload(
    val runId: String,
    val phase: String,
    val stepIndex: Int,
    val stepCount: Int,
    val currentIntent: String? = null,
    val currentReason: String? = null,
    val planVersion: Int = 1,
    val indicatorMode: String = "EDGE_GLOW",
    val indicatorState: String = "IDLE",
    val brainRoute: String = "AUTO",
    val takeoverActive: Boolean = false,
    val lastError: ErrorPayload? = null,
)

/** A failure, in its wire form. */
@Serializable
public data class ErrorPayload(val code: String, val message: String, val detail: Map<String, String> = emptyMap())

/** One step event, in its wire form. */
@Serializable
public data class StepEventPayload(
    val type: String,
    val runId: String,
    val seq: Long,
    val tsMs: Long,
    val stepId: String? = null,
    val intent: String? = null,
    val reason: String? = null,
    val riskClass: String? = null,
    val app: String? = null,
    val durationMs: Long? = null,
    val text: String? = null,
)

/** The budget of a run, in its wire form. */
@Serializable
public data class BudgetPayload(
    val ramUsedMiB: Int,
    val ramLimitMiB: Int,
    val ramByCategory: Map<String, Int> = emptyMap(),
    val storageUsedMiB: Long,
    val storageLimitMiB: Long,
    val storageByBucket: Map<String, Long> = emptyMap(),
    val stepsUsed: Int,
    val stepsSoftLimit: Int,
    val stepsHardLimit: Int,
    val elapsedMs: Long,
    val wallClockLimitMs: Long,
    val costMicros: Long,
    val costLimitMicros: Long,
    val thermalStatus: String = "NONE",
    val batteryPercent: Int = 100,
)

/** An approval request, in its wire form. */
@Serializable
public data class ApprovalPayload(
    val runId: String,
    val approvalId: String,
    val stepId: String,
    val riskClass: String,
    val title: String,
    val detail: String,
    val app: String,
    val appLabel: String,
    val consequences: List<String> = emptyList(),
    val actionPreview: PreviewPayload,
    val expiresAtMs: Long,
    val leaseSeconds: Int,
)

/** The redacted preview of an action, in its wire form. */
@Serializable
public data class PreviewPayload(val kind: String, val label: String? = null, val text: String? = null, val target: String? = null)

/**
 * The brain one run is told to use, in its wire form.
 *
 * The configuration crosses the IPC with the goal rather than being read from
 * a shared file, because the two processes must not hold two copies of the same
 * settings, and because a run has to be auditable against the consent it was
 * started with: [screenTextEgress] is the `I11` answer for that run alone.
 */
@Serializable
public data class BrainConfigPayload(
    val baseUrl: String = "",
    val apiKey: String = "",
    val modelId: String = "",
    val screenTextEgress: Boolean = false,
)

/** The default wall-clock ceiling of a run: 45 minutes, per `AGENTS.md` §2. */
private const val DEFAULT_WALL_CLOCK_LIMIT_MS: Long = 45L * 60L * 1000L

/** The goal a run is started from, in its wire form. */
@Serializable
public data class GoalPayload(
    val runId: String,
    val text: String,
    val stepsSoftLimit: Int = 150,
    val stepsHardLimit: Int = 400,
    val wallClockLimitMs: Long = DEFAULT_WALL_CLOCK_LIMIT_MS,
    val costLimitMicros: Long = 0L,
    val allowedPackages: Set<String> = emptySet(),
    val brainPin: String = "AUTO",
    val brain: BrainConfigPayload = BrainConfigPayload(),
)

/** The helpers that turn the model into the wire form. */
public object Payloads {

    /** The wire form of [state]. */
    public fun state(state: dev.lumen.core.agent.RunState): AgentStatePayload = AgentStatePayload(
        runId = state.runId,
        phase = state.phase.name,
        stepIndex = state.stepIndex,
        stepCount = state.stepCount,
        currentIntent = state.currentIntent,
        currentReason = state.currentReason,
        planVersion = state.planVersion,
        indicatorMode = state.indicatorMode.name,
        indicatorState = state.indicatorState.name,
        brainRoute = state.brainRoute,
        takeoverActive = state.takeoverActive,
        lastError = state.lastError?.let {
            ErrorPayload(it.code.name.lowercase(), it.message)
        },
    )

    /** The wire form of a goal. */
    public fun goal(goal: Goal): GoalPayload = GoalPayload(
        runId = goal.id.value,
        text = goal.text,
        stepsSoftLimit = goal.constraints.stepsSoftLimit,
        stepsHardLimit = goal.constraints.stepsHardLimit,
        wallClockLimitMs = goal.constraints.wallClockLimitMs,
        costLimitMicros = goal.constraints.costLimitMicros,
        allowedPackages = goal.constraints.allowedPackages,
        brainPin = goal.constraints.brainPin.name,
    )

    /** The goal from its wire form. */
    public fun goal(payload: GoalPayload): Goal = Goal(
        id = dev.lumen.core.model.RunId(payload.runId),
        text = payload.text,
        constraints = dev.lumen.core.model.GoalConstraints(
            stepsSoftLimit = payload.stepsSoftLimit,
            stepsHardLimit = payload.stepsHardLimit,
            wallClockLimitMs = payload.wallClockLimitMs,
            costLimitMicros = payload.costLimitMicros,
            allowedPackages = payload.allowedPackages,
            brainPin = brainPin(payload.brainPin),
        ),
    )

    /** The brain a run arrived with: its endpoint, its model and its consent. */
    public fun brainConfig(payload: GoalPayload): BrainConfig = BrainConfig(
        baseUrl = payload.brain.baseUrl.trim(),
        apiKey = payload.brain.apiKey.trim(),
        modelId = payload.brain.modelId.trim(),
        pin = brainPin(payload.brainPin),
        screenTextEgress = payload.brain.screenTextEgress,
    )

    /** The pin a run arrived with, or `AUTO` when the wire carried a word that is not one. */
    public fun brainPin(name: String): BrainPin = BrainPin.entries.firstOrNull { it.name == name.uppercase() } ?: BrainPin.AUTO
}
