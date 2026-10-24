package dev.lumen.platform.idle

import com.google.common.truth.Truth.assertThat
import dev.lumen.core.model.ManualClock
import org.junit.Test

class IdleGateTest {

    private val clock = ManualClock(1_000L)

    private class FakeScreenState(var screenOn: Boolean = true, var keyguardLocked: Boolean = false) : ScreenStateSource {
        override fun isScreenOn(): Boolean = screenOn
        override fun isKeyguardLocked(): Boolean = keyguardLocked
    }

    private fun gate(state: FakeScreenState, observer: TouchObserver, windowMs: Long = 60_000L) = IdleGate(state, observer, windowMs, clock)

    @Test
    fun `a screen that is off permits the live surface`() {
        val state = FakeScreenState(screenOn = false)
        val observer = TouchObserver(clock)
        observer.onTouch(clock.nowMs())

        val decision = gate(state, observer).permitsLiveSurface()

        assertThat(decision.permitted).isTrue()
    }

    @Test
    fun `a locked keyguard permits the live surface`() {
        val state = FakeScreenState(keyguardLocked = true)
        val observer = TouchObserver(clock)
        observer.onTouch(clock.nowMs())

        val decision = gate(state, observer).permitsLiveSurface()

        assertThat(decision.permitted).isTrue()
    }

    @Test
    fun `a device that has never been touched is idle`() {
        val state = FakeScreenState()
        val observer = TouchObserver(clock)

        val decision = gate(state, observer).permitsLiveSurface()

        assertThat(decision.permitted).isTrue()
    }

    @Test
    fun `a recent touch blocks the live surface`() {
        val state = FakeScreenState()
        val observer = TouchObserver(clock)
        observer.onTouch(clock.nowMs())

        val decision = gate(state, observer).permitsLiveSurface()

        assertThat(decision.permitted).isFalse()
        assertThat(decision.waitMs).isGreaterThan(0L)
    }

    @Test
    fun `a touch older than the window permits the live surface`() {
        val state = FakeScreenState()
        val observer = TouchObserver(clock)
        observer.onTouch(clock.nowMs())

        clock.advance(60_000L)
        val decision = gate(state, observer).permitsLiveSurface()

        assertThat(decision.permitted).isTrue()
    }

    @Test
    fun `the wait reports how long until the surface is free`() {
        val state = FakeScreenState()
        val observer = TouchObserver(clock)
        observer.onTouch(clock.nowMs())

        clock.advance(10_000L)
        val decision = gate(state, observer).permitsLiveSurface()

        assertThat(decision.permitted).isFalse()
        assertThat(decision.waitMs).isEqualTo(50_000L)
    }

    @Test
    fun `the observer forgets a touch when it is cleared`() {
        val observer = TouchObserver(clock)
        observer.onTouch(clock.nowMs())
        assertThat(observer.lastTouchMs()).isNotNull()

        observer.clear()

        assertThat(observer.lastTouchMs()).isNull()
    }
}
