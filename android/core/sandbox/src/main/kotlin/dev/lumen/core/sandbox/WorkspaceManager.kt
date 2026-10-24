package dev.lumen.core.sandbox

import dev.lumen.core.model.LumenClock
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer

/** A write that would push a bucket past its budget. */
public class StorageQuotaExceeded(public val bucket: StorageBucket, public val requestedBytes: Long, public val availableBytes: Long) :
    Exception(
        "the write of $requestedBytes bytes does not fit in ${bucket.name.lowercase()}, " +
            "which has $availableBytes bytes left",
    )

/**
 * The only way to read or write a file.
 *
 * Every operation resolves its path through the root, so a path that escapes
 * is refused before any I/O happens, and every write is accounted against the
 * bucket budget. When a bucket is full, the least-recently-used files in it
 * are evicted first — except in the user's workspace, which is never swept.
 */
public class WorkspaceManager(private val root: SandboxRoot, private val quota: StorageQuota, private val clock: LumenClock) {

    /**
     * Writes [bytes] to [path].
     *
     * @return the number of bytes written
     */
    public fun write(path: SandboxPath, bytes: ByteArray): Long {
        val file = root.resolve(path)
        val bucket = root.bucketOf(path)

        if (bucket != null && quota.wouldExceed(bucket, bytes.size.toLong())) {
            makeRoom(bucket, bytes.size.toLong())
        }

        try {
            file.parentFile?.mkdirs()
            file.writeBytes(bytes)
        } catch (error: IOException) {
            throw SandboxViolation(path.relative, "the file could not be written", error)
        }

        if (bucket != null) {
            quota.register(path, bytes.size.toLong(), clock.nowMs())
        }
        return bytes.size.toLong()
    }

    /**
     * Appends [bytes] to the file at [path], creating it when it is missing.
     *
     * This is the only way a file grows in place: [write] replaces the
     * contents, and an append that went through [write] would silently drop
     * everything already in the file.
     *
     * @return the number of bytes added
     */
    public fun append(path: SandboxPath, bytes: ByteArray): Long = appendTo(path, bytes, durable = false)

    /**
     * Appends [bytes] to [path] and does not return until the device has them.
     *
     * This is the only write that survives the process being killed: the page
     * cache is not a record, and a ledger entry that lives there is an entry
     * that was never written. Everything the [append] contract has — the
     * bucket admission, the eviction, the accounting — applies here too.
     *
     * @return the number of bytes added
     */
    public fun appendDurable(path: SandboxPath, bytes: ByteArray): Long = appendTo(path, bytes, durable = true)

    private fun appendTo(path: SandboxPath, bytes: ByteArray, durable: Boolean): Long {
        val file = root.resolve(path)
        val bucket = root.bucketOf(path)
        val sizeBefore = if (file.isFile) file.length() else 0L

        if (bucket != null && quota.wouldExceed(bucket, bytes.size.toLong())) {
            makeRoom(bucket, bytes.size.toLong())
        }

        try {
            file.parentFile?.mkdirs()
            FileOutputStream(file, true).use { stream ->
                val channel = stream.channel
                channel.write(ByteBuffer.wrap(bytes))
                if (durable) {
                    // `force(true)` takes the metadata as well as the content,
                    // so the file exists at the size it claims after a crash.
                    channel.force(true)
                }
            }
        } catch (error: IOException) {
            throw SandboxViolation(path.relative, "the file could not be appended", error)
        }

        if (bucket != null) {
            quota.register(path, file.length(), clock.nowMs())
        }
        return file.length() - sizeBefore
    }

    /** Reads the file at [path]. */
    public fun read(path: SandboxPath): ByteArray {
        val file = root.resolve(path)
        if (!file.isFile) {
            throw SandboxViolation(path.relative, "the file does not exist")
        }
        try {
            val bytes = file.readBytes()
            quota.touch(path, clock.nowMs())
            return bytes
        } catch (error: IOException) {
            throw SandboxViolation(path.relative, "the file could not be read", error)
        }
    }

    /** Whether a file exists at [path]. */
    public fun exists(path: SandboxPath): Boolean = root.resolve(path).isFile

    /**
     * How much of [bucket]'s budget is still unclaimed.
     *
     * This is an accounting question, not a file operation: it reads what the
     * quota already knows and touches nothing.
     */
    public fun availableBytes(bucket: StorageBucket): Long = quota.availableBytes(bucket)

    /** Deletes the file at [path]. */
    public fun delete(path: SandboxPath): Boolean {
        val file = root.resolve(path)
        val deleted = if (file.isFile) file.delete() else false
        if (deleted) {
            quota.unregister(path)
        }
        return deleted
    }

    /** The files in [bucket], relative to the root. */
    public fun list(bucket: StorageBucket): List<SandboxPath> {
        val directory = File(root.root, bucket.name.lowercase())
        if (!directory.isDirectory) return emptyList()

        return directory.listFiles()
            .orEmpty()
            .filter { it.isFile }
            .map { file ->
                SandboxPath.of("${bucket.name.lowercase()}/${file.name}")
            }
            .sortedBy { it.relative }
    }

    /**
     * Frees space in [bucket] by evicting the least-recently-used files.
     *
     * Files are evicted one at a time, oldest first, and only until the write
     * fits. The user's workspace is never swept, so a full workspace bucket
     * refuses the write rather than deleting the user's files.
     */
    private fun makeRoom(bucket: StorageBucket, neededBytes: Long) {
        while (quota.wouldExceed(bucket, neededBytes)) {
            val oldest = quota.oldestEvictable(bucket) ?: break
            val file = root.resolve(oldest.path)
            if (file.isFile) {
                file.delete()
            }
            quota.unregister(oldest.path)
        }

        if (quota.wouldExceed(bucket, neededBytes)) {
            throw StorageQuotaExceeded(
                bucket = bucket,
                requestedBytes = neededBytes,
                availableBytes = quota.availableBytes(bucket),
            )
        }
    }
}
