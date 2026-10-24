package dev.lumen.core.store

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

/** The run table. */
@Dao
public interface RunDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public suspend fun upsert(run: RunEntity)

    @Query("SELECT * FROM runs WHERE id = :runId")
    public suspend fun find(runId: String): RunEntity?

    @Query("SELECT * FROM runs ORDER BY created_at DESC LIMIT :limit")
    public suspend fun recent(limit: Int): List<RunEntity>
}

/** The plan table. */
@Dao
public interface PlanDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public suspend fun upsert(plan: PlanEntity)

    @Query("SELECT * FROM plans WHERE run_id = :runId ORDER BY version DESC LIMIT 1")
    public suspend fun latest(runId: String): PlanEntity?

    @Query("SELECT * FROM plans WHERE run_id = :runId ORDER BY version ASC")
    public suspend fun history(runId: String): List<PlanEntity>
}

/** The step table. */
@Dao
public interface StepDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public suspend fun upsert(step: StepEntity)

    @Query("SELECT * FROM steps WHERE run_id = :runId AND plan_version = :planVersion ORDER BY rowid")
    public suspend fun forPlan(runId: String, planVersion: Int): List<StepEntity>

    @Query("SELECT * FROM steps WHERE id = :stepId")
    public suspend fun find(stepId: String): StepEntity?

    @Query("SELECT COUNT(*) FROM steps WHERE run_id = :runId")
    public suspend fun count(runId: String): Int
}

/** The observation table. */
@Dao
public interface ObservationDao {
    @Insert
    public suspend fun insert(observation: ObservationEntity)

    @Query("SELECT * FROM observations WHERE run_id = :runId ORDER BY seq ASC")
    public suspend fun forRun(runId: String): List<ObservationEntity>

    @Query("SELECT COALESCE(MAX(seq), -1) + 1 FROM observations WHERE run_id = :runId")
    public suspend fun nextSeq(runId: String): Long
}

/** The approval table. */
@Dao
public interface ApprovalDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public suspend fun upsert(approval: ApprovalEntity)

    @Query("SELECT * FROM approvals WHERE id = :approvalId")
    public suspend fun find(approvalId: String): ApprovalEntity?

    @Query("SELECT * FROM approvals WHERE run_id = :runId AND state = 'PENDING' ORDER BY created_at ASC")
    public suspend fun pending(runId: String): List<ApprovalEntity>

    @Transaction
    public suspend fun resolve(approvalId: String, state: String, resolvedAt: Long, answerText: String?) {
        val existing = find(approvalId) ?: return
        upsert(existing.copy(state = state, resolvedAt = resolvedAt, answerText = answerText))
    }
}

/** The budget ledger table. */
@Dao
public interface BudgetLedgerDao {
    @Insert
    public suspend fun insert(entry: BudgetLedgerEntity)

    @Query("SELECT * FROM budget_ledger WHERE run_id = :runId ORDER BY created_at ASC")
    public suspend fun forRun(runId: String): List<BudgetLedgerEntity>
}

/** The settings table. */
@Dao
public interface SettingsDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public suspend fun upsert(setting: SettingEntity)

    @Query("SELECT value FROM settings WHERE `key` = :key")
    public suspend fun value(key: String): String?

    @Query("DELETE FROM settings WHERE `key` = :key")
    public suspend fun delete(key: String)
}
