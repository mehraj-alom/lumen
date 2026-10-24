package dev.lumen.core.quota

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.sync.Semaphore

/** A failure raised by the quota engine. */
public sealed class QuotaError(message: String) : Exception(message) {

    /** A fifth concurrent worker was requested. */
    public data class WorkerLimitReached(val limit: Int) :
        QuotaError("the agent runs at most $limit workers, and they are all busy")

    /** A reservation was refused. */
    public data class ReservationRefused(val reason: RefusalReason, val requestedMiB: Int, val availableMiB: Int) :
        QuotaError(
            "the request for $requestedMiB MiB was refused ($reason); $availableMiB MiB is available",
        )
}

/**
 * The bounded worker pool.
 *
 * The agent runs at most four worker threads (`AGENTS.md` §2). A fifth
 * concurrent worker is **refused**, not queued: a request that would have to
 * wait is a request that would blow the wall-clock budget, and the caller
 * needs to know that immediately so it can shed load instead.
 */
public class WorkerPool(public val maxWorkers: Int = DEFAULT_MAX_WORKERS) {
    private val semaphore = Semaphore(maxWorkers)
    private val inFlight = AtomicInteger(0)

    init {
        require(maxWorkers > 0) { "the worker limit must be positive" }
    }

    /**
     * Runs [block] on a worker slot.
     *
     * @throws QuotaError.WorkerLimitReached when every slot is taken.
     */
    public suspend fun <T> withWorker(block: suspend () -> T): T {
        if (!semaphore.tryAcquire()) {
            throw QuotaError.WorkerLimitReached(maxWorkers)
        }
        inFlight.incrementAndGet()
        try {
            return block()
        } finally {
            inFlight.decrementAndGet()
            semaphore.release()
        }
    }

    /** The number of workers currently running. */
    public fun depth(): Int = inFlight.get()

    /** The number of worker slots that are free. */
    public fun available(): Int = semaphore.availablePermits

    /** Whether a worker slot is free right now. */
    public fun hasCapacity(): Boolean = semaphore.availablePermits > 0

    public companion object {
        /** The default worker ceiling from `AGENTS.md` §2. */
        public const val DEFAULT_MAX_WORKERS: Int = 4
    }
}
