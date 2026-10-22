package dev.lumen.core.policy

import dev.lumen.core.model.ApprovalId
import dev.lumen.core.model.ApprovalRecord
import dev.lumen.core.model.ApprovalRequest
import dev.lumen.core.model.ApprovalState
import dev.lumen.core.model.LumenClock
import dev.lumen.core.model.RunId
import dev.lumen.core.model.StateStore
import dev.lumen.core.model.UlidGenerator

/** What a human decided. */
public sealed interface ApprovalDecision {

    /** The action may run. */
    public data object Approve : ApprovalDecision

    /** The action must not run. */
    public data object Deny : ApprovalDecision

    /** The action may run, with the answer fed back to the planner. */
    public data class Answer(val text: String) : ApprovalDecision
}

/**
 * The approval broker.
 *
 * An approval is **staged**, not blocking: staging writes a durable row and
 * returns. The step waits on a flow owned by the run, not on this call. That
 * is what lets the agent process keep running, and what lets an approval
 * survive the agent process being killed.
 *
 * A lease bounds every approval. When the lease runs out the approval is
 * denied, because the default is deny and silence is not consent.
 */
public class ApprovalBroker(
    private val store: StateStore,
    private val clock: LumenClock,
    private val leaseMs: Long = ApprovalRequestBuilder.DEFAULT_LEASE_MS,
    private val ids: UlidGenerator = UlidGenerator(clock = clock),
) {

    /**
     * Stages [request] and returns the identifier it was filed under.
     *
     * The request's own identifier is replaced with a fresh one, so a
     * retried staging cannot collide with an earlier approval.
     */
    public suspend fun stage(request: ApprovalRequest): ApprovalId {
        val approvalId = ApprovalId(ids.next())
        val now = clock.nowMs()

        store.saveApproval(
            ApprovalRecord(
                id = approvalId,
                runId = request.runId,
                stepId = request.stepId,
                riskClass = request.riskClass,
                title = request.title,
                detail = request.detail,
                app = request.app,
                appLabel = request.appLabel,
                consequences = request.consequences,
                actionPreview = request.actionPreview,
                state = ApprovalState.PENDING,
                createdAtMs = now,
                leaseExpiresAtMs = now + leaseMs,
            ),
        )

        return approvalId
    }

    /**
     * Records a decision.
     *
     * @return false when the approval is unknown, already decided, or its
     *   lease has run out. A stale decision never overrides a newer one.
     */
    public suspend fun resolve(approvalId: ApprovalId, decision: ApprovalDecision): Boolean {
        val record = store.loadApproval(approvalId) ?: return false
        if (record.state != ApprovalState.PENDING) return false

        val now = clock.nowMs()
        if (now >= record.leaseExpiresAtMs) {
            store.saveApproval(record.copy(state = ApprovalState.EXPIRED, resolvedAtMs = now))
            return false
        }

        val state = when (decision) {
            ApprovalDecision.Approve -> ApprovalState.APPROVED
            ApprovalDecision.Deny -> ApprovalState.DENIED
            is ApprovalDecision.Answer -> ApprovalState.APPROVED
        }

        store.saveApproval(
            record.copy(
                state = state,
                resolvedAtMs = now,
                answerText = (decision as? ApprovalDecision.Answer)?.text,
            ),
        )
        return true
    }

    /** The approvals of [runId] that are still waiting for a decision. */
    public suspend fun pending(runId: RunId): List<ApprovalRecord> = store.loadPendingApprovals(runId)

    /**
     * Denies every approval of [runId] whose lease has run out.
     *
     * @return the identifiers that were expired
     */
    public suspend fun expireOverdue(runId: RunId): List<ApprovalId> {
        val now = clock.nowMs()
        val expired = mutableListOf<ApprovalId>()

        for (record in store.loadPendingApprovals(runId)) {
            if (now < record.leaseExpiresAtMs) continue
            store.saveApproval(record.copy(state = ApprovalState.EXPIRED, resolvedAtMs = now))
            expired += record.id
        }
        return expired
    }
}
