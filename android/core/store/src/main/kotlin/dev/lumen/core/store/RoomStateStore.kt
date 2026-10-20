package dev.lumen.core.store

import dev.lumen.core.model.Action
import dev.lumen.core.model.ActionPreview
import dev.lumen.core.model.ApprovalId
import dev.lumen.core.model.ApprovalRecord
import dev.lumen.core.model.ApprovalState
import dev.lumen.core.model.Assertion
import dev.lumen.core.model.BudgetEntry
import dev.lumen.core.model.BudgetEntryKind
import dev.lumen.core.model.Observation
import dev.lumen.core.model.Plan
import dev.lumen.core.model.Reason
import dev.lumen.core.model.ReasonSource
import dev.lumen.core.model.RiskClass
import dev.lumen.core.model.RunId
import dev.lumen.core.model.StateStore
import dev.lumen.core.model.Step
import dev.lumen.core.model.StepId
import dev.lumen.core.model.StepState
import androidx.room.withTransaction
import kotlinx.serialization.json.Json

/**
 * The Room-backed [StateStore].
 *
 * The mapping is explicit rather than reflective: the entity columns are the
 * contract in `TECHSTACK.md` §6, and the JSON blobs hold the polymorphic
 * model types that do not have a natural column shape.
 */
public class RoomStateStore(
    private val database: LumenDatabase,
) : StateStore {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override suspend fun savePlan(plan: Plan) {
        database.withTransaction {
            database.plans().upsert(
                PlanEntity(
                    runId = plan.runId.value,
                    version = plan.version,
                    summary = plan.summary,
                    createdAt = plan.createdAtMs,
                ),
            )
            plan.steps.forEach { step -> database.steps().upsert(step.toEntity(plan.runId)) }
        }
    }

    override suspend fun loadPlan(runId: RunId): Plan? {
        val header = database.plans().latest(runId.value) ?: return null
        val steps = database.steps().forPlan(runId.value, header.version).map { it.toStep() }
        return Plan(
            runId = runId,
            version = header.version,
            steps = steps,
            createdAtMs = header.createdAt,
            summary = header.summary,
        )
    }

    override suspend fun loadPlanHistory(runId: RunId): List<Plan> =
        database.plans().history(runId.value).map { header ->
            Plan(
                runId = runId,
                version = header.version,
                steps = database.steps().forPlan(runId.value, header.version).map { it.toStep() },
                createdAtMs = header.createdAt,
                summary = header.summary,
            )
        }

    override suspend fun saveStep(runId: RunId, step: Step) {
        database.steps().upsert(step.toEntity(runId))
    }

    override suspend fun loadSteps(runId: RunId, planVersion: Int): List<Step> =
        database.steps().forPlan(runId.value, planVersion).map { it.toStep() }

    override suspend fun loadStep(stepId: StepId): Step? =
        database.steps().find(stepId.value)?.toStep()

    override suspend fun appendObservation(
        runId: RunId,
        stepId: StepId,
        observation: Observation,
    ) {
        database.observations().insert(
            ObservationEntity(
                runId = runId.value,
                stepId = stepId.value,
                seq = database.observations().nextSeq(runId.value),
                app = observation.app,
                nodesJson = json.encodeToString(observation.nodes),
                overlaysJson = json.encodeToString(observation.overlays),
                settleJson = observation.settle?.let { json.encodeToString(it) },
                screenshotPath = observation.screenshot?.path,
                capturedAt = observation.capturedAtMs,
            ),
        )
    }

    override suspend fun loadObservations(runId: RunId): List<Observation> =
        database.observations().forRun(runId.value).map { entity ->
            Observation(
                app = entity.app,
                nodes = json.decodeFromString(entity.nodesJson),
                overlays = json.decodeFromString(entity.overlaysJson),
                settle = entity.settleJson?.let { json.decodeFromString(it) },
                capturedAtMs = entity.capturedAt,
            )
        }

    override suspend fun saveApproval(approval: ApprovalRecord) {
        database.approvals().upsert(
            ApprovalEntity(
                id = approval.id.value,
                runId = approval.runId.value,
                stepId = approval.stepId.value,
                riskClass = approval.riskClass.name,
                title = approval.title,
                detail = approval.detail,
                app = approval.app,
                appLabel = approval.appLabel,
                consequencesJson = json.encodeToString(approval.consequences),
                actionPreviewJson = json.encodeToString(approval.actionPreview),
                state = approval.state.name,
                createdAt = approval.createdAtMs,
                leaseExpiresAt = approval.leaseExpiresAtMs,
                resolvedAt = approval.resolvedAtMs,
                answerText = approval.answerText,
            ),
        )
    }

    override suspend fun loadApproval(approvalId: ApprovalId): ApprovalRecord? =
        database.approvals().find(approvalId.value)?.toApproval()

    override suspend fun loadPendingApprovals(runId: RunId): List<ApprovalRecord> =
        database.approvals().pending(runId.value).map { it.toApproval() }

    override suspend fun appendBudgetEntry(entry: BudgetEntry) {
        database.budgetLedger().insert(
            BudgetLedgerEntity(
                runId = entry.runId.value,
                category = entry.category,
                kind = entry.kind.name,
                amountBytes = entry.amountBytes,
                createdAt = entry.createdAtMs,
            ),
        )
    }

    override suspend fun loadBudgetLedger(runId: RunId): List<BudgetEntry> =
        database.budgetLedger().forRun(runId.value).map { entity ->
            BudgetEntry(
                runId = runId,
                category = entity.category,
                kind = runCatching { BudgetEntryKind.valueOf(entity.kind) }
                    .getOrDefault(BudgetEntryKind.RESERVE),
                amountBytes = entity.amountBytes,
                createdAtMs = entity.createdAt,
            )
        }

    override suspend fun loadSetting(key: String): String? = database.settings().value(key)

    override suspend fun saveSetting(key: String, value: String) {
        database.settings().upsert(SettingEntity(key = key, value = value))
    }

    private fun Step.toEntity(runId: RunId): StepEntity = StepEntity(
        id = id.value,
        runId = runId.value,
        planVersion = planVersion,
        phase = phase,
        intent = intent,
        actionJson = json.encodeToString(action),
        expectationJson = expectation?.let { json.encodeToString(it) },
        preconditionJson = precondition?.let { json.encodeToString(it) },
        riskClass = riskClass.name,
        reasonSource = reason.source.name,
        reasonText = reason.text,
        state = state.name,
        dependsOnJson = json.encodeToString(dependsOn.map { it.value }),
        seed = seed,
        promptVersion = promptVersion,
        createdAt = createdAtMs,
        updatedAt = updatedAtMs,
    )

    private fun StepEntity.toStep(): Step = Step(
        id = StepId(id),
        planVersion = planVersion,
        phase = phase,
        intent = intent,
        reason = Reason(
            source = runCatching { ReasonSource.valueOf(reasonSource) }
                .getOrDefault(ReasonSource.SYSTEM),
            text = reasonText,
        ),
        action = json.decodeFromString(actionJson),
        expectation = expectationJson?.let { json.decodeFromString<Assertion>(it) },
        precondition = preconditionJson?.let { json.decodeFromString<Assertion>(it) },
        riskClass = runCatching { RiskClass.valueOf(riskClass) }.getOrDefault(RiskClass.NONE),
        state = runCatching { StepState.valueOf(state) }.getOrDefault(StepState.PLANNED),
        dependsOn = json.decodeFromString<List<String>>(dependsOnJson).map { StepId(it) },
        seed = seed,
        promptVersion = promptVersion,
        createdAtMs = createdAt,
        updatedAtMs = updatedAt,
    )

    private fun ApprovalEntity.toApproval(): ApprovalRecord = ApprovalRecord(
        id = ApprovalId(id),
        runId = RunId(runId),
        stepId = StepId(stepId),
        riskClass = runCatching { RiskClass.valueOf(riskClass) }.getOrDefault(RiskClass.NONE),
        title = title,
        detail = detail,
        app = app,
        appLabel = appLabel,
        consequences = json.decodeFromString(consequencesJson),
        actionPreview = json.decodeFromString(actionPreviewJson),
        state = runCatching { ApprovalState.valueOf(state) }.getOrDefault(ApprovalState.PENDING),
        createdAtMs = createdAt,
        leaseExpiresAtMs = leaseExpiresAt,
        resolvedAtMs = resolvedAt,
        answerText = answerText,
    )
}
