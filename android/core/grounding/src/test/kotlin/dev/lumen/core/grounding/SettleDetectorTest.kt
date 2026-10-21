package dev.lumen.core.grounding

import dev.lumen.core.grounding.fixtures.SyntheticTreeBuilder
import dev.lumen.core.model.SettleKind
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import org.junit.Test
import com.google.common.truth.Truth.assertThat

/** A clock the test drives. */
internal class FakeUiEventClock(private val now: () -> Long) : UiEventClock {
    var uiEventAt: Long? = null
    var scrollEventAt: Long? = null

    override fun nowMs(): Long = now()

    override fun lastUiEventMs(): Long? = uiEventAt

    override fun lastScrollEventMs(): Long? = scrollEventAt
}

class SettleDetectorTest {

    private fun snapshot(size: Int): TreeSnapshot {
        val builder = SyntheticTreeBuilder()
        val root = builder.root()
        repeat(size) { index -> builder.child(parent = root, className = "android.widget.TextView", text = "n$index") }
        return builder.build()
    }

    @Test
    fun `T-SET-01 the detector returns 160ms after the last event`() = runTest {
        val clock = FakeUiEventClock { testScheduler.currentTime }
        clock.uiEventAt = 0
        val detector = SettleDetector(clock, sampler = { snapshot(10) })

        val result = detector.awaitSettled(startedAtMs = 0, kind = SettleKind.NON_SCROLL)

        assertThat(result.settled).isTrue()
        assertThat(result.durationMs).isAtLeast(160)
        assertThat(result.durationMs).isLessThan(400)
    }

    @Test
    fun `T-SET-02 the detector returns 90ms when nothing changes at all`() = runTest {
        val clock = FakeUiEventClock { testScheduler.currentTime }
        val detector = SettleDetector(clock, sampler = { snapshot(10) })

        val result = detector.awaitSettled(startedAtMs = 0, kind = SettleKind.NON_SCROLL)

        assertThat(result.settled).isTrue()
        assertThat(result.durationMs).isAtLeast(90)
        assertThat(result.durationMs).isLessThan(160)
    }

    @Test
    fun `T-SET-03 continuous events run the detector into the cap`() = runTest {
        val clock = FakeUiEventClock { testScheduler.currentTime }
        val ticker = launch {
            while (true) {
                clock.uiEventAt = testScheduler.currentTime
                delay(20)
            }
        }
        val detector = SettleDetector(clock, sampler = { snapshot(10) })

        val result = detector.awaitSettled(startedAtMs = 0, kind = SettleKind.NON_SCROLL)

        ticker.cancel()
        assertThat(result.settled).isFalse()
        assertThat(result.reachedCap).isTrue()
        assertThat(result.durationMs).isAtLeast(2_000)
    }

    @Test
    fun `T-SET-04 a stable tree shape returns before the cap`() = runTest {
        val clock = FakeUiEventClock { testScheduler.currentTime }
        val ticker = launch {
            while (true) {
                clock.uiEventAt = testScheduler.currentTime
                delay(20)
            }
        }
        val detector = SettleDetector(clock, sampler = { snapshot(10) })

        val result = detector.awaitSettled(startedAtMs = 0, kind = SettleKind.NON_SCROLL)

        ticker.cancel()
        assertThat(result.settled).isTrue()
        assertThat(result.reachedCap).isFalse()
        assertThat(result.durationMs).isLessThan(2_000)
    }

    @Test
    fun `T-SET-05 a churning tree shape does not return early`() = runTest {
        val clock = FakeUiEventClock { testScheduler.currentTime }
        val ticker = launch {
            while (true) {
                clock.uiEventAt = testScheduler.currentTime
                delay(20)
            }
        }
        var calls = 0
        val detector = SettleDetector(clock, sampler = { snapshot(10 + calls++ * 5) })

        val result = detector.awaitSettled(startedAtMs = 0, kind = SettleKind.NON_SCROLL)

        ticker.cancel()
        assertThat(result.settled).isFalse()
        assertThat(result.reachedCap).isTrue()
    }

    @Test
    fun `T-SET-06 a stale cached tree is refreshed rather than returned`() = runTest {
        val clock = FakeUiEventClock { testScheduler.currentTime }
        val ticker = launch {
            while (true) {
                clock.uiEventAt = testScheduler.currentTime
                if (testScheduler.currentTime >= 400) break
                delay(20)
            }
        }
        var calls = 0
        val detector = SettleDetector(clock, sampler = { snapshot(10 + calls++ * 5) })

        val result = detector.awaitSettled(startedAtMs = 0, kind = SettleKind.NON_SCROLL)

        ticker.cancel()
        assertThat(calls).isAtLeast(2)
        assertThat(result.snapshot?.nodeCount).isEqualTo(snapshot(10 + (calls - 1) * 5).nodeCount)
    }

    @Test
    fun `T-SET-07 a scroll uses the scroll clock`() = runTest {
        val clock = FakeUiEventClock { testScheduler.currentTime }
        clock.scrollEventAt = 0
        val detector = SettleDetector(clock, sampler = { snapshot(10) })

        val result = detector.awaitSettled(startedAtMs = 0, kind = SettleKind.SCROLL)

        // The interface clock never moved, so a detector that used it would
        // have returned after 90ms. 160ms means the scroll clock was used.
        assertThat(result.durationMs).isAtLeast(160)
    }

    @Test
    fun `T-SET-08 cancelling the detector stops it promptly`() = runTest {
        val clock = FakeUiEventClock { testScheduler.currentTime }
        val ticker = launch {
            while (true) {
                clock.uiEventAt = testScheduler.currentTime
                delay(20)
            }
        }
        val detector = SettleDetector(clock, sampler = { snapshot(10) })

        val job = launch { detector.awaitSettled(startedAtMs = 0, kind = SettleKind.NON_SCROLL) }
        delay(100)
        job.cancelAndJoin()
        ticker.cancel()

        assertThat(job.isCompleted).isTrue()
        assertThat(job.isCancelled).isTrue()
    }

    @Test
    fun `the detector always returns within its cap`() = runTest {
        repeat(40) { seed ->
            val random = Random(seed)
            val clock = FakeUiEventClock { testScheduler.currentTime }
            val ticker = launch {
                while (true) {
                    if (random.nextInt(3) == 0) {
                        clock.uiEventAt = testScheduler.currentTime
                    }
                    delay(random.nextInt(30) + 1)
                }
            }
            var calls = 0
            val detector = SettleDetector(clock, sampler = { snapshot(random.nextInt(20)) })

            val result = detector.awaitSettled(startedAtMs = 0, kind = SettleKind.NON_SCROLL)

            ticker.cancel()
            assertThat(result.durationMs).isAtMost(2_000)
            assertThat(result.reachedCap).implies(result.durationMs >= 2_000)
            calls++
        }
    }

    @Test
    fun `the scroll cap is shorter than the general cap`() {
        val settings = SettleSettings()

        assertThat(settings.scrollCapMs).isEqualTo(1_500)
        assertThat(settings.nonScrollCapMs).isEqualTo(2_000)
        assertThat(settings.quietWindowMs).isEqualTo(160)
        assertThat(settings.noChangeWindowMs).isEqualTo(90)
        assertThat(settings.nodeCountTolerance).isEqualTo(2)
        assertThat(settings.sampleIntervalMs).isEqualTo(250)
        assertThat(settings.observationWindowMs).isEqualTo(400)
        assertThat(settings.pollIntervalMs).isEqualTo(25)
        assertThat(settings.staleTreeGuardMs).isEqualTo(150)
    }
}
