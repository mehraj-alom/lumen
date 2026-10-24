package dev.lumen.core.quota

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class QuotaWatchdogTest {

    @Test
    fun `Q-RAM-10 a runtime inside the cap is under no pressure`() {
        val runtime = FakeRuntime()
        runtime.add(RamCategory.BASELINE, 64)

        val tick = runtime.watchdog.tick()

        assertThat(tick.pressure).isEqualTo(PressureLevel.NOMINAL)
        assertThat(tick.evictedMiB).isEqualTo(0)
        assertThat(tick.escalations).isEmpty()
        assertThat(tick.hardStop).isNull()
    }

    @Test
    fun `Q-RAM-05 the frame buffers are given up before the tree cache`() {
        val runtime = FakeRuntime()
        runtime.add(RamCategory.FRAME_BUFFER, 128)
        runtime.add(RamCategory.TREE_CACHE, 192)
        runtime.add(RamCategory.BRAIN_MODEL, 1_400)
        runtime.setExtra(300)

        val tick = runtime.watchdog.tick()

        assertThat(tick.pressure).isEqualTo(PressureLevel.CRITICAL)
        assertThat(runtime.evictionOrder.first()).isEqualTo(RamCategory.FRAME_BUFFER)
        assertThat(runtime.evictionOrder).contains(RamCategory.TREE_CACHE)
        assertThat(runtime.evictionOrder).doesNotContain(RamCategory.BRAIN_MODEL)
        assertThat(tick.evictedMiB).isGreaterThan(0)
    }

    @Test
    fun `the local model is not touched when the frames are enough`() {
        val runtime = FakeRuntime()
        runtime.add(RamCategory.FRAME_BUFFER, 128)
        runtime.add(RamCategory.BRAIN_MODEL, 1_400)
        // 1,828 MiB is elevated; freeing the frames brings it back to 1,700.
        runtime.setExtra(300)

        runtime.watchdog.tick()

        assertThat(runtime.component(RamCategory.FRAME_BUFFER).evictCalls).isAtLeast(1)
        assertThat(runtime.component(RamCategory.BRAIN_MODEL).evictCalls).isEqualTo(0)
        assertThat(runtime.component(RamCategory.BRAIN_MODEL).heldMiB).isEqualTo(1_400)
    }

    @Test
    fun `Q-RAM-06 critical pressure unloads the local model and routes to the cloud`() {
        val runtime = FakeRuntime()
        runtime.add(RamCategory.BRAIN_MODEL, 1_400)
        runtime.add(RamCategory.TREE_CACHE, 192)
        runtime.setExtra(400)

        val tick = runtime.watchdog.tick()

        assertThat(runtime.component(RamCategory.BRAIN_MODEL).evictCalls).isAtLeast(1)
        assertThat(tick.escalations.any { it is Escalation.UnloadLocalModel }).isTrue()
        assertThat(tick.escalations.any { it is Escalation.RouteToCloud }).isTrue()
        assertThat(runtime.escalations).hasSize(2)
    }

    @Test
    fun `the escalation is delivered to the listener`() {
        val runtime = FakeRuntime()
        runtime.add(RamCategory.BRAIN_MODEL, 1_400)
        runtime.add(RamCategory.TREE_CACHE, 192)
        runtime.setExtra(400)

        runtime.watchdog.tick()

        assertThat(runtime.escalations.first()).isInstanceOf(Escalation.UnloadLocalModel::class.java)
    }

    @Test
    fun `Q-RAM-07 a runtime past the cap is hard stopped`() {
        val runtime = FakeRuntime()
        runtime.add(RamCategory.BASELINE, 64)
        runtime.setExtra(2_100)

        val tick = runtime.watchdog.tick()

        assertThat(tick.pressure).isEqualTo(PressureLevel.EXCEEDED)
        assertThat(tick.hardStop).isNotNull()
        assertThat(tick.hardStop?.measuredMiB).isEqualTo(2_164)
        assertThat(tick.hardStop?.limitMiB).isEqualTo(RamCategory.TOTAL_LIMIT_MIB)
        assertThat(runtime.hardStops).hasSize(1)
    }

    @Test
    fun `the watchdog reports the last tick`() {
        val runtime = FakeRuntime()
        runtime.add(RamCategory.BASELINE, 64)

        assertThat(runtime.watchdog.lastTick()).isNull()
        runtime.watchdog.tick()
        assertThat(runtime.watchdog.lastTick()).isNotNull()
    }

    @Test
    fun `the pressure thresholds are the specified fractions`() {
        assertThat(PressureLevel.forMeasurement(0, 2_048)).isEqualTo(PressureLevel.NOMINAL)
        assertThat(PressureLevel.forMeasurement(1_740, 2_048)).isEqualTo(PressureLevel.NOMINAL)
        assertThat(PressureLevel.forMeasurement(1_741, 2_048)).isEqualTo(PressureLevel.ELEVATED)
        assertThat(PressureLevel.forMeasurement(1_945, 2_048)).isEqualTo(PressureLevel.ELEVATED)
        assertThat(PressureLevel.forMeasurement(1_946, 2_048)).isEqualTo(PressureLevel.CRITICAL)
        assertThat(PressureLevel.forMeasurement(2_047, 2_048)).isEqualTo(PressureLevel.CRITICAL)
        assertThat(PressureLevel.forMeasurement(2_048, 2_048)).isEqualTo(PressureLevel.EXCEEDED)
        assertThat(PressureLevel.forMeasurement(2_049, 2_048)).isEqualTo(PressureLevel.EXCEEDED)
    }
}

/**
 * A fake runtime: a budget, a set of components that hold memory, and a probe
 * that reports what they hold.
 */
private class FakeRuntime {
    val budget = RamBudget()
    val escalations = mutableListOf<Escalation>()
    val hardStops = mutableListOf<HardStop>()
    val evictionOrder = mutableListOf<RamCategory>()
    private val components = mutableListOf<FakeComponent>()
    private var extraMiB = 0

    val watchdog: QuotaWatchdog = QuotaWatchdog(
        budget = budget,
        probe = MemoryProbe { measuredMiB() },
        onEscalation = { escalations += it },
        onHardStop = { hardStops += it },
    )

    fun add(category: RamCategory, amountMiB: Int): FakeComponent {
        val component = FakeComponent(category, amountMiB, budget) { evictionOrder += it }
        components += component
        watchdog.register(category, component)
        return component
    }

    fun component(category: RamCategory): FakeComponent = components.first { it.category == category }

    fun setExtra(mib: Int) {
        extraMiB = mib
    }

    fun measuredMiB(): Int = components.sumOf { it.heldMiB } + extraMiB
}

private class FakeComponent(
    val category: RamCategory,
    amountMiB: Int,
    private val budget: RamBudget,
    private val onEvict: (RamCategory) -> Unit,
) : Evictable {
    private val reservation: Reservation =
        (budget.reserve(category, amountMiB) as ReservationResult.Granted).reservation

    var heldMiB: Int = amountMiB
    var evictCalls: Int = 0

    override fun evict(targetMiB: Int): Int {
        evictCalls++
        onEvict(category)
        val freed = targetMiB.coerceAtMost(heldMiB)
        heldMiB -= freed
        budget.release(reservation, amountMiB = freed)
        return freed
    }
}
