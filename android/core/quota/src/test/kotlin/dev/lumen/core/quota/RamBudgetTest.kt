package dev.lumen.core.quota

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Test

class RamBudgetTest {

    @Test
    fun `Q-RAM-01 the baseline reservation is accepted`() {
        val budget = RamBudget()

        val result = budget.reserve(RamCategory.BASELINE, 64)

        assertThat(result).isInstanceOf(ReservationResult.Granted::class.java)
        assertThat(budget.usedMiB(RamCategory.BASELINE)).isEqualTo(64)
    }

    @Test
    fun `Q-RAM-02 a reservation beyond the limit is refused`() {
        val budget = RamBudget(limitMiB = 100)

        val first = budget.reserve(RamCategory.BASELINE, 64)
        val second = budget.reserve(RamCategory.TREE_CACHE, 50)

        assertThat(first).isInstanceOf(ReservationResult.Granted::class.java)
        assertThat(second).isInstanceOf(ReservationResult.Refused::class.java)
        val refused = second as ReservationResult.Refused
        assertThat(refused.reason).isEqualTo(RefusalReason.TOTAL_FULL)
        assertThat(refused.availableMiB).isEqualTo(36)
    }

    @Test
    fun `a reservation beyond a category cap is refused with the category reason`() {
        val budget = RamBudget()

        val result = budget.reserve(RamCategory.TREE_CACHE, 193)

        assertThat(result).isInstanceOf(ReservationResult.Refused::class.java)
        assertThat((result as ReservationResult.Refused).reason).isEqualTo(RefusalReason.CATEGORY_FULL)
    }

    @Test
    fun `Q-RAM-03 a model reservation lands in the brain category`() {
        val budget = RamBudget()

        val result = budget.reserve(RamCategory.BRAIN_MODEL, 1_100)

        assertThat(result).isInstanceOf(ReservationResult.Granted::class.java)
        assertThat(budget.usedMiB(RamCategory.BRAIN_MODEL)).isEqualTo(1_100)
        assertThat(budget.usedMiB(RamCategory.TREE_CACHE)).isEqualTo(0)
    }

    @Test
    fun `Q-RAM-08 a released reservation returns the budget to the baseline`() {
        val budget = RamBudget()
        val granted = budget.reserve(RamCategory.FRAME_BUFFER, 128) as ReservationResult.Granted

        budget.release(granted.reservation)

        assertThat(budget.usedMiB(RamCategory.FRAME_BUFFER)).isEqualTo(0)
        assertThat(budget.usedMiB()).isEqualTo(0)
        assertThat(budget.activeReservationCount()).isEqualTo(0)
    }

    @Test
    fun `releasing a reservation twice does not double count`() {
        val budget = RamBudget()
        val granted = budget.reserve(RamCategory.FRAME_BUFFER, 128) as ReservationResult.Granted

        budget.release(granted.reservation)
        budget.release(granted.reservation)

        assertThat(budget.usedMiB()).isEqualTo(0)
    }

    @Test
    fun `a partial release only gives back what was freed`() {
        val budget = RamBudget()
        val granted = budget.reserve(RamCategory.FRAME_BUFFER, 128) as ReservationResult.Granted

        budget.release(granted.reservation, amountMiB = 32)

        assertThat(budget.usedMiB(RamCategory.FRAME_BUFFER)).isEqualTo(96)
        assertThat(budget.activeReservationCount()).isEqualTo(1)
    }

    @Test
    fun `Q-RAM-09 concurrent reservations never over commit`() = runTest {
        // The total cap binds before the category cap here, so this exercises
        // the total rather than the per-category check.
        val budget = RamBudget(limitMiB = 64)
        val attempts = 32

        val results = withContext(Dispatchers.Default) {
            (1..attempts).map {
                async { budget.reserve(RamCategory.FRAME_BUFFER, 32) }
            }.awaitAll()
        }

        val granted = results.count { it is ReservationResult.Granted }

        assertThat(granted).isEqualTo(2)
        assertThat(budget.usedMiB()).isEqualTo(64)
        assertThat(budget.activeReservationCount()).isEqualTo(2)
    }

    @Test
    fun `the snapshot reports every category`() {
        val budget = RamBudget()
        budget.reserve(RamCategory.TREE_CACHE, 100)

        val snapshot = budget.snapshot()

        assertThat(snapshot.limitMiB).isEqualTo(RamCategory.TOTAL_LIMIT_MIB)
        assertThat(snapshot.usedMiB).isEqualTo(100)
        assertThat(snapshot.usedBy(RamCategory.TREE_CACHE)).isEqualTo(100)
        assertThat(snapshot.usedBy(RamCategory.FRAME_BUFFER)).isEqualTo(0)
        assertThat(snapshot.byCategory).hasSize(RamCategory.entries.size)
    }

    @Test
    fun `the category caps leave the specified headroom`() {
        val capped = RamCategory.entries.sumOf { it.capMiB }

        assertThat(capped).isEqualTo(1_880)
        assertThat(RamCategory.TOTAL_LIMIT_MIB - capped).isEqualTo(168)
    }

    @Test
    fun `a non-positive reservation is rejected`() {
        val budget = RamBudget()

        assertThat(runCatching { budget.reserve(RamCategory.BASELINE, 0) }.exceptionOrNull())
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
