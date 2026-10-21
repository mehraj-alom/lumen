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
    /** The time of the last raw touch, or null. */
    public fun lastTouchMs(): Long?
}

/**
 * The platform touch source.
 *
 * `InputManager.monitorGesture` is the only way to see a raw touch without
 * a window, and it arrived in API 34. Below that the gate falls back to the
 * accessibility event clock, which catches taps and scrolls but can miss a
 * drag.
 */
public class PlatformTouchSource(context: Context) : TouchSource {

    private val inputManager = context.getSystemService(Context.INPUT_SERVICE) as android.hardware.input.InputManager

    override fun lastTouchMs(): Long? =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            inputManager.latestGestureTimestamp
        } else {
            null
        }
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
