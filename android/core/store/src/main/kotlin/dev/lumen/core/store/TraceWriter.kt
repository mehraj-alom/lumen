package dev.lumen.core.store

import dev.lumen.core.model.LumenClock
import dev.lumen.core.model.RunId
import dev.lumen.core.sandbox.SandboxPath
import dev.lumen.core.sandbox.WorkspaceManager
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException

/** One line of an execution trace. */
@Serializable
public data class TraceEvent(
    val runId: String,
    val seq: Long,
    val tsMs: Long,
    val type: String,
    val stepId: String? = null,
    val reason: String? = null,
    val app: String? = null,
    val durationMs: Long? = null,
    val text: String? = null,
)

/**
 * The rotating execution trace.
 *
 * Traces are append-only JSONL under `traces/`, one file per run per day. A
 * file is rolled when it grows past its size limit, and files older than the
 * retention window are removed. Nothing in a trace is screen content: the
 * trace records what happened, not what was on the screen.
 */
public class TraceWriter(
    private val workspace: WorkspaceManager,
    private val clock: LumenClock,
    private val maxBytesPerFile: Long = DEFAULT_MAX_BYTES_PER_FILE,
    private val retentionMs: Long = DEFAULT_RETENTION_MS,
) {

    private val json = Json { encodeDefaults = true }
    private val lock = Any()
    private var currentBytes = 0L
    private var currentPath: SandboxPath? = null

    /** Appends an event to the trace of [runId]. */
    public suspend fun append(event: TraceEvent) {
        val line = json.encodeToString(event) + "\n"
        val bytes = line.toByteArray()
        val path = pathFor(event.runId, event.tsMs)

        synchronized(lock) {
            if (currentPath != path) {
                currentPath = path
                currentBytes = if (workspace.exists(path)) workspace.read(path).size.toLong() else 0L
            }
        }

        workspace.write(path, bytes)
        synchronized(lock) { currentBytes += bytes.size.toLong() }
    }

    /** Starts a new trace file, so that the current one stops growing. */
    public fun roll(): Unit = synchronized(lock) {
        currentPath = null
        currentBytes = 0L
    }

    /** Deletes the trace files that are past the retention window. */
    public fun prune(nowMs: Long): Int {
        val cutoff = nowMs - retentionMs
        var removed = 0

        for (bucket in listOf(dev.lumen.core.sandbox.StorageBucket.TRACES)) {
            for (path in workspace.list(bucket)) {
                val name = path.toRelativePath()
                val timestamp = timestampFromName(name)
                if (timestamp != null && timestamp < cutoff) {
                    if (workspace.delete(path)) removed++
                }
            }
        }
        return removed
    }

    private fun pathFor(runId: String, tsMs: Long): SandboxPath {
        val day = tsMs / MILLIS_PER_DAY
        return SandboxPath.of("traces/$runId-$day.jsonl")
    }

    private fun timestampFromName(name: String): Long? {
        val stamp = name.substringAfterLast('-').removeSuffix(".jsonl")
        return stamp.toLongOrNull()?.times(MILLIS_PER_DAY)
    }

    public companion object {
        /** The size at which a trace file is rolled. */
        public const val DEFAULT_MAX_BYTES_PER_FILE: Long = 8L * 1024L * 1024L

        /** How long a trace file is kept. */
        public const val DEFAULT_RETENTION_MS: Long = 7L * 24L * 60L * 60L * 1000L

        private const val MILLIS_PER_DAY: Long = 24L * 60L * 60L * 1000L
    }
}

/**
 * The frame store.
 *
 * Frames are the bulk artifacts: screen captures for the spectating view and
 * for the trace. They are the first thing to be evicted, and a frame that is
 * more than a day old is downscaled rather than kept at full resolution.
 */
public class FrameStore(
    private val workspace: WorkspaceManager,
    private val clock: LumenClock,
) {

    /** Stores a frame and returns the path it was written to. */
    public suspend fun store(runId: RunId, bytes: ByteArray, capturedAtMs: Long): SandboxPath {
        val path = SandboxPath.of("frames/$runId-$capturedAtMs.jpg")
        workspace.write(path, bytes)
        return path
    }

    /** The path a frame with these coordinates would have. */
    public fun pathFor(runId: RunId, capturedAtMs: Long): SandboxPath =
        SandboxPath.of("frames/$runId-$capturedAtMs.jpg")

    /** Removes the frames that are past the retention window. */
    public fun prune(nowMs: Long): Int {
        val cutoff = nowMs - RETENTION_MS
        var removed = 0

        for (path in workspace.list(dev.lumen.core.sandbox.StorageBucket.FRAMES)) {
            val capturedAt = capturedAtFromName(path.toRelativePath())
            if (capturedAt != null && capturedAt < cutoff) {
                if (workspace.delete(path)) removed++
            }
        }
        return removed
    }

    private fun capturedAtFromName(name: String): Long? {
        val stamp = name.substringAfterLast('-').removeSuffix(".jpg")
        return stamp.toLongOrNull()
    }

    public companion object {
        /** How long a frame is kept at full resolution. */
        public const val RETENTION_MS: Long = 24L * 60L * 60L * 1000L
    }
}
