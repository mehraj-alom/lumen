package dev.lumen.core.sandbox

import com.google.common.truth.Truth.assertThat
import dev.lumen.core.model.ManualClock
import java.io.File
import java.nio.file.Files
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SandboxPathTest {

    @Test
    fun `Q-STO-02 a path with parent traversal is refused`() {
        val failure = runCatching { SandboxPath.of("../etc/passwd") }.exceptionOrNull()

        assertThat(failure).isInstanceOf(SandboxViolation::class.java)
    }

    @Test
    fun `a traversal in the middle of a path is refused`() {
        val failure = runCatching { SandboxPath.of("frames/../../etc/passwd") }.exceptionOrNull()

        assertThat(failure).isInstanceOf(SandboxViolation::class.java)
    }

    @Test
    fun `Q-STO-03 an absolute path is refused`() {
        val failure = runCatching { SandboxPath.of("/etc/passwd") }.exceptionOrNull()

        assertThat(failure).isInstanceOf(SandboxViolation::class.java)
    }

    @Test
    fun `Q-STO-05 a foreign filesystem is refused`() {
        listOf("proc/self/mem", "sys/kernel", "sdcard/DCIM/x.jpg").forEach { candidate ->
            val failure = runCatching { SandboxPath.of(candidate) }.exceptionOrNull()

            assertThat(failure).isInstanceOf(SandboxViolation::class.java)
        }
    }

    @Test
    fun `Q-STO-06 a path with a control character is refused`() {
        val failure = runCatching { SandboxPath.of("frames/a\u0000b.jpg") }.exceptionOrNull()

        assertThat(failure).isInstanceOf(SandboxViolation::class.java)
    }

    @Test
    fun `a blank path is refused`() {
        val failure = runCatching { SandboxPath.of("   ") }.exceptionOrNull()

        assertThat(failure).isInstanceOf(SandboxViolation::class.java)
    }

    @Test
    fun `a windows drive is refused`() {
        val failure = runCatching { SandboxPath.of("C:/Windows/System32") }.exceptionOrNull()

        assertThat(failure).isInstanceOf(SandboxViolation::class.java)
    }

    @Test
    fun `a home-relative path is refused`() {
        val failure = runCatching { SandboxPath.of("~/secrets") }.exceptionOrNull()

        assertThat(failure).isInstanceOf(SandboxViolation::class.java)
    }

    @Test
    fun `a path inside the sandbox is accepted`() {
        val path = SandboxPath.of("frames/screenshot-0001.jpg")

        assertThat(path.relative).isEqualTo("frames/screenshot-0001.jpg")
        assertThat(path.bucketName).isEqualTo("frames")
        assertThat(path.segments).containsExactly("frames", "screenshot-0001.jpg").inOrder()
    }

    @Test
    fun `the bucket of a path is resolved by its first segment`() {
        assertThat(StorageBucket.bucketOf(SandboxPath.of("frames/a.jpg"))).isEqualTo(StorageBucket.FRAMES)
        assertThat(StorageBucket.bucketOf(SandboxPath.of("workspace/notes.md"))).isEqualTo(StorageBucket.WORKSPACE)
        assertThat(StorageBucket.bucketOf(SandboxPath.of("elsewhere/a.txt"))).isNull()
    }
}

class SandboxRootTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var root: SandboxRoot

    @Before
    fun setUp() {
        root = SandboxRoot(temporaryFolder.newFolder("sandbox"))
    }

    @Test
    fun `Q-STO-01 a path inside the root resolves to a file inside the root`() {
        val resolved = root.resolve(SandboxPath.of("frames/a.jpg"))

        assertThat(root.isInsideRoot(resolved)).isTrue()
        assertThat(resolved.path).startsWith(root.root.path)
    }

    @Test
    fun `Q-STO-04 a symlink that points outside the root is refused`() {
        val outside = temporaryFolder.newFile("outside.txt")
        val link = File(root.root, "frames/link.txt")
        link.parentFile?.mkdirs()
        Files.createSymbolicLink(link.toPath(), outside.toPath())

        val failure = runCatching { root.resolve(SandboxPath.of("frames/link.txt")) }.exceptionOrNull()

        assertThat(failure).isInstanceOf(SandboxViolation::class.java)
    }

    @Test
    fun `a symlink that stays inside the root is allowed`() {
        val inside = File(root.root, "frames/real.txt")
        inside.parentFile?.mkdirs()
        inside.writeText("hello")
        val link = File(root.root, "frames/alias.txt")
        Files.createSymbolicLink(link.toPath(), inside.toPath())

        val resolved = root.resolve(SandboxPath.of("frames/alias.txt"))

        assertThat(root.isInsideRoot(resolved)).isTrue()
    }
}

class WorkspaceManagerTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var root: SandboxRoot
    private lateinit var quota: StorageQuota
    private lateinit var clock: ManualClock
    private lateinit var workspace: WorkspaceManager

    @Before
    fun setUp() {
        root = SandboxRoot(temporaryFolder.newFolder("sandbox"))
        quota = StorageQuota(
            budgets = mapOf(
                StorageBucket.FRAMES to 1L,
                StorageBucket.WORKSPACE to 1L,
                StorageBucket.TRACES to 1L,
                StorageBucket.STATE to 1L,
                StorageBucket.MODELS to 1L,
            ),
        )
        clock = ManualClock(1_000L)
        workspace = WorkspaceManager(root, quota, clock)
    }

    @Test
    fun `Q-STO-01 a write inside the root succeeds and is accounted`() {
        val written = workspace.write(SandboxPath.of("frames/a.jpg"), ByteArray(1024))

        assertThat(written).isEqualTo(1024L)
        assertThat(workspace.exists(SandboxPath.of("frames/a.jpg"))).isTrue()
        assertThat(quota.usedBytes(StorageBucket.FRAMES)).isEqualTo(1024L)
    }

    @Test
    fun `a read returns what was written`() {
        val bytes = "hello lumen".toByteArray()
        workspace.write(SandboxPath.of("workspace/notes.md"), bytes)

        assertThat(workspace.read(SandboxPath.of("workspace/notes.md"))).isEqualTo(bytes)
    }

    @Test
    fun `an append grows the file instead of replacing it`() {
        val path = SandboxPath.of("traces/run.jsonl")
        workspace.write(path, "first\n".toByteArray())

        val added = workspace.append(path, "second\n".toByteArray())

        assertThat(added).isEqualTo(7L)
        assertThat(String(workspace.read(path))).isEqualTo("first\nsecond\n")
        assertThat(quota.usedBytes(StorageBucket.TRACES)).isEqualTo(13L)
    }

    @Test
    fun `an append to a missing file creates it`() {
        val path = SandboxPath.of("traces/run.jsonl")

        workspace.append(path, "only\n".toByteArray())

        assertThat(String(workspace.read(path))).isEqualTo("only\n")
    }

    @Test
    fun `a durable append grows the file and is accounted`() {
        // What `force` does cannot be observed from the JVM; what these tests
        // cover is that the durable path keeps every other property of an
        // append — the growth, the accounting, and the sandbox admission.
        val path = SandboxPath.of("traces/ledger.jsonl")
        workspace.write(path, "first\n".toByteArray())

        val added = workspace.appendDurable(path, "second\n".toByteArray())

        assertThat(added).isEqualTo(7L)
        assertThat(String(workspace.read(path))).isEqualTo("first\nsecond\n")
        assertThat(quota.usedBytes(StorageBucket.TRACES)).isEqualTo(13L)
    }

    @Test
    fun `a durable append through a link that leaves the root is refused`() {
        val outside = temporaryFolder.newFile("outside.jsonl")
        File(root.root, "traces").mkdirs()
        java.nio.file.Files.createSymbolicLink(
            File(root.root, "traces/link.jsonl").toPath(),
            outside.toPath(),
        )

        val failure = runCatching {
            workspace.appendDurable(SandboxPath.of("traces/link.jsonl"), "one line\n".toByteArray())
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(SandboxViolation::class.java)
        assertThat(outside.length()).isEqualTo(0L)
    }

    @Test
    fun `a durable append into a bucket with no room is refused`() {
        val noRoom = WorkspaceManager(
            root = SandboxRoot(temporaryFolder.newFolder("no-traces")),
            quota = StorageQuota(mapOf(StorageBucket.TRACES to 0L)),
            clock = ManualClock(1_000L),
        )

        val failure = runCatching {
            noRoom.appendDurable(SandboxPath.of("traces/ledger.jsonl"), "one line\n".toByteArray())
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(StorageQuotaExceeded::class.java)
        assertThat(noRoom.exists(SandboxPath.of("traces/ledger.jsonl"))).isFalse()
    }

    @Test
    fun `an append that would escape the root is refused`() {
        val failure = runCatching {
            workspace.append(SandboxPath.of("traces/../../escape.jsonl"), ByteArray(8))
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(SandboxViolation::class.java)
    }

    @Test
    fun `Q-STO-07 a write into a full bucket evicts the least recently used file`() {
        val hundredKiB = ByteArray(100 * 1024)

        workspace.write(SandboxPath.of("frames/a.jpg"), hundredKiB)
        clock.advance(100)
        workspace.write(SandboxPath.of("frames/b.jpg"), hundredKiB)
        clock.advance(100)
        workspace.write(SandboxPath.of("frames/c.jpg"), hundredKiB)

        // Reading 'a' makes it the newest file, so 'b' becomes the oldest.
        clock.advance(100)
        workspace.read(SandboxPath.of("frames/a.jpg"))

        clock.advance(100)
        workspace.write(SandboxPath.of("frames/d.jpg"), ByteArray(800 * 1024))

        assertThat(workspace.exists(SandboxPath.of("frames/b.jpg"))).isFalse()
        assertThat(workspace.exists(SandboxPath.of("frames/a.jpg"))).isTrue()
        assertThat(workspace.exists(SandboxPath.of("frames/c.jpg"))).isTrue()
        assertThat(workspace.exists(SandboxPath.of("frames/d.jpg"))).isTrue()
    }

    @Test
    fun `Q-STO-08 a write that still does not fit is refused`() {
        val overBudget = ByteArray(2 * 1024 * 1024)

        val failure = runCatching {
            workspace.write(SandboxPath.of("frames/huge.jpg"), overBudget)
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(StorageQuotaExceeded::class.java)
    }

    @Test
    fun `Q-STO-09 the workspace is never swept`() {
        val overBudget = ByteArray(2 * 1024 * 1024)
        workspace.write(SandboxPath.of("workspace/keep.md"), ByteArray(16))

        runCatching { workspace.write(SandboxPath.of("workspace/huge.bin"), overBudget) }

        assertThat(workspace.exists(SandboxPath.of("workspace/keep.md"))).isTrue()
    }

    @Test
    fun `Q-STO-10 a bucket at its cap measures exactly the cap`() {
        val oneMebibyte = ByteArray(1024 * 1024)

        workspace.write(SandboxPath.of("frames/exact.bin"), oneMebibyte)

        assertThat(quota.usedBytes(StorageBucket.FRAMES)).isEqualTo(1024L * 1024L)
        assertThat(quota.budgetMiB(StorageBucket.FRAMES)).isEqualTo(1L)
    }

    @Test
    fun `a delete removes the file and its accounting`() {
        workspace.write(SandboxPath.of("frames/a.jpg"), ByteArray(1024))

        val deleted = workspace.delete(SandboxPath.of("frames/a.jpg"))

        assertThat(deleted).isTrue()
        assertThat(quota.usedBytes(StorageBucket.FRAMES)).isEqualTo(0L)
    }

    @Test
    fun `a write outside the root is refused`() {
        val failure = runCatching {
            workspace.write(SandboxPath.of("frames/../../escape.txt"), ByteArray(8))
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(SandboxViolation::class.java)
    }

    @Test
    fun `the bucket budgets are the specified ones`() {
        val real = StorageQuota()

        assertThat(real.budgetMiB(StorageBucket.MODELS)).isEqualTo(4_000L)
        assertThat(real.budgetMiB(StorageBucket.FRAMES)).isEqualTo(6_000L)
        assertThat(real.budgetMiB(StorageBucket.TRACES)).isEqualTo(3_000L)
        assertThat(real.budgetMiB(StorageBucket.STATE)).isEqualTo(2_000L)
        assertThat(real.budgetMiB(StorageBucket.WORKSPACE)).isEqualTo(4_000L)
        assertThat(StorageBucket.entries.sumOf { it.budgetMiB }).isEqualTo(19_000L)
    }
}
