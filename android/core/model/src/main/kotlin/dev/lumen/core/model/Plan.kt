package dev.lumen.core.model

import kotlinx.serialization.Serializable

/**
 * A versioned plan.
 *
 * The version increases on every revision. A revision never mutates the steps
 * of an earlier version; the whole plan is replaced and the previous version
 * stays in the trace, so a run can be replayed exactly.
 */
@Serializable
public data class Plan(
    val runId: RunId,
    val version: Int,
    val steps: List<Step>,
    val createdAtMs: Long = 0L,
    val summary: String? = null,
) {
    init {
        require(version >= 1) { "a plan version starts at 1" }
    }

    /** The step with [id], or null. */
    public fun step(id: StepId): Step? = steps.firstOrNull { it.id == id }

    /** The steps that are ready to run, in plan order. */
    public fun readySteps(): List<Step> = steps.filter { step ->
        step.state == StepState.PLANNED &&
            step.dependsOn.all { dependency ->
                this.step(dependency)?.state == StepState.COMPLETE
            }
    }
}
