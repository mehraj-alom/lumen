package dev.lumen.core.store

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** The durable record of a run. */
@Entity(tableName = "runs")
public data class RunEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "goal_text")
    val goalText: String,
    @ColumnInfo(name = "constraints_json")
    val constraintsJson: String,
    @ColumnInfo(name = "phase")
    val phase: String,
    @ColumnInfo(name = "plan_version")
    val planVersion: Int,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)

/** The durable record of a plan version. */
@Entity(
    tableName = "plans",
    primaryKeys = ["run_id", "version"],
    indices = [Index(value = ["run_id", "version"])],
)
public data class PlanEntity(
    @ColumnInfo(name = "run_id")
    val runId: String,
    @ColumnInfo(name = "version")
    val version: Int,
    @ColumnInfo(name = "summary")
    val summary: String?,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
)

/** The durable record of a step. */
@Entity(
    tableName = "steps",
    indices = [Index(value = ["run_id", "plan_version", "state"])],
)
public data class StepEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "run_id")
    val runId: String,
    @ColumnInfo(name = "plan_version")
    val planVersion: Int,
    @ColumnInfo(name = "phase")
    val phase: String,
    @ColumnInfo(name = "intent")
    val intent: String,
    @ColumnInfo(name = "action_json")
    val actionJson: String,
    @ColumnInfo(name = "expectation_json")
    val expectationJson: String?,
    @ColumnInfo(name = "precondition_json")
    val preconditionJson: String?,
    @ColumnInfo(name = "risk_class")
    val riskClass: String,
    @ColumnInfo(name = "reason_source")
    val reasonSource: String,
    @ColumnInfo(name = "reason_text")
    val reasonText: String,
    @ColumnInfo(name = "state")
    val state: String,
    @ColumnInfo(name = "depends_on_json")
    val dependsOnJson: String,
    @ColumnInfo(name = "seed")
    val seed: Long?,
    @ColumnInfo(name = "prompt_version")
    val promptVersion: String?,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)

/** The durable record of an observation. */
@Entity(
    tableName = "observations",
    indices = [Index(value = ["run_id", "seq"])],
)
public data class ObservationEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0L,
    @ColumnInfo(name = "run_id")
    val runId: String,
    @ColumnInfo(name = "step_id")
    val stepId: String,
    @ColumnInfo(name = "seq")
    val seq: Long,
    @ColumnInfo(name = "app")
    val app: String,
    @ColumnInfo(name = "nodes_json")
    val nodesJson: String,
    @ColumnInfo(name = "overlays_json")
    val overlaysJson: String,
    @ColumnInfo(name = "settle_json")
    val settleJson: String?,
    @ColumnInfo(name = "screenshot_path")
    val screenshotPath: String?,
    @ColumnInfo(name = "captured_at")
    val capturedAt: Long,
)

/** The durable record of an approval. */
@Entity(
    tableName = "approvals",
    indices = [Index(value = ["run_id", "state"])],
)
public data class ApprovalEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "run_id")
    val runId: String,
    @ColumnInfo(name = "step_id")
    val stepId: String,
    @ColumnInfo(name = "risk_class")
    val riskClass: String,
    @ColumnInfo(name = "title")
    val title: String,
    @ColumnInfo(name = "detail")
    val detail: String,
    @ColumnInfo(name = "app")
    val app: String,
    @ColumnInfo(name = "app_label")
    val appLabel: String,
    @ColumnInfo(name = "consequences_json")
    val consequencesJson: String,
    @ColumnInfo(name = "action_preview_json")
    val actionPreviewJson: String,
    @ColumnInfo(name = "state")
    val state: String,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "lease_expires_at")
    val leaseExpiresAt: Long,
    @ColumnInfo(name = "resolved_at")
    val resolvedAt: Long?,
    @ColumnInfo(name = "answer_text")
    val answerText: String?,
)

/** The durable record of a budget movement. */
@Entity(
    tableName = "budget_ledger",
    indices = [Index(value = ["run_id", "created_at"])],
)
public data class BudgetLedgerEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0L,
    @ColumnInfo(name = "run_id")
    val runId: String,
    @ColumnInfo(name = "category")
    val category: String,
    @ColumnInfo(name = "kind")
    val kind: String,
    @ColumnInfo(name = "amount_bytes")
    val amountBytes: Long,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
)

/** A durable setting. */
@Entity(tableName = "settings")
public data class SettingEntity(
    @PrimaryKey
    @ColumnInfo(name = "key")
    val key: String,
    @ColumnInfo(name = "value")
    val value: String,
)
