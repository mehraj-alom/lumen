package dev.lumen.core.policy

import dev.lumen.core.model.Action
import dev.lumen.core.model.ApprovalId
import dev.lumen.core.model.ApprovalRequest
import dev.lumen.core.model.Observation
import dev.lumen.core.model.Reason
import dev.lumen.core.model.RiskClass
import dev.lumen.core.model.RunId
import dev.lumen.core.model.StepId

/**
 * What the policy engine was asked about.
 *
 * The planner's own opinion of the risk travels with the request. It can
 * only ever make the answer stricter.
 */
public data class PolicyRequest(
    val runId: RunId,
    val stepId: StepId,
    val action: Action,
    val observation: Observation?,
    val plannerRiskClass: RiskClass = RiskClass.NONE,
    val appLabel: String = "",
)

/** The policy engine's answer. */
public sealed interface PolicyDecision {

    /** The action may run. */
    public data class Allow(val reason: Reason) : PolicyDecision

    /** The action must be approved by a human first. */
    public data class RequiresApproval(val request: ApprovalRequest) : PolicyDecision

    /** The action is refused outright. */
    public data class Deny(val reason: Reason) : PolicyDecision
}

/**
 * The policy engine.
 *
 * Deny by default, and no bypass. A step that matches no class runs; a step
 * that matches a class waits for a human; a step the engine refuses does not
 * run at all.
 */
public class PolicyEngine(
    private val classifier: DangerClassifier = DangerClassifier(),
    private val grants: GrantStore = GrantStore(),
) {

    /**
     * The decision for [request].
     *
     * The effective risk class is the **stricter** of the classifier's answer
     * and the planner's opinion. The planner may escalate a step; it may
     * never de-escalate one.
     */
    public fun decide(request: PolicyRequest, nowMs: Long): PolicyDecision {
        val detected = classifier.classify(request.action, request.observation)
        val effective = stricter(detected, request.plannerRiskClass)

        if (!effective.requiresApproval) {
            return PolicyDecision.Allow(
                Reason.policy("no irreversible action class was detected"),
            )
        }

        if (grants.isGranted(request.runId, effective, nowMs)) {
            return PolicyDecision.Allow(
                Reason.policy("${effective.label} was granted for this run"),
            )
        }

        val approval = ApprovalRequestBuilder.build(
            request = request,
            riskClass = effective,
            classifier = classifier,
        )

        return PolicyDecision.RequiresApproval(approval)
    }

    /** The grants this engine holds. */
    public fun grantStore(): GrantStore = grants

    /**
     * The stricter of two risk classes.
     *
     * The order is the severity order of `PRD.md` §6, and a class that
     * requires approval always beats one that does not.
     */
    public fun stricter(left: RiskClass, right: RiskClass): RiskClass {
        if (left.requiresApproval && !right.requiresApproval) return left
        if (right.requiresApproval && !left.requiresApproval) return right
        return if (severity(left) >= severity(right)) left else right
    }

    private fun severity(riskClass: RiskClass): Int = when (riskClass) {
        RiskClass.NONE -> 0
        RiskClass.USER_PINNED -> 1
        RiskClass.LEGAL -> 2
        RiskClass.PERMISSION -> 3
        RiskClass.ACCOUNT -> 4
        RiskClass.COMMUNICATION -> 5
        RiskClass.PUBLISH -> 6
        RiskClass.CREDENTIAL -> 7
        RiskClass.DELETE -> 8
        RiskClass.PAYMENT -> 9
        RiskClass.IRREVERSIBLE -> 10
    }
}

/**
 * The grants that let a class run without asking.
 *
 * A grant is bound to one run and one action class, and it expires. There is
 * no global "auto-approve" switch, because a global switch is how an
 * irreversible action becomes an accidental one.
 */
public class GrantStore {

    private val lock = Any()
    private val grants = mutableMapOf<GrantKey, Long>()

    /** Grants [riskClass] for [runId] until [expiresAtMs]. */
    public fun grant(runId: RunId, riskClass: RiskClass, expiresAtMs: Long): Unit =
        synchronized(lock) {
            grants[GrantKey(runId, riskClass)] = expiresAtMs
        }

    /** Whether [riskClass] is granted for [runId] at [nowMs]. */
    public fun isGranted(runId: RunId, riskClass: RiskClass, nowMs: Long): Boolean =
        synchronized(lock) {
            val expiry = grants[GrantKey(runId, riskClass)] ?: return@synchronized false
            if (nowMs >= expiry) {
                grants.remove(GrantKey(runId, riskClass))
                return@synchronized false
            }
            true
        }

    /** Removes every grant for [runId]. */
    public fun clear(runId: RunId): Unit = synchronized(lock) {
        grants.keys.removeIf { it.runId == runId }
    }

    /** The number of live grants. */
    public fun size(): Int = synchronized(lock) { grants.size }

    private data class GrantKey(val runId: RunId, val riskClass: RiskClass)
}
