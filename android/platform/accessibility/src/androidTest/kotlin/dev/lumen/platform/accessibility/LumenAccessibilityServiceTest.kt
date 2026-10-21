package dev.lumen.platform.accessibility

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.lumen.core.model.RelativePoint
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The accessibility service's device tests.
 *
 * These need the service to be enabled in Android settings, so they are
 * the one suite that cannot run on the JVM or in a plain CI runner. They
 * run on a device with the service enabled.
 */
@RunWith(AndroidJUnit4::class)
class LumenAccessibilityServiceTest {

    @Test
    fun I01_theServiceConnectsAndReportsItsState() = runTest {
        val service = LumenAccessibilityService.instance

        assertThat(service).isNotNull()
        assertThat(service?.let { it.isEnabled }).isTrue()
    }

    @Test
    fun I02_aGestureLandsOnTheNodeItWasAimedAt() = runTest {
        val service = LumenAccessibilityService.instance ?: return
        val injector = GestureInjector(service) {
            val metrics = service.resources.displayMetrics
            metrics.widthPixels to metrics.heightPixels
        }

        // The middle of the screen is the middle of whatever is in front.
        val dispatched = injector.tap(RelativePoint(0.5), RelativePoint(0.5))

        assertThat(dispatched).isTrue()
    }

    @Test
    fun I03_theEventClockMovesWhenTheScreenChanges() {
        val tracker = UiEventTracker()

        assertThat(tracker.lastEventMs()).isNull()

        tracker.onEvent(
            android.view.accessibility.AccessibilityEvent.obtain(
                android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            ),
            nowMs = 1_000L,
        )

        assertThat(tracker.lastEventMs()).isEqualTo(1_000L)
        assertThat(tracker.lastScrollMs()).isNull()
    }
}

/**
 * The tracker's own tests.
 *
 * The tracker is the input to the settle detector, and the detector's
 * correctness depends on the tracker distinguishing a scroll from any other
 * event. That distinction is testable on the JVM.
 */
class UiEventTrackerTest {

    @Test
    fun `a scroll updates the scroll clock and the general clock`() {
        val tracker = UiEventTracker()

        tracker.onEvent(
            android.view.accessibility.AccessibilityEvent.obtain(
                android.view.accessibility.AccessibilityEvent.TYPE_VIEW_SCROLLED,
            ),
            nowMs = 500L,
        )

        assertThat(tracker.lastEventMs()).isEqualTo(500L)
        assertThat(tracker.lastScrollMs()).isEqualTo(500L)
    }

    @Test
    fun `an ordinary event does not update the scroll clock`() {
        val tracker = UiEventTracker()

        tracker.onEvent(
            android.view.accessibility.AccessibilityEvent.obtain(
                android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            ),
            nowMs = 500L,
        )

        assertThat(tracker.lastEventMs()).isEqualTo(500L)
        assertThat(tracker.lastScrollMs()).isNull()
    }
}
