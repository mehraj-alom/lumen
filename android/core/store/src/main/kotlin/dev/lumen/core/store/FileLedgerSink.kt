package dev.lumen.core.store

import dev.lumen.core.action.ActionLedgerEntry
import dev.lumen.core.action.LedgerSink
import dev.lumen.core.model.Action
import dev.lumen.core.sandbox.SandboxPath
import dev.lumen.core.sandbox.SandboxViolation
import dev.lumen.core.sandbox.StorageBucket
import dev.lumen.core.sandbox.StorageQuotaExceeded
import dev.lumen.core.sandbox.WorkspaceManager
import java.io.IOException
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * One recorded action, in the form the ledger file holds.
 *
 * The wire form is flat and it is write-only: nothing reads the ledger back to
 * drive the agent, so the record exists for the audit, and for the human who
 * asks what the agent did and why.
 */
@Serializable
public data class LedgerRecord(
    val runId: String,
    val stepId: String,
    val seq: Long,
    val startedAtMs: Long,
    val reasonSource: String,
    val reason: String,
    val preState: String?,
    val action: Action,
) {
    public companion object {
        /** The file form of [entry]. */
        public fun of(entry: ActionLedgerEntry): LedgerRecord = LedgerRecord(
            runId = entry.runId.value,
            stepId = entry.stepId.value,
            seq = entry.seq,
            startedAtMs = entry.startedAtMs,
            reasonSource = entry.reason.source.name,
            reason = entry.reason.text,
            preState = entry.preState,
            action = entry.action,
        )
    }
}

/**
 * The write-ahead ledger, as a file.
 *
 * One JSONL line per action, under `traces/`, and the line is on the device
 * before the call returns. That is the whole reason the ledger exists: an
 * action that is not recorded is an action nobody can audit, and an action
 * nobody can audit is not dispatched (invariant I6).
 *
 * A refusal from the sandbox — the path was not inside it, the bucket filled
 * up and there was nothing left to evict — is reported as an `IOException`.
 * That is how [dev.lumen.core.action.ActionLedger] is told the entry did not
 * become durable, and it turns the refusal into a denied action rather than
 * into a crash mid-run.
 */
public class FileLedgerSink(private val workspace: WorkspaceManager) : LedgerSink {

    private val json = Json { encodeDefaults = true }

    override suspend fun append(entry: ActionLedgerEntry) {
        val line = json.encodeToString(LedgerRecord.of(entry)) + "\n"
        try {
            // The path is built inside the guard: a name that the sandbox
            // refuses must deny the action rather than end the run.
            val path = pathFor(entry)
            workspace.appendDurable(path, line.toByteArray())
        } catch (error: SandboxViolation) {
            throw IOException("the ledger entry could not be made durable", error)
        } catch (error: StorageQuotaExceeded) {
            throw IOException("the traces bucket has no room left for a ledger entry", error)
        }
    }

    /**
     * Whether the ledger can still take an entry.
     *
     * The check is the one that matters to the agent: the bucket has some
     * unclaimed budget. Eviction can still make room beyond that, and a full
     * bucket is caught on the write, where it denies the action.
     */
    override suspend fun isHealthy(): Boolean = workspace.availableBytes(StorageBucket.TRACES) > 0L

    private fun pathFor(entry: ActionLedgerEntry): SandboxPath {
        val day = entry.startedAtMs / MILLIS_PER_DAY
        return SandboxPath.of("traces/ledger-${entry.runId.value}-$day.jsonl")
    }

    public companion object {
        private const val MILLIS_PER_DAY: Long = 24L * 60L * 60L * 1000L
    }
}
