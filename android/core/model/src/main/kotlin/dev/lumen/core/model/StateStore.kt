package dev.lumen.core.model

import kotlinx.serialization.Serializable

/**
 * The persistence port.
 *
 * The port and its DTOs live here, in the pure module, and the Room
 * implementation lives in `:core:store`, which is an Android module because
 * Room is an Android-only artifact (ADR 0002). The agent loop depends on
 * this interface and never on Room, so it stays testable on the JVM with a
 * fake store.
 */
public interface StateStore {

    /** Creates or replaces the plan of a run. */
    public suspend fun savePlan(plan: Plan)

    /** The current plan of a run, or null. */
    public suspend fun loadPlan(runId: RunId): Plan?

    /** Every plan version of a run, oldest first. */
    public suspend fun loadPlanHistory(runId: RunId): List<Plan>

    /** Inserts or updates a step. */
    public suspend fun saveStep(runId: RunId, step: Step)

    /** The steps of a run at a plan version. */
    public suspend fun loadSteps(runId: RunId, planVersion: Int): List<Step>

    /** The step with [stepId], or null. */
    public suspend fun loadStep(stepId: StepId): Step?

    /** Appends an observation. */
    public suspend fun appendObservation(runId: RunId, stepId: StepId, observation: Observation)

    /** The observations of a run, oldest first. */
    public suspend fun loadObservations(runId: RunId): List<Observation>

    /** Creates or updates a pending approval. */
    public suspend fun saveApproval(approval: ApprovalRecord)

    /** The approval with [approvalId], or null. */
    public suspend fun loadApproval(approvalId: ApprovalId): ApprovalRecord?

    /** The approvals of a run that are still waiting for a decision. */
    public suspend fun loadPendingApprovals(runId: RunId): List<ApprovalRecord>

    /** Records a budget movement. */
    public suspend fun appendBudgetEntry(entry: BudgetEntry)

    /** The budget ledger of a run. */
    public suspend fun loadBudgetLedger(runId: RunId): List<BudgetEntry>

    /** Reads a setting. */
    public suspend fun loadSetting(key: String): String?

    /** Writes a setting. */
    public suspend fun saveSetting(key: String, value: String)
}

/**
 * A pending approval, as it is presented to the person who has to decide.
 *
 * The request answers three questions without the user having to think: what
 * is about to happen, where, and what cannot be undone. The preview is
 * redacted — it shows the shape of the action, not the whole of the content.
 */
@Serializable
public data class ApprovalRequest(
    val runId: RunId,
    val approvalId: ApprovalId,
    val stepId: StepId,
    val riskClass: RiskClass,
    val title: String,
    val detail: String,
    val app: String,
    val appLabel: String,
    val consequences: List<String>,
    val actionPreview: ActionPreview,
    val expiresAtMs: Long,
    val leaseSeconds: Int,
)

/** A durable record of a pending or resolved approval. */
public data class ApprovalRecord(
    val id: ApprovalId,
    val runId: RunId,
    val stepId: StepId,
    val riskClass: RiskClass,
    val title: String,
    val detail: String,
    val app: String,
    val appLabel: String,
    val consequences: List<String>,
    val actionPreview: ActionPreview,
    val state: ApprovalState,
    val createdAtMs: Long,
    val leaseExpiresAtMs: Long,
    val resolvedAtMs: Long? = null,
    val answerText: String? = null,
)

/** The state of an approval. */
public enum class ApprovalState {
    /** Waiting for a decision. */
    PENDING,

    /** A human approved it. */
    APPROVED,

    /** A human refused it. */
    DENIED,

    /** Nobody answered and the lease expired. */
    EXPIRED,
}

/** A human-readable projection of the action an approval covers. */
@Serializable
public data class ActionPreview(val kind: String, val label: String? = null, val text: String? = null, val target: String? = null)

/** One movement in the budget ledger. */
public data class BudgetEntry(
    val runId: RunId,
    val category: String,
    val kind: BudgetEntryKind,
    val amountBytes: Long,
    val createdAtMs: Long,
)

/** The kinds of budget movement. */
public enum class BudgetEntryKind {
    RESERVE,
    RELEASE,
    WRITE,
    DELETE,
    EVICT,
}
