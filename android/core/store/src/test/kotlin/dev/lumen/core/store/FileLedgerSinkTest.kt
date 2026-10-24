package dev.lumen.core.store

import com.google.common.truth.Truth.assertThat
import dev.lumen.core.action.ActionLedgerEntry
import dev.lumen.core.model.Action
import dev.lumen.core.model.ManualClock
import dev.lumen.core.model.Reason
import dev.lumen.core.model.RunId
import dev.lumen.core.model.StepId
import dev.lumen.core.sandbox.SandboxPath
import dev.lumen.core.sandbox.SandboxRoot
import dev.lumen.core.sandbox.StorageBucket
import dev.lumen.core.sandbox.StorageQuota
import dev.lumen.core.sandbox.WorkspaceManager
import java.io.IOException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileLedgerSinkTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val runId = RunId(RUN)
    private val stepId = StepId(STEP)

    private lateinit var workspace: WorkspaceManager
    private lateinit var sink: FileLedgerSink

    @Before
    fun setUp() {
        workspace = workspace(StorageBucket.entries.associateWith { BUCKET_BUDGET_MIB })
        sink = FileLedgerSink(workspace)
    }

    @Test
    fun `T-LED-01 an entry lands as one line in the traces bucket`() = runTest {
        sink.append(entry(seq = 0L))
        sink.append(entry(seq = 1L))

        val lines = String(workspace.read(ledgerPath())).trim().lines()
        assertThat(lines).hasSize(2)

        val first = json.decodeFromString<LedgerRecord>(lines.first())
        assertThat(first.runId).isEqualTo(RUN)
        assertThat(first.stepId).isEqualTo(STEP)
        assertThat(first.seq).isEqualTo(0L)
        assertThat(first.action).isEqualTo(Action.ClickText("Continue"))
        assertThat(first.reason).isEqualTo("the planner chose this step")
        assertThat(first.reasonSource).isEqualTo("PLANNER")
        // The screen that preceded the action is on the record (invariant I6).
        assertThat(first.preState).isEqualTo("app=com.example.browser;nodes=1")
    }

    @Test
    fun `one run gets one file per day`() = runTest {
        sink.append(entry(seq = 0L, startedAtMs = DAY_INDEX * MILLIS_PER_DAY))
        sink.append(entry(seq = 1L, startedAtMs = (DAY_INDEX + 1) * MILLIS_PER_DAY))

        assertThat(workspace.list(StorageBucket.TRACES).map { it.relative }.sorted())
            .containsExactly(
                "traces/ledger-$RUN-$DAY_INDEX.jsonl",
                "traces/ledger-$RUN-${DAY_INDEX + 1}.jsonl",
            )
    }

    @Test
    fun `T-LED-02 a bucket that cannot take the entry is an io failure`() = runTest {
        // The sink's contract with `ActionLedger`: a write that did not become
        // durable is reported as an IOException, which is what turns into a
        // denied action rather than a crash in the middle of a run.
        val exhausted = FileLedgerSink(workspace(mapOf(StorageBucket.TRACES to 0L)))

        val failure = runCatching { exhausted.append(entry(seq = 0L)) }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IOException::class.java)
    }

    @Test
    fun `the sink is healthy while the traces bucket has room`() = runTest {
        val emptyBucket = FileLedgerSink(workspace(mapOf(StorageBucket.TRACES to 0L)))

        assertThat(sink.isHealthy()).isTrue()
        assertThat(emptyBucket.isHealthy()).isFalse()
    }

    /** A workspace over its own sandbox root, with these bucket budgets. */
    private fun workspace(budgets: Map<StorageBucket, Long>): WorkspaceManager = WorkspaceManager(
        root = SandboxRoot(temporaryFolder.newFolder("sandbox", uniqueName())),
        quota = StorageQuota(
            StorageBucket.entries.associateWith { budgets[it] ?: BUCKET_BUDGET_MIB },
        ),
        clock = ManualClock(0L),
    )

    private fun entry(seq: Long, startedAtMs: Long = DAY_INDEX * MILLIS_PER_DAY) = ActionLedgerEntry(
        runId = runId,
        stepId = stepId,
        seq = seq,
        action = Action.ClickText("Continue"),
        reason = Reason.planner("the planner chose this step"),
        preState = "app=com.example.browser;nodes=1",
        startedAtMs = startedAtMs,
    )

    private fun ledgerPath(): SandboxPath = SandboxPath.of("traces/ledger-$RUN-$DAY_INDEX.jsonl")

    /** A fresh directory name, so one test's sandbox never shadows another's. */
    private fun uniqueName(): String = java.util.UUID.randomUUID().toString()

    private companion object {
        val json = Json { ignoreUnknownKeys = true }

        const val RUN = "01JBX000000000000000000001"
        const val STEP = "01JBX000000000000000000002"
        const val MILLIS_PER_DAY: Long = 24L * 60L * 60L * 1000L
        const val DAY_INDEX = 20_000L
        const val BUCKET_BUDGET_MIB: Long = 1L
    }
}
