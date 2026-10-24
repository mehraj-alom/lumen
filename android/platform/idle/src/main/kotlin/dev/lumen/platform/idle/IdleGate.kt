package dev.lumen.platform.idle

import dev.lumen.core.model.LumenClock
import dev.lumen.core.model.SystemLumenClock

/**
 * What the device is doing.
 *
 * The seam that keeps the idle gate testable on the JVM.
 */
public interface ScreenStateSource {
    /** Whether the screen is on. */
    public fun isScreenOn(): Boolean

    /** Whether the keyguard is locked. */
    public fun isKeyguardLocked(): Boolean
}

/**
 * The record of the last touch.
 *
 * A touch is the strongest signal that the user is present. Any touch
 * resets the idle window, whatever else the device is doing.
 */
public class TouchObserver(private val clock: LumenClock = SystemLumenClock) {
    private val lock = Any()
    private var lastTouchMs: Long? = null

    /** Records a touch. */
    public fun onTouch(atMs: Long = clock.nowMs()): Unit = synchronized(lock) {
        lastTouchMs = atMs
    }

    /** The time of the last touch, or null. */
    public fun lastTouchMs(): Long? = synchronized(lock) { lastTouchMs }

    /** Forgets the last touch. */
    public fun clear(): Unit = synchronized(lock) { lastTouchMs = null }

    /**
     * Whether the device has been untouched for [windowMs].
     *
     * A device that has never been touched is idle. That is the right
     * default: the user has not been using the phone since the agent asked
     * for the screen.
     */
    public fun isIdle(nowMs: Long, windowMs: Long): Boolean {
        val last = lastTouchMs() ?: return true
        return nowMs - last >= windowMs
    }
}

/** The gate's answer. */
public data class IdleDecision(val permitted: Boolean, val reason: String, val waitMs: Long = 0L)

/**
 * The idle gate.
 *
 * A live-surface action is permitted only when the user is not present:
 * the screen is off, or the screen is on and nothing has touched it for
 * the idle window. The screen being off is enough on its own — the user is
 * not looking at it, and a locked device is the safest surface to act on.
 */
public class IdleGate(
    private val screenState: ScreenStateSource,
    private val touchObserver: TouchObserver,
    private val idleWindowMs: Long = DEFAULT_IDLE_WINDOW_MS,
    private val clock: LumenClock = SystemLumenClock,
) {

    /**
     * Whether a live-surface action is permitted right now.
     *
     * The order of the checks is the order of their confidence: an off screen
     * and a locked keyguard are proof that nobody is using the device, an
     * untouched device is next, and only a touch inside the window says the
     * user is present.
     */
    public fun permitsLiveSurface(): IdleDecision {
        if (!screenState.isScreenOn()) return permitted("the screen is off, so the live surface is not in use")
        if (screenState.isKeyguardLocked()) return permitted("the keyguard is locked, so the live surface is not in use")

        val lastTouch = touchObserver.lastTouchMs() ?: return permitted(
            "the device has not been touched, so the live surface is not in use",
        )

        // The clock is read after the touch, so `since` cannot come out negative
        // when the two are sampled a millisecond apart.
        val since = clock.nowMs() - lastTouch
        if (since >= idleWindowMs) {
            return permitted("the device has been untouched for ${since}ms, so the live surface is not in use")
        }

        return IdleDecision(
            permitted = false,
            reason = "the device was touched ${since}ms ago, so the live surface is in use",
            waitMs = idleWindowMs - since,
        )
    }

    /**
     * How long the agent has to wait before the live surface is free.
     */
    public fun waitMs(): Long = permitsLiveSurface().waitMs

    private fun permitted(reason: String): IdleDecision = IdleDecision(permitted = true, reason = reason)

    public companion object {
        /** The default idle window. */
        public const val DEFAULT_IDLE_WINDOW_MS: Long = 60_000L
    }
}
