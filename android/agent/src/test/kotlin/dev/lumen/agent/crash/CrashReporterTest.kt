package dev.lumen.agent.crash

import com.google.common.truth.Truth.assertThat
import dev.lumen.core.model.ManualClock
import dev.lumen.core.sandbox.SandboxRoot
import dev.lumen.core.sandbox.StorageBucket
import dev.lumen.core.sandbox.StorageQuota
import dev.lumen.core.sandbox.WorkspaceManager
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CrashReporterTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var workspace: WorkspaceManager
    private lateinit var reporter: CrashReporter

    @Before
    fun setUp() {
        val root = SandboxRoot(temporaryFolder.newFolder("sandbox"))
        workspace = WorkspaceManager(root, StorageQuota(), ManualClock(1_000L))
        reporter = CrashReporter(workspace)
    }

    @Test
    fun `nothing is written while reporting is off`() {
        val report = reporter.record(IllegalStateException("boom"))

        assertThat(report).isNull()
        assertThat(reporter.written).isEmpty()
        assertThat(workspace.list(StorageBucket.TRACES)).isEmpty()
    }

    @Test
    fun `a report is written when reporting is on`() {
        reporter.enabled = true

        val report = reporter.record(IllegalStateException("boom"))

        assertThat(report).isNotNull()
        assertThat(workspace.list(StorageBucket.TRACES)).hasSize(1)
    }

    @Test
    fun `a secret in the message is redacted`() {
        reporter.enabled = true

        val report = reporter.record(IllegalStateException("the password hunter2 was rejected"))

        assertThat(report?.message).doesNotContain("hunter2")
        assertThat(report?.message).contains("[redacted]")
    }

    @Test
    fun `an absolute path is reduced to its file name`() {
        reporter.enabled = true

        val report = reporter.record(
            IllegalStateException("could not read /data/user/0/dev.lumen/files/secret.json"),
        )

        assertThat(report?.message).doesNotContain("/data/user/0")
        assertThat(report?.message).contains("secret.json")
    }

    @Test
    fun `the rendered report is valid json`() {
        reporter.enabled = true

        val rendered = reporter.record(RuntimeException("boom"))?.render().orEmpty()

        assertThat(rendered).contains("\"exception\"")
        assertThat(rendered).contains("\"stackTrace\"")
    }
}
