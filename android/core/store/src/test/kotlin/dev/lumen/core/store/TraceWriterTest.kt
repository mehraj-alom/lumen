package dev.lumen.core.store

import com.google.common.truth.Truth.assertThat
import dev.lumen.core.model.ManualClock
import dev.lumen.core.sandbox.SandboxPath
import dev.lumen.core.sandbox.SandboxRoot
import dev.lumen.core.sandbox.StorageBucket
import dev.lumen.core.sandbox.StorageQuota
import dev.lumen.core.sandbox.WorkspaceManager
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TraceWriterTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var workspace: WorkspaceManager

    @Before
    fun setUp() {
        val root = SandboxRoot(temporaryFolder.newFolder("sandbox"))
        workspace = WorkspaceManager(root, StorageQuota(), ManualClock(0L))
    }

    @Test
    fun `T-TRC-01 events land in one file, in order`() = runTest {
        val writer = TraceWriter(workspace, maxBytesPerFile = 1_000_000L)

        repeat(3) { index -> writer.append(event(runId = "run", index = index)) }

        val lines = String(workspace.read(path("run", part = 0))).trim().lines()
        assertThat(lines).hasSize(3)
        assertThat(lines[0]).contains("\"seq\":0")
        assertThat(lines[2]).contains("\"seq\":2")
    }

    @Test
    fun `T-TRC-02 a file past its size limit rolls onto the next part`() = runTest {
        val lineBytes = measureLineBytes()
        val writer = TraceWriter(workspace, maxBytesPerFile = lineBytes * 2 + 10)

        repeat(5) { index -> writer.append(event(runId = "run", index = index)) }

        val paths = listOf(path("run", 0), path("run", 1), path("run", 2))
        assertThat(workspace.list(StorageBucket.TRACES).map { it.relative }.toSet())
            .containsExactlyElementsIn(paths.map { it.relative }.toSet())
        val lines = paths.flatMap { String(workspace.read(it)).trim().lines() }
        assertThat(lines).hasSize(5)
        assertThat(lines.map { it.substringAfter("\"seq\":").substringBefore(',') })
            .containsExactly("0", "1", "2", "3", "4").inOrder()
    }

    @Test
    fun `T-TRC-03 prune takes the rolled parts as well as the first file`() = runTest {
        val writer = TraceWriter(workspace, maxBytesPerFile = 1L, retentionMs = MILLIS_PER_DAY)

        repeat(3) { index -> writer.append(event(runId = "run", index = index)) }
        assertThat(workspace.list(StorageBucket.TRACES)).hasSize(3)

        val removed = writer.prune(nowMs = DAY_INDEX * MILLIS_PER_DAY + 2 * MILLIS_PER_DAY)

        assertThat(removed).isEqualTo(3)
        assertThat(workspace.list(StorageBucket.TRACES)).isEmpty()
    }

    /** The size of one trace line, measured rather than assumed. */
    private suspend fun measureLineBytes(): Long {
        val probe = TraceWriter(workspace)
        probe.append(event(runId = "probe", index = 0))
        val size = workspace.read(path("probe", 0)).size.toLong()
        workspace.delete(path("probe", 0))
        return size
    }

    private fun event(runId: String, index: Int): TraceEvent = TraceEvent(
        runId = runId,
        seq = index.toLong(),
        tsMs = DAY_INDEX * MILLIS_PER_DAY,
        type = "step",
        stepId = "$runId-step-$index",
        reason = "the planner chose this step",
    )

    private fun path(runId: String, part: Int): SandboxPath =
        SandboxPath.of("traces/$runId-$DAY_INDEX${if (part == 0) "" else ".$part"}.jsonl")

    private companion object {
        const val MILLIS_PER_DAY: Long = 24L * 60L * 60L * 1000L
        const val DAY_INDEX = 20_000L
    }
}
