package dev.lumen.agent.service

import dev.lumen.core.sandbox.SandboxPath
import dev.lumen.core.sandbox.WorkspaceManager
import dev.lumen.core.model.LumenClock
import dev.lumen.core.model.RunId
import dev.lumen.core.model.SystemLumenClock

/**
 * The frame pipeline.
 *
 * A captured frame is written into the sandbox and its path is published.
 * The path goes over the IPC, not the bytes: a four-megabyte frame does not
 * fit in a Binder transaction, and sending the bytes would put the bulk
 * channel on the same wire as the control channel.
 *
 * The frames are the first thing the quota evicts, and they are dropped
 * rather than queued when the frame buffer is full.
 */
public class FramePipeline(
    private val workspace: WorkspaceManager,
    private val clock: LumenClock = SystemLumenClock,
    private val maxBufferBytes: Long = DEFAULT_MAX_BUFFER_BYTES,
) {

    private val lock = Any()
    private var bufferedBytes = 0L
    private var droppedFrames = 0L
    private var publishedFrames = 0L

    /** The number of frames that were dropped. */
    public fun droppedFrames(): Long = synchronized(lock) { droppedFrames }

    /** The number of frames that were published. */
    public fun publishedFrames(): Long = synchronized(lock) { publishedFrames }

    /** The bytes currently held in the frame buffer. */
    public fun bufferedBytes(): Long = synchronized(lock) { bufferedBytes }

    /**
     * Stores a frame and returns the path it was written to.
     *
     * @return the path, or null when the frame was dropped because the
     *   buffer was full
     */
    public fun store(runId: RunId, bytes: ByteArray): SandboxPath? {
        synchronized(lock) {
            if (bufferedBytes + bytes.size > maxBufferBytes) {
                droppedFrames++
                return null
            }
            bufferedBytes += bytes.size
        }

        val path = SandboxPath.of("frames/$runId-${clock.nowMs()}.jpg")
        workspace.write(path, bytes)

        synchronized(lock) { publishedFrames++ }
        return path
    }

    /**
     * Releases a frame that the consumer has finished with.
     */
    public fun release(path: SandboxPath, sizeBytes: Long) {
        synchronized(lock) {
            bufferedBytes = (bufferedBytes - sizeBytes).coerceAtLeast(0)
        }
        workspace.delete(path)
    }

    /** Removes the frames that are past the retention window. */
    public fun prune(nowMs: Long): Int {
        val cutoff = nowMs - RETENTION_MS
        var removed = 0
        for (path in workspace.list(dev.lumen.core.sandbox.StorageBucket.FRAMES)) {
            val capturedAt = path.toRelativePath()
                .substringAfterLast('-')
                .removeSuffix(".jpg")
                .toLongOrNull()
            if (capturedAt != null && capturedAt < cutoff) {
                if (workspace.delete(path)) removed++
            }
        }
        return removed
    }

    public companion object {
        /** The default ceiling on the frame buffer. */
        public const val DEFAULT_MAX_BUFFER_BYTES: Long = 128L * 1024L * 1024L

        /** How long a frame is kept. */
        public const val RETENTION_MS: Long = 24L * 60L * 60L * 1000L
    }
}
