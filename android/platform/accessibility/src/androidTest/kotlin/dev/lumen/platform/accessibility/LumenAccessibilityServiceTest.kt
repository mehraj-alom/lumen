package dev.lumen.platform.accessibility

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.lumen.core.model.RelativePoint
import kotlinx.coroutines.test.runTest
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The accessibility service's device tests.
 *
 * These need the service to be enabled in Android settings, so they are
 * the one suite that cannot run on the JVM or in a plain CI runner. They
 * run on a device with the service enabled.
 *
 * The two tests that act through the service say so as an assumption rather than
 * an assertion: an emulator that has never had the switch thrown reports them as
 * skipped, which is the truth, instead of failing a build that checked nothing.
 * On a handset with the service enabled the way `OPERATIONS.md` §2.1 describes,
 * they run.
 */
@RunWith(AndroidJUnit4::class)
class LumenAccessibilityServiceTest {

    @Test
    fun I01_theServiceConnectsAndReportsItsState() {
        val service = LumenAccessibilityService.instance
        assumeTrue("the accessibility service is not enabled on this device", service != null)

        // The instance is set in `onServiceConnected`, and there is no public
        // API for the enabled flag in settings, so the bridge's own readiness
        // answer is the state being tested.
        assertThat(service).isNotNull()
        assertThat(service?.bridge?.isReady()).isTrue()
    }

    @Test
    fun I02_aGestureLandsOnTheNodeItWasAimedAt() {
        val service = LumenAccessibilityService.instance
        assumeTrue("the accessibility service is not enabled on this device", service != null)
        if (service == null) return

        runTest {
            val injector = GestureInjector(service) {
                val metrics = service.resources.displayMetrics
                metrics.widthPixels to metrics.heightPixels
            }

            // The middle of the screen is the middle of whatever is in front.
            val dispatched = injector.tap(RelativePoint(0.5), RelativePoint(0.5))

            assertThat(dispatched).isTrue()
        }
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
 * event. The logic is plain enough that the two tests below are the whole of it.
 *
 * They live in the instrumented source set because `AccessibilityEvent.obtain`
 * is a framework method with no JVM body — a plain unit test gets the
 * "not mocked" stub back. The method names carry no spaces for the same reason:
 * DEX only allows them from version 040 (API 33) up, and this module builds for
 * API 26, so a spaced name fails the build at the dexing step, long before the
 * test runs.
 */
class UiEventTrackerTest {

    @Test
    fun aScrollUpdatesBothClocks() {
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
    fun anOrdinaryEventLeavesTheScrollClockAlone() {
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
