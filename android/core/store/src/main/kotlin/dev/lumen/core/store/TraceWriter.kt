package dev.lumen.core.store

import dev.lumen.core.model.RunId
import dev.lumen.core.sandbox.SandboxPath
import dev.lumen.core.sandbox.StorageBucket
import dev.lumen.core.sandbox.WorkspaceManager
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

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
    private val maxBytesPerFile: Long = DEFAULT_MAX_BYTES_PER_FILE,
    private val retentionMs: Long = DEFAULT_RETENTION_MS,
) {

    private val json = Json { encodeDefaults = true }
    private val lock = Any()
    private var currentRunId: String? = null
    private var currentPart = 0
    private var currentBytes = 0L

    /** Appends an event to the trace of [runId]. */
    public suspend fun append(event: TraceEvent) {
        val line = json.encodeToString(event) + "\n"
        val bytes = line.toByteArray()

        val part = synchronized(lock) {
            if (currentRunId != event.runId) {
                currentRunId = event.runId
                currentPart = 0
                currentBytes = 0L
            } else if (currentBytes + bytes.size > maxBytesPerFile) {
                // The file is full: the next line starts the next part, so a
                // run's trace stays readable in order and no file grows past
                // its size limit.
                currentPart += 1
                currentBytes = 0L
            }
            currentBytes += bytes.size
            currentPart
        }

        workspace.append(pathFor(event.runId, event.tsMs, part), bytes)
    }

    /** Starts a new trace file, so that the current one stops growing. */
    public fun roll(): Unit = synchronized(lock) {
        currentRunId = null
        currentPart = 0
        currentBytes = 0L
    }

    /** Deletes the trace files that are past the retention window. */
    public fun prune(nowMs: Long): Int {
        val cutoff = nowMs - retentionMs
        return workspace.list(StorageBucket.TRACES).count { path ->
            val timestamp = timestampFromName(path.toRelativePath())
            timestamp != null && timestamp < cutoff && workspace.delete(path)
        }
    }

    private fun pathFor(runId: String, tsMs: Long, part: Int): SandboxPath {
        val day = tsMs / MILLIS_PER_DAY
        val suffix = if (part == 0) "" else ".$part"
        return SandboxPath.of("traces/$runId-$day$suffix.jsonl")
    }

    private fun timestampFromName(name: String): Long? {
        val stamp = name.substringAfterLast('-').removeSuffix(".jsonl").substringBefore('.')
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
public class FrameStore(private val workspace: WorkspaceManager) {

    /** Stores a frame and returns the path it was written to. */
    public suspend fun store(runId: RunId, bytes: ByteArray, capturedAtMs: Long): SandboxPath {
        val path = SandboxPath.of("frames/$runId-$capturedAtMs.jpg")
        workspace.write(path, bytes)
        return path
    }

    /** The path a frame with these coordinates would have. */
    public fun pathFor(runId: RunId, capturedAtMs: Long): SandboxPath = SandboxPath.of("frames/$runId-$capturedAtMs.jpg")

    /** Removes the frames that are past the retention window. */
    public fun prune(nowMs: Long): Int {
        val cutoff = nowMs - RETENTION_MS
        return workspace.list(StorageBucket.FRAMES).count { path ->
            val capturedAt = capturedAtFromName(path.toRelativePath())
            capturedAt != null && capturedAt < cutoff && workspace.delete(path)
        }
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
