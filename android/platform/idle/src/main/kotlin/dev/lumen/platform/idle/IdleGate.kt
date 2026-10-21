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
public class TouchObserver(
    private val clock: LumenClock = SystemLumenClock,
) {
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
public data class IdleDecision(
    val permitted: Boolean,
    val reason: String,
    val waitMs: Long = 0L,
)

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

    /** Whether a live-surface action is permitted right now. */
    public fun permitsLiveSurface(): IdleDecision {
        val now = clock.nowMs()

        if (!screenState.isScreenOn()) {
            return IdleDecision(
                permitted = true,
                reason = "the screen is off, so the live surface is not in use",
            )
        }

        if (screenState.isKeyguardLocked()) {
            return IdleDecision(
                permitted = true,
                reason = "the keyguard is locked, so the live surface is not in use",
            )
        }

        val lastTouch = touchObserver.lastTouchMs()
        if (lastTouch == null) {
            return IdleDecision(
                permitted = true,
                reason = "the device has not been touched, so the live surface is not in use",
            )
        }

        val since = now - lastTouch
        if (since >= idleWindowMs) {
            return IdleDecision(
                permitted = true,
                reason = "the device has been untouched for ${since}ms, so the live surface is not in use",
            )
        }

        val wait = idleWindowMs - since
        return IdleDecision(
            permitted = false,
            reason = "the device was touched ${since}ms ago, so the live surface is in use",
            waitMs = wait,
        )
    }

    /** How long the agent has to wait before the live surface is free. */
    public fun waitMs(): Long = permitsLiveSurface().waitMs

    public companion object {
        /** The default idle window. */
        public const val DEFAULT_IDLE_WINDOW_MS: Long = 60_000L
    }
}
