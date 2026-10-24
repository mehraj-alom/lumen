package dev.lumen.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A high-level intent, with the limits it must be executed under.
 *
 * The limits are part of the goal, not a side setting: a run's budget is
 * decided before the first step, so the planner can size the plan against it.
 */
@Serializable
public data class Goal(val id: RunId, val text: String, val constraints: GoalConstraints = GoalConstraints(), val createdAtMs: Long = 0L) {
    init {
        require(text.isNotBlank()) { "a goal must not be blank" }
    }
}

/** The limits a run executes under. */
@Serializable
public data class GoalConstraints(
    val stepsSoftLimit: Int = 150,
    val stepsHardLimit: Int = 400,
    val wallClockLimitMs: Long = DEFAULT_WALL_CLOCK_LIMIT_MINUTES * MILLIS_PER_MINUTE,
    val costLimitMicros: Long = 0L,
    val allowedPackages: Set<String> = emptySet(),
    val brainPin: BrainPin = BrainPin.AUTO,
) {
    init {
        require(stepsSoftLimit in 1..stepsHardLimit) {
            "the soft step limit must be within the hard limit"
        }
        require(stepsHardLimit > 0) { "the hard step limit must be positive" }
        require(wallClockLimitMs > 0) { "the wall-clock limit must be positive" }
        require(costLimitMicros >= 0) { "a cost limit cannot be negative" }
    }

    public companion object {
        /** The default run ceiling, the 45 minutes of `AGENTS.md` §2. */
        private const val DEFAULT_WALL_CLOCK_LIMIT_MINUTES: Long = 45

        /** Milliseconds in one minute. */
        private const val MILLIS_PER_MINUTE: Long = 60 * 1_000
    }
}

/** The user's brain preference. A pin always wins over the automatic route. */
@Serializable
public enum class BrainPin {
    /** Local when a model is installed and fits the budget, otherwise cloud. */
    @SerialName("auto")
    AUTO,

    /** Local only. The run pauses if no local model is available. */
    @SerialName("local")
    LOCAL,

    /** Cloud only. The run pauses if the cloud is unreachable. */
    @SerialName("cloud")
    CLOUD,
}
