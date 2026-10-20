package dev.lumen.core.model

import kotlinx.serialization.Serializable

/**
 * One step of a plan.
 *
 * A step is the unit of progress, of approval, and of audit. It always
 * carries a reason (invariant I9) and always carries an action, so that the
 * trace of a run can be replayed step by step.
 */
@Serializable
public data class Step(
    val id: StepId,
    val planVersion: Int,
    val phase: String,
    val intent: String,
    val reason: Reason,
    val action: Action,
    val expectation: Assertion? = null,
    val precondition: Assertion? = null,
    val riskClass: RiskClass = RiskClass.NONE,
    val state: StepState = StepState.PLANNED,
    val dependsOn: List<StepId> = emptyList(),
    val seed: Long? = null,
    val promptVersion: String? = null,
    val createdAtMs: Long = 0L,
    val updatedAtMs: Long = 0L,
) {
    init {
        require(intent.isNotBlank()) { "a step must state its intent" }
    }

    /** Whether this step changes the device state. */
    public val mutating: Boolean
        get() = action.mutating
}
