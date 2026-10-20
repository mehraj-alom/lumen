package dev.lumen.core.quota

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ThermalGovernorTest {

    @Test
    fun `Q-THM-01 a device at 42 degrees drops to one worker`() {
        val governor = ThermalGovernor()

        val decision = governor.decide(ThermalReading(temperatureCelsius = 42.0))

        assertThat(decision.workerLimit).isEqualTo(1)
        assertThat(decision.localInferenceAllowed).isTrue()
    }

    @Test
    fun `Q-THM-02 a device at 46 degrees suspends local inference`() {
        val governor = ThermalGovernor()

        val decision = governor.decide(ThermalReading(temperatureCelsius = 46.0))

        assertThat(decision.workerLimit).isEqualTo(1)
        assertThat(decision.localInferenceAllowed).isFalse()
    }

    @Test
    fun `a cool device uses the full worker pool`() {
        val governor = ThermalGovernor()

        val decision = governor.decide(ThermalReading(temperatureCelsius = 30.0))

        assertThat(decision.workerLimit).isEqualTo(4)
        assertThat(decision.localInferenceAllowed).isTrue()
    }

    @Test
    fun `a critical thermal status suspends local inference even when the temperature is low`() {
        val governor = ThermalGovernor()

        val decision = governor.decide(
            ThermalReading(temperatureCelsius = 35.0, status = ThermalStatus.CRITICAL),
        )

        assertThat(decision.localInferenceAllowed).isFalse()
    }

    @Test
    fun `the thresholds are inclusive`() {
        val governor = ThermalGovernor()

        assertThat(governor.decide(ThermalReading(temperatureCelsius = 41.9)).workerLimit).isEqualTo(4)
        assertThat(governor.decide(ThermalReading(temperatureCelsius = 42.0)).workerLimit).isEqualTo(1)
        assertThat(governor.decide(ThermalReading(temperatureCelsius = 45.9)).localInferenceAllowed).isTrue()
        assertThat(governor.decide(ThermalReading(temperatureCelsius = 46.0)).localInferenceAllowed).isFalse()
    }
}

class BatteryGovernorTest {

    @Test
    fun `a charged device proceeds`() {
        val governor = BatteryGovernor()

        val decision = governor.decide(BatteryReading(levelPercent = 5, isCharging = true))

        assertThat(decision).isEqualTo(BatteryDecision.Proceed)
    }

    @Test
    fun `a full battery proceeds`() {
        val governor = BatteryGovernor()

        val decision = governor.decide(BatteryReading(levelPercent = 100, isCharging = false))

        assertThat(decision).isEqualTo(BatteryDecision.Proceed)
    }

    @Test
    fun `a low battery that is not charging suspends the run`() {
        val governor = BatteryGovernor()

        val decision = governor.decide(BatteryReading(levelPercent = 19, isCharging = false))

        assertThat(decision).isInstanceOf(BatteryDecision.Suspend::class.java)
    }

    @Test
    fun `the threshold is inclusive`() {
        val governor = BatteryGovernor()

        assertThat(governor.decide(BatteryReading(levelPercent = 20, isCharging = false)))
            .isEqualTo(BatteryDecision.Proceed)
        assertThat(governor.decide(BatteryReading(levelPercent = 19, isCharging = false)))
            .isInstanceOf(BatteryDecision.Suspend::class.java)
    }
}

class WorkerPoolTest {

    @Test
    fun `Q-THM-04 a fifth concurrent worker is refused`() = runTest {
        val pool = WorkerPool(maxWorkers = 4)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        val holders = (1..4).map {
            async {
                pool.withWorker {
                    started.complete(Unit)
                    release.await()
                }
            }
        }
        started.await()
        runCurrent()

        val failure = runCatching { pool.withWorker { "never" } }.exceptionOrNull()

        assertThat(failure).isInstanceOf(QuotaError.WorkerLimitReached::class.java)
        assertThat(pool.depth()).isEqualTo(4)
        assertThat(pool.available()).isEqualTo(0)

        release.complete(Unit)
        holders.awaitAll()
        assertThat(pool.depth()).isEqualTo(0)
        assertThat(pool.available()).isEqualTo(4)
    }

    @Test
    fun `a worker slot is released when the block throws`() = runTest {
        val pool = WorkerPool(maxWorkers = 1)

        runCatching { pool.withWorker { throw IllegalStateException("boom") } }

        assertThat(pool.depth()).isEqualTo(0)
        assertThat(pool.hasCapacity()).isTrue()
    }

    @Test
    fun `the pool reports its depth while it is busy`() = runTest {
        val pool = WorkerPool(maxWorkers = 2)
        val release = CompletableDeferred<Unit>()

        val holder = async {
            pool.withWorker {
                release.await()
            }
        }
        runCurrent()

        assertThat(pool.depth()).isEqualTo(1)
        assertThat(pool.available()).isEqualTo(1)

        release.complete(Unit)
        holder.await()
    }
}
