package dev.lumen.core.grounding

import dev.lumen.core.model.SettleKind
import kotlin.math.abs
import kotlinx.coroutines.delay

/**
 * The clock of user-interface events.
 *
 * The settle detector asks the clock when the screen last changed. The clock
 * is an interface so that the detector can be tested without a device and
 * without sleeping.
 */
public interface UiEventClock {

    /** The current time. */
    public fun nowMs(): Long

    /** The time of the last interface event of any kind, or null. */
    public fun lastUiEventMs(): Long?

    /** The time of the last scroll event, or null. */
    public fun lastScrollEventMs(): Long?
}

/**
 * The timing constants of the settle detector.
 *
 * These are the numbers in `ARCH.md` §4.2. They are a data class rather
 * than constants so that a test can tighten them, and so that a future
 * device profile can loosen them without touching the algorithm.
 */
public data class SettleSettings(
    /** How long the screen must be quiet after the last change. */
    val quietWindowMs: Long = 160,
    /** How long to wait when nothing has changed at all. */
    val noChangeWindowMs: Long = 90,
    /** How much the node count may move and still count as stable. */
    val nodeCountTolerance: Int = 2,
    /** The minimum gap between two node-count samples. */
    val sampleIntervalMs: Long = 250,
    /** How long the screen must have been observed before a sample is trusted. */
    val observationWindowMs: Long = 400,
    /** How often the detector wakes up. */
    val pollIntervalMs: Long = 25,
    /** The ceiling while the screen is scrolling. */
    val scrollCapMs: Long = 1_500,
    /** The ceiling otherwise. */
    val nonScrollCapMs: Long = 2_000,
    /** How old a cached tree may be before it is read again. */
    val staleTreeGuardMs: Long = 150,
)

/** The outcome of a settle wait. */
public data class SettleResult(
    val snapshot: TreeSnapshot?,
    val settled: Boolean,
    val durationMs: Long,
    val nodeCount: Int,
    val quietMs: Long,
    val reachedCap: Boolean,
)

/**
 * Waits for the screen to stop changing.
 *
 * The detector does not sleep for a fixed period and hope. It watches the
 * event clock and returns as soon as two independent signals agree that the
 * screen is quiet: the event stream has gone quiet, and the shape of the tree
 * has stopped moving.
 *
 * Two clocks are kept apart. Scrolling produces a different event storm from
 * a tap, and a scroll needs a shorter, tighter wait than a page change.
 */
