package dev.lumen.platform.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent
import dev.lumen.core.grounding.UiEventClock
import dev.lumen.core.model.RelativePoint
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * The gesture injector.
 *
 * Every gesture the agent makes goes through here, so there is one place
 * that knows how a tap becomes a [GestureDescription]. A drag replays the
 * path it is given rather than flinging from end to end: the shape of the
 * path is usually the point of the gesture.
 */
public class GestureInjector(private val service: AccessibilityService, private val screenSize: () -> Pair<Int, Int>) {

    /** Taps at a relative position. */
    public suspend fun tap(x: RelativePoint, y: RelativePoint): Boolean = stroke(
        listOf(x to y),
        durationMs = DEFAULT_TAP_MS,
    )

    /** Taps twice at a relative position. */
    public suspend fun doubleTap(x: RelativePoint, y: RelativePoint): Boolean {
        val first = stroke(listOf(x to y), durationMs = DEFAULT_TAP_MS)
        if (!first) return false
        kotlinx.coroutines.delay(DOUBLE_TAP_GAP_MS)
        return stroke(listOf(x to y), durationMs = DEFAULT_TAP_MS)
    }

    /** Presses and holds at a relative position. */
    public suspend fun longPress(x: RelativePoint, y: RelativePoint, durationMs: Long = DEFAULT_LONG_PRESS_MS): Boolean =
        stroke(listOf(x to y), durationMs = durationMs)

    /** Swipes between two relative positions. */
    public suspend fun swipe(
        fromX: RelativePoint,
        fromY: RelativePoint,
        toX: RelativePoint,
        toY: RelativePoint,
        durationMs: Long = DEFAULT_SWIPE_MS,
    ): Boolean = stroke(
        listOf(fromX to fromY, toX to toY),
        durationMs = durationMs,
    )

    /**
     * Drags along a path of relative positions.
     *
     * The points are replayed as given: every one of them is a point on the
     * screen, and the gesture API draws straight segments between them.
     * `Action.Drag` carries an easing for the side that produces the path, so
     * a curve that was meant to be interpolated has to be interpolated before
     * it gets here — the injector does not resample it.
     */
    public suspend fun drag(path: List<RelativePoint>, durationMs: Long = DEFAULT_DRAG_MS): Boolean {
        if (path.size < 2) return false
        val (width, height) = screenSize()
        if (width <= 0 || height <= 0) return false

        val screenPath = Path()
        path.forEachIndexed { index, point ->
            val x = (point.fraction * width).toFloat()
            val y = (point.fraction * height).toFloat()
            if (index == 0) screenPath.moveTo(x, y) else screenPath.lineTo(x, y)
        }

        val gesture = GestureDescription.Builder()
            .addStroke(
                GestureDescription.StrokeDescription(
                    screenPath,
                    0L,
                    durationMs,
                ),
            )
            .build()

        return dispatch(gesture)
    }

    /** A single straight stroke through [points]. */
    private suspend fun stroke(points: List<Pair<RelativePoint, RelativePoint>>, durationMs: Long): Boolean {
        val (width, height) = screenSize()
        if (width <= 0 || height <= 0) return false
        if (points.isEmpty()) return false

        val path = Path()
        points.forEachIndexed { index, (x, y) ->
            val px = (x.fraction * width).toFloat()
            val py = (y.fraction * height).toFloat()
            if (index == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }

        val gesture = GestureDescription.Builder()
            .addStroke(
                GestureDescription.StrokeDescription(
                    path,
                    0L,
                    durationMs,
                ),
            )
            .build()

        return dispatch(gesture)
    }

    /** Hands the gesture to the platform and waits for the outcome. */
    private suspend fun dispatch(gesture: GestureDescription): Boolean = suspendCancellableCoroutine { continuation ->
        val callback = object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription) {
                if (continuation.isActive) continuation.resume(true)
            }

            override fun onCancelled(gestureDescription: GestureDescription) {
                if (continuation.isActive) continuation.resume(false)
            }
        }

        val dispatched = service.dispatchGesture(gesture, callback, null)
        if (!dispatched && continuation.isActive) {
            continuation.resume(false)
        }
    }

    public companion object {
        /** The default duration of a tap. */
        public const val DEFAULT_TAP_MS: Long = 60L

        /** The gap between the two taps of a double tap. */
        public const val DOUBLE_TAP_GAP_MS: Long = 90L

        /** The default duration of a long press. */
        public const val DEFAULT_LONG_PRESS_MS: Long = 600L

        /** The default duration of a swipe. */
        public const val DEFAULT_SWIPE_MS: Long = 300L

        /** The default duration of a drag. */
        public const val DEFAULT_DRAG_MS: Long = 500L
    }
}

/**
 * The event clock.
 *
 * The settle detector asks this when the screen last changed. The tracker
 * keeps two clocks apart, because a scroll produces a different event storm
 * from a tap and needs a tighter wait.
 */
public class UiEventTracker : UiEventClock {

    private val lock = Any()
    private var lastUiEventMs: Long? = null
    private var lastScrollEventMs: Long? = null

    /** Records an event. */
    public fun onEvent(event: AccessibilityEvent?, nowMs: Long) {
        if (event == null) return
        synchronized(lock) {
            lastUiEventMs = nowMs
            if (isScroll(event)) {
                lastScrollEventMs = nowMs
            }
        }
    }

    /** The time of the last event of any kind. */
    public fun lastEventMs(): Long? = synchronized(lock) { lastUiEventMs }

    /** The time of the last scroll event. */
    public fun lastScrollMs(): Long? = synchronized(lock) { lastScrollEventMs }

    override fun nowMs(): Long = System.currentTimeMillis()

    override fun lastUiEventMs(): Long? = synchronized(lock) { lastUiEventMs }

    override fun lastScrollEventMs(): Long? = synchronized(lock) { lastScrollEventMs }

    private fun isScroll(event: AccessibilityEvent): Boolean = when (event.eventType) {
        AccessibilityEvent.TYPE_VIEW_SCROLLED -> true
        else -> false
    }
}
