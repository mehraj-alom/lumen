package dev.lumen.core.sandbox

/**
 * The storage buckets of the sandbox.
 *
 * The budgets are the ones in `AGENTS.md` §2. They sum to 20,000 MiB with the
 * headroom, and the `workspace` bucket is never swept.
 */
public enum class StorageBucket(public val budgetMiB: Long) {
    /** Model weights, user-installed. */
    MODELS(4_000L),

    /** Frames and screenshots. Rotating, least-recently-used eviction. */
    FRAMES(6_000L),

    /** Execution traces, as rotating JSONL. */
    TRACES(3_000L),

    /** The state database, its write-ahead log, and its checkpoints. */
    STATE(2_000L),

    /** Files the agent produced, which the user can see. */
    WORKSPACE(4_000L),
    ;

    /** Whether the bucket is swept when it is over budget. */
    public val evictable: Boolean
        get() = this != WORKSPACE

    public companion object {
        /** The total storage cap, in MiB. */
        public const val TOTAL_LIMIT_MIB: Long = 20_000L

        /** The bucket that holds the files the user can see. */
        public val USER_VISIBLE: StorageBucket = WORKSPACE

        /** The bucket for [path], or null when the path names no bucket. */
        public fun bucketOf(path: SandboxPath): StorageBucket? {
            val name = path.bucketName
            return entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
        }
    }
}

/** A file held in the sandbox, as the quota sees it. */
public data class StorageEntry(val path: SandboxPath, val sizeBytes: Long, val lastAccessMs: Long)

/**
 * The storage accounting.
 *
 * Every byte the agent writes is accounted against a bucket, and a write that
 * would push a bucket past its budget is refused rather than allowed and
 * cleaned up later. Eviction is least-recently-used, and the user's workspace
 * is never swept.
 */
public class StorageQuota(private val budgets: Map<StorageBucket, Long> = StorageBucket.entries.associateWith { it.budgetMiB }) {
    private val lock = Any()
    private val entries = mutableMapOf<SandboxPath, StorageEntry>()

    /** The budget of [bucket], in MiB. */
    public fun budgetMiB(bucket: StorageBucket): Long = budgets[bucket] ?: 0L

    /** The bytes held in [bucket]. */
    public fun usedBytes(bucket: StorageBucket): Long = synchronized(lock) {
        entries.values.filter { StorageBucket.bucketOf(it.path) == bucket }.sumOf { it.sizeBytes }
    }

    /** The bytes still writable in [bucket]. */
    public fun availableBytes(bucket: StorageBucket): Long = budgetMiB(bucket) * BYTES_PER_MIB - usedBytes(bucket)

    /** Whether [bytes] would fit in [bucket] right now. */
    public fun wouldExceed(bucket: StorageBucket, bytes: Long): Boolean = usedBytes(bucket) + bytes > budgetMiB(bucket) * BYTES_PER_MIB

    /** Records a file. */
    public fun register(path: SandboxPath, sizeBytes: Long, lastAccessMs: Long): Unit = synchronized(lock) {
        entries[path] = StorageEntry(path, sizeBytes, lastAccessMs)
    }

    /** Records a read, which refreshes the eviction order. */
    public fun touch(path: SandboxPath, lastAccessMs: Long): Unit = synchronized(lock) {
        val existing = entries[path] ?: return@synchronized
        entries[path] = existing.copy(lastAccessMs = lastAccessMs)
    }

    /** Removes a file from the accounting. */
    public fun unregister(path: SandboxPath): Unit = synchronized(lock) {
        entries.remove(path)
    }

    /** The entry for [path], or null. */
    public fun entry(path: SandboxPath): StorageEntry? = synchronized(lock) { entries[path] }

    /**
     * The files to evict to make room for [bytes] in [bucket].
     *
     * The order is least-recently-used first. The user's workspace is never
     * in the list, whatever the pressure.
     */
    public fun evictionCandidates(bucket: StorageBucket, bytes: Long): List<StorageEntry> = synchronized(lock) {
        val candidates = entries.values
            .filter { StorageBucket.bucketOf(it.path) == bucket }
            .filter { bucket.evictable }
            .sortedBy { it.lastAccessMs }

        var freed = 0L
        val selected = mutableListOf<StorageEntry>()
        for (candidate in candidates) {
            if (freed >= bytes) break
            selected += candidate
            freed += candidate.sizeBytes
        }
        selected
    }

    /**
     * The least-recently-used file in [bucket], or null when there is none.
     *
     * This is what the sweep takes one file at a time, so that a write evicts
     * exactly as much as it needs to and no more.
     */
    public fun oldestEvictable(bucket: StorageBucket): StorageEntry? = synchronized(lock) {
        if (!bucket.evictable) return@synchronized null
        entries.values
            .filter { StorageBucket.bucketOf(it.path) == bucket }
            .minByOrNull { it.lastAccessMs }
    }

    /** A per-bucket report. */
    public fun snapshot(): StorageSnapshot = synchronized(lock) {
        StorageSnapshot(
            limitMiB = StorageBucket.TOTAL_LIMIT_MIB,
            usedMiB = entries.values.sumOf { it.sizeBytes } / BYTES_PER_MIB,
            byBucket = StorageBucket.entries.associateWith { usedBytes(it) / BYTES_PER_MIB },
        )
    }

    private companion object {
        const val BYTES_PER_MIB: Long = 1_048_576L
    }
}

/** A per-bucket report of the storage accounting. */
public data class StorageSnapshot(val limitMiB: Long, val usedMiB: Long, val byBucket: Map<StorageBucket, Long>) {
    /** The MiB held in [bucket]. */
    public fun usedBy(bucket: StorageBucket): Long = byBucket[bucket] ?: 0L
}
