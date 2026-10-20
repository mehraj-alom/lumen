package dev.lumen.core.model

/**
 * Compares two plans for equivalence, ignoring execution state and timestamps.
 *
 * Invariant I8 requires that the same goal and the same device state produce
 * the same plan. The comparison is on the *plan*, not on the run: a step's
 * execution state and every timestamp are excluded, because those differ
 * between two executions of the same plan by definition.
 */
public object PlanEquivalence {

    /**
     * Whether [left] and [right] describe the same plan.
     *
     * Compared: the run, the version, and each step's identity, intent,
     * reason, action, expectation, precondition, risk class and dependencies.
     * Not compared: the execution state, the timestamps, and the summary.
     */
    public fun equivalent(left: Plan, right: Plan): Boolean {
        if (left.runId != right.runId) return false
        if (left.version != right.version) return false
        if (left.steps.size != right.steps.size) return false

        for (index in left.steps.indices) {
            if (!equivalent(left.steps[index], right.steps[index])) return false
        }
        return true
    }

    /** Whether two steps describe the same planned work. */
    public fun equivalent(left: Step, right: Step): Boolean {
        if (left.id != right.id) return false
        if (left.phase != right.phase) return false
        if (left.intent != right.intent) return false
        if (left.reason != right.reason) return false
        if (left.action != right.action) return false
        if (left.expectation != right.expectation) return false
        if (left.precondition != right.precondition) return false
        if (left.riskClass != right.riskClass) return false
        if (left.dependsOn != right.dependsOn) return false
        if (left.seed != right.seed) return false
        if (left.promptVersion != right.promptVersion) return false
        return true
    }

    /**
     * Throws when the two plans are not equivalent.
     *
     * The message names the first difference, because a determinism failure
     * that only says "not equal" is not actionable.
     */
    public fun assertEquivalent(left: Plan, right: Plan) {
        require(left.runId == right.runId) {
            "the plans belong to different runs: ${left.runId} and ${right.runId}"
        }
        require(left.version == right.version) {
            "the plan versions differ: ${left.version} and ${right.version}"
        }
        require(left.steps.size == right.steps.size) {
            "the plans have a different number of steps: ${left.steps.size} and ${right.steps.size}"
        }
        for (index in left.steps.indices) {
            val leftStep = left.steps[index]
            val rightStep = right.steps[index]
            require(equivalent(leftStep, rightStep)) {
                "the plans differ at step $index (${leftStep.id}): " +
                    "intent '${leftStep.intent}' vs '${rightStep.intent}'"
            }
        }
    }
}
