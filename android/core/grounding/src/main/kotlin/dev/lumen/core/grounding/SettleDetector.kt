package dev.lumen.core.grounding

import dev.lumen.core.model.SettleKind
import kotlinx.coroutines.delay
import kotlin.math.abs

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

        var lastSnapshot: TreeSnapshot? = null
        var lastCount = -1
        var lastCountAt = 0L
        var reachedCap = false

        while (true) {
            val now = clock.nowMs()
            if (now - startedAtMs >= capMs) {
                reachedCap = true
                break
            }

            val lastEvent = if (kind == SettleKind.SCROLL) {
                clock.lastScrollEventMs()
            } else {
                clock.lastUiEventMs()
            }
            val changed = lastEvent != null && lastEvent >= startedAtMs

            // Two independent signals that the screen has stopped moving.
            // The quiet window is measured from the last event. The
            // no-change window is measured from the start of the wait,
            // because an event that never arrives is itself the signal.
            val quietFor = if (changed) now - lastEvent else now - doneAt
            val isQuiet = (!changed && now - doneAt >= settings.noChangeWindowMs) ||
                (changed && quietFor >= settings.quietWindowMs)
            if (isQuiet) break

            // The shape of the tree is a second signal, sampled on its own
            // schedule. It is measured from the start of the wait, so a
            // screen that keeps emitting events but keeps the same shape is
            // recognised as stable.
            if (kind != SettleKind.SCROLL &&
                now - doneAt >= settings.observationWindowMs &&
                now - lastCountAt >= settings.sampleIntervalMs
            ) {
                val sampled = sampler()
                val count = sampled?.nodeCount ?: 0
                if (lastCount >= 0 && abs(count - lastCount) <= settings.nodeCountTolerance) {
                    return SettleResult(
                        snapshot = sampled,
                        settled = true,
                        durationMs = now - doneAt,
                        nodeCount = count,
                        quietMs = quietFor,
                        reachedCap = false,
                    )
                }
                lastCount = count
                lastSnapshot = sampled
                lastCountAt = now
            }

            delay(settings.pollIntervalMs)
        }

        // The cached tree is only trusted if it is recent. A stale tree is
        // worse than no tree: it describes a screen that has moved on.
        val now = clock.nowMs()
        val cached = lastSnapshot
        val snapshot = if (cached == null || now - lastCountAt >= settings.staleTreeGuardMs) {
            sampler()
        } else {
            cached
        }

        return SettleResult(
            snapshot = snapshot,
            settled = !reachedCap,
            durationMs = now - doneAt,
            nodeCount = snapshot?.nodeCount ?: 0,
            quietMs = now - doneAt,
            reachedCap = reachedCap,
        )
    }
}
