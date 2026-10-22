package dev.lumen.platform.idle

import android.app.KeyguardManager
import android.content.Context
import android.os.PowerManager
import android.view.Display
import android.view.WindowManager

/**
 * The device's screen and keyguard state, read from the platform.
 */
public class PlatformScreenState(context: Context) : ScreenStateSource {

    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    override fun isScreenOn(): Boolean = powerManager.isInteractive

    override fun isKeyguardLocked(): Boolean = keyguardManager.isKeyguardLocked
}

/**
 * The touch source, read from the platform.
 *
 * A raw touch is the strongest signal that the user is present, so it is
 * worth the effort to get it exactly rather than inferring it from
 * accessibility events, which a swipe may not produce.
 */
public interface TouchSource {
    /** The time of the last touch, or null. */
    public fun lastTouchMs(): Long?
}

/**
 * The platform touch source, backed by the accessibility event clock.
 *
 * Android has no public API for a raw touch outside a window:
 * `InputManager.monitorGesture` and `latestGestureTimestamp` are not public
 * API. The signal therefore comes from the accessibility event clock, which
 * sees every tap, scroll and text change.
 *
 * The gap is honest and it is documented: a drag that produces no
 * accessibility event is not seen, so the idle window may expire while the
 * user is mid-drag. The window is a guard against the common case, not a
 * proof that nobody is touching the screen.
 */
public class PlatformTouchSource(
    private val lastEvent: () -> Long?,
) : TouchSource {
    override fun lastTouchMs(): Long? = lastEvent()
}

/**
 * The touch observer, fed by the platform.
 *
 * The observer is the one place that knows how a raw touch is discovered,
 * so the gate itself stays a pure function of the last touch.
 */
public class PlatformTouchObserver(
    private val observer: TouchObserver,
    private val source: TouchSource,
) {
    /**
     * Folds the platform's latest touch into the observer.
     *
     * @return whether a new touch was seen
     */
    public fun refresh(): Boolean {
        val last = source.lastTouchMs() ?: return false
        val known = observer.lastTouchMs()
        if (known != null && last <= known) return false
        observer.onTouch(last)
        return true
    }
}

/** The display the agent is acting on. */
public fun Context.defaultDisplayId(): Int = Display.DEFAULT_DISPLAY
