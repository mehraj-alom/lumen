package dev.lumen.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The phase of a run. */
@Serializable
public enum class Phase {
    @SerialName("idle")
    IDLE,

    @SerialName("planning")
    PLANNING,

    @SerialName("running")
    RUNNING,

    @SerialName("waiting_approval")
    WAITING_APPROVAL,

    @SerialName("paused")
    PAUSED,

    @SerialName("done")
    DONE,

    @SerialName("failed")
    FAILED,

    @SerialName("aborted")
    ABORTED,
    ;

    /** Whether the run is still able to make progress. */
    public val isTerminal: Boolean
        get() = this == DONE || this == FAILED || this == ABORTED
}

/** The state of a single step. */
@Serializable
public enum class StepState {
    @SerialName("planned")
    PLANNED,

    @SerialName("blocked")
    BLOCKED,

    @SerialName("awaiting_approval")
    AWAITING_APPROVAL,

    @SerialName("running")
    RUNNING,

    @SerialName("observed")
    OBSERVED,

    @SerialName("critique")
    CRITIQUE,

    @SerialName("complete")
    COMPLETE,

    @SerialName("failed")
    FAILED,

    @SerialName("denied")
    DENIED,

    @SerialName("skipped")
    SKIPPED,

    @SerialName("replan")
    REPLAN,

    @SerialName("aborted")
    ABORTED,
    ;

    /** Whether no further transition is possible from this state. */
    public val isTerminal: Boolean
        get() = this == COMPLETE || this == FAILED || this == DENIED || this == SKIPPED || this == ABORTED
}

/** What the critic concluded about a step. */
@Serializable
public enum class Verdict {
    /** The observation matched the expectation. Continue with the next step. */
    @SerialName("continue")
    CONTINUE,

    /** The observation did not match. The plan must be revised. */
    @SerialName("replan")
    REPLAN,

    /** The goal is satisfied. */
    @SerialName("complete")
    COMPLETE,

    /** The step failed and the plan cannot recover. */
    @SerialName("fail")
    FAIL,
}

/** Who produced a reason. */
@Serializable
public enum class ReasonSource {
    @SerialName("planner")
    PLANNER,

    @SerialName("critic")
    CRITIC,

    @SerialName("policy")
    POLICY,

    @SerialName("user")
    USER,

    @SerialName("system")
    SYSTEM,
}

/**
 * An auditable explanation of why something happened.
 *
 * A reason is mandatory on every step and every decision (invariant I9). The
 * type refuses blank text so that an empty reason is a compile-and-run-time
 * failure rather than a silent omission in a trace.
 */
@Serializable
public data class Reason(val source: ReasonSource, val text: String) {
    init {
        require(text.isNotBlank()) { "a reason must not be blank (invariant I9)" }
    }

    public companion object {
        /** A reason produced by the planner. */
        public fun planner(text: String): Reason = Reason(ReasonSource.PLANNER, text)

        /** A reason produced by the critic. */
        public fun critic(text: String): Reason = Reason(ReasonSource.CRITIC, text)

        /** A reason produced by the policy engine. */
        public fun policy(text: String): Reason = Reason(ReasonSource.POLICY, text)

        /** A reason produced by the user. */
        public fun user(text: String): Reason = Reason(ReasonSource.USER, text)

        /** A reason produced by the system itself. */
        public fun system(text: String): Reason = Reason(ReasonSource.SYSTEM, text)
    }
}