public class SettleDetector(
    private val clock: UiEventClock,
    private val sampler: suspend () -> TreeSnapshot?,
    private val settings: SettleSettings = SettleSettings(),
) {

    /**
     * Waits until the screen settles, or until the cap is reached.
     *
     * @param startedAtMs when the action that caused the change was dispatched
     */
    public suspend fun awaitSettled(startedAtMs: Long, kind: SettleKind): SettleResult {
        val capMs = if (kind == SettleKind.SCROLL) settings.scrollCapMs else settings.nonScrollCapMs
        val doneAt = clock.nowMs()
        return when (val polled = pollUntilQuiet(startedAtMs, kind, capMs, doneAt)) {
            is PollResult.Stable -> polled.result
            is PollResult.Ended -> finish(polled.lastSnapshot, polled.lastCountAt, polled.reachedCap, doneAt)
        }
    }

    /**
     * Watches the event clock until the screen goes quiet, the shape of the
     * tree settles, or the cap is reached.
     *
     * The loop keeps a single exit flag rather than breaking from several
     * points, so the timeout and the quiet signal are decided in the same
     * order they always were: the cap is checked against a fresh sample of the
     * clock before any event is read.
     */
    private suspend fun pollUntilQuiet(startedAtMs: Long, kind: SettleKind, capMs: Long, doneAt: Long): PollResult {
        val wait = SettleWait(startedAtMs, kind, capMs, doneAt)
        var reachedCap = false
        var done = false
        while (!done) {
            when (val tick = evaluateTick(clock.nowMs(), wait)) {
                is TickResult.Cap -> {
                    reachedCap = true
                    done = true
                }

                is TickResult.Quiet -> done = true
                is TickResult.Continue -> delay(settings.pollIntervalMs)
                is TickResult.Stable -> return PollResult.Stable(tick.result)
            }
        }
        return PollResult.Ended(wait.lastSnapshot, wait.lastCountAt, reachedCap)
    }

    /**
     * The decision for one sample of the clock at [now], in the order the
     * wait has always applied them: the cap first, then the event quiet
     * signal, then the tree-shape signal. It records the latest tree sample on
     * [wait] when it takes one.
     */
    private suspend fun evaluateTick(now: Long, wait: SettleWait): TickResult {
        if (now - wait.startedAtMs >= wait.capMs) return TickResult.Cap

        val lastEvent = lastEventFor(wait.kind)
        val changed = lastEvent != null && lastEvent >= wait.startedAtMs
        val quietFor = if (changed) now - (lastEvent ?: wait.doneAt) else now - wait.doneAt
        if (isQuiet(changed, now, wait.doneAt, quietFor)) return TickResult.Quiet

        val stable = stableSnapshot(now, quietFor, wait)
        return if (stable != null) TickResult.Stable(stable) else TickResult.Continue
    }

    /** The outcome of a single settle sample. */
    private sealed interface TickResult {
        data object Cap : TickResult
        data object Quiet : TickResult
        data object Continue : TickResult
        data class Stable(val result: SettleResult) : TickResult
    }

    /** The event stream the detector watches for [kind]: the scroll clock or the general one. */
    private fun lastEventFor(kind: SettleKind): Long? = if (kind == SettleKind.SCROLL) clock.lastScrollEventMs() else clock.lastUiEventMs()

    /**
     * Whether the screen has gone quiet, measured from the last event when one
     * arrived and from the start of the wait when none did — an event that
     * never arrives is itself the signal.
     */
    private fun isQuiet(changed: Boolean, now: Long, doneAt: Long, quietFor: Long): Boolean =
        if (changed) quietFor >= settings.quietWindowMs else now - doneAt >= settings.noChangeWindowMs

    /**
     * The second, independent signal: the shape of the tree sampled on its own
     * schedule. A settled shape is returned as a finished result; otherwise the
     * wait remembers the sample so the next one can be compared against it.
     */
    private suspend fun stableSnapshot(now: Long, quietFor: Long, wait: SettleWait): SettleResult? {
        val shouldSample = wait.kind != SettleKind.SCROLL &&
            now - wait.doneAt >= settings.observationWindowMs &&
            now - wait.lastCountAt >= settings.sampleIntervalMs
        if (!shouldSample) return null

        val sampled = sampler()
        val previous = wait.lastSnapshot
        // A tree that cannot be read is not evidence of a stable screen: two
        // unreadable samples in a row would otherwise look like an unchanged
        // shape and settle the wait.
        val stable = sampled != null &&
            previous != null &&
            abs(sampled.nodeCount - previous.nodeCount) <= settings.nodeCountTolerance
        if (stable) {
            return SettleResult(
                snapshot = requireNotNull(sampled),
                settled = true,
                durationMs = now - wait.doneAt,
                nodeCount = sampled.nodeCount,
                quietMs = quietFor,
                reachedCap = false,
            )
        }
        wait.lastSnapshot = sampled
        wait.lastCountAt = now
        return null
    }

    /**
     * Builds the result for a wait that ended without a settled shape. The
     * cached tree is only trusted when it is recent; a stale tree describes a
     * screen that has moved on and is refreshed instead.
     */
    private suspend fun finish(lastSnapshot: TreeSnapshot?, lastCountAt: Long, reachedCap: Boolean, doneAt: Long): SettleResult {
        val now = clock.nowMs()
        val snapshot = freshTree(lastSnapshot, lastCountAt, now)
        return SettleResult(
            snapshot = snapshot,
            settled = !reachedCap,
            durationMs = now - doneAt,
            nodeCount = snapshot?.nodeCount ?: 0,
            quietMs = now - doneAt,
            reachedCap = reachedCap,
        )
    }

    private suspend fun freshTree(cached: TreeSnapshot?, lastCountAt: Long, now: Long): TreeSnapshot? =
        if (cached == null || now - lastCountAt >= settings.staleTreeGuardMs) sampler() else cached

    /** How the settle loop ended: a settled shape, or the quiet/cap boundary. */
    private sealed interface PollResult {
        data class Stable(val result: SettleResult) : PollResult
        data class Ended(val lastSnapshot: TreeSnapshot?, val lastCountAt: Long, val reachedCap: Boolean) : PollResult
    }

    /**
     * The state of one settle wait: the fixed timing reference (when the
     * action started, which clock, the cap, and the moment the wait began)
     * plus the last tree sample, carried between iterations so the next sample
     * can be compared against it.
     */
    private class SettleWait(val startedAtMs: Long, val kind: SettleKind, val capMs: Long, val doneAt: Long) {
        var lastSnapshot: TreeSnapshot? = null
        var lastCountAt: Long = 0L
    }
}
