package dev.lumen.core.model

/**
 * The source of time.
 *
 * Production code never calls [System.currentTimeMillis] directly. The clock is
 * injected so that a run can be replayed, so that a test can advance time
 * without sleeping, and so that ordering never depends on the wall clock
 * (invariant I8).
 */
public fun interface LumenClock {

    /** The current time in Unix epoch milliseconds. */
    public fun nowMs(): Long
}

/** The production [LumenClock], backed by the system wall clock. */
public object SystemLumenClock : LumenClock {
    override fun nowMs(): Long = System.currentTimeMillis()
}

/**
 * A [LumenClock] that never moves on its own.
 *
 * Used by tests and by the deterministic replay tooling.
 */
public class ManualClock(private var current: Long = 0L) : LumenClock {

    override fun nowMs(): Long = current

    /** Moves the clock forward. */
    public fun advance(deltaMs: Long) {
        require(deltaMs >= 0) { "a clock cannot move backwards" }
        current += deltaMs
    }

    /** Sets the clock to an absolute time. */
    public fun setTo(millis: Long) {
        require(millis >= 0) { "a clock cannot move backwards" }
        current = millis
    }
}
