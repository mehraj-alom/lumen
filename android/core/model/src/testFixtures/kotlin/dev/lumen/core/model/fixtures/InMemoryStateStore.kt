package dev.lumen.core.model.fixtures

import dev.lumen.core.model.ApprovalId
import dev.lumen.core.model.ApprovalRecord
import dev.lumen.core.model.ApprovalState
import dev.lumen.core.model.BudgetEntry
import dev.lumen.core.model.Observation
import dev.lumen.core.model.Plan
import dev.lumen.core.model.RunId
import dev.lumen.core.model.StateStore
import dev.lumen.core.model.Step
import dev.lumen.core.model.StepId

/**
 * A store in memory.
 *
 * The loop's tests need a store that behaves like the real one without a
 * database, and the real one needs a device. The behaviour is the same: a
 * plan, its steps, the observations, and the approvals.
 */
public class InMemoryStateStore : StateStore {

    /** The plans, by run and version. */
    public val plans = mutableMapOf<Pair<RunId, Int>, Plan>()

    /** The steps, by identifier. */
    public val steps = mutableMapOf<StepId, Step>()

    /** The observations, in order. */
    public val observations = mutableListOf<Pair<RunId, Observation>>()

    /** The approvals, by identifier. */
    public val approvals = mutableMapOf<ApprovalId, ApprovalRecord>()

    /** The budget ledger, in order. */
    public val ledger = mutableListOf<BudgetEntry>()

    /** The settings. */
    public val settings = mutableMapOf<String, String>()

    override suspend fun savePlan(plan: Plan) {
        plans[plan.runId to plan.version] = plan
        plan.steps.forEach { steps[it.id] = it }
    }

    override suspend fun loadPlan(runId: RunId): Plan? =
        plans.entries
            .filter { it.key.first == runId }
            .maxByOrNull { it.key.second }
            ?.value

    override suspend fun loadPlanHistory(runId: RunId): List<Plan> =
        plans.values.filter { it.runId == runId }.sortedBy { it.version }

    override suspend fun saveStep(runId: RunId, step: Step) {
        steps[step.id] = step
    }

    override suspend fun loadSteps(runId: RunId, planVersion: Int): List<Step> =
        steps.values.filter { it.planVersion == planVersion }.sortedBy { it.createdAtMs }

    override suspend fun loadStep(stepId: StepId): Step? = steps[stepId]

    override suspend fun appendObservation(runId: RunId, stepId: StepId, observation: Observation) {
        observations += runId to observation
    }

    override suspend fun loadObservations(runId: RunId): List<Observation> =
        observations.filter { it.first == runId }.map { it.second }

    override suspend fun saveApproval(approval: ApprovalRecord) {
        approvals[approval.id] = approval
    }

    override suspend fun loadApproval(approvalId: ApprovalId): ApprovalRecord? = approvals[approvalId]

    override suspend fun loadPendingApprovals(runId: RunId): List<ApprovalRecord> =
        approvals.values.filter { it.runId == runId && it.state == ApprovalState.PENDING }

    override suspend fun appendBudgetEntry(entry: BudgetEntry) {
        ledger += entry
    }

    override suspend fun loadBudgetLedger(runId: RunId): List<BudgetEntry> =
        ledger.filter { it.runId == runId }

    override suspend fun loadSetting(key: String): String? = settings[key]

    override suspend fun saveSetting(key: String, value: String) {
        settings[key] = value
    }
}
