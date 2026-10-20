package dev.lumen.core.quota

/**
 * A granted claim on the RAM budget.
 *
 * The holder releases it when the memory is actually freed. A reservation
 * that is never released is a leak, and the budget is exact: it never
 * over-commits.
 */
public data class Reservation(
    val id: Long,
    val category: RamCategory,
    val amountMiB: Int,
)

/** Why a reservation was refused. */
public enum class RefusalReason {
    /** The category itself is full. */
    CATEGORY_FULL,

    /** The categories together would exceed the total cap. */
    TOTAL_FULL,
}

/** The outcome of a reservation request. */
public sealed interface ReservationResult {

    /** The memory is reserved. */
    public data class Granted(val reservation: Reservation) : ReservationResult

    /** The memory is not available. The caller must not allocate. */
    public data class Refused(
        val reason: RefusalReason,
        val requestedMiB: Int,
        val availableMiB: Int,
    ) : ReservationResult
}

/**
 * The RAM budget: an exact ledger of what the agent has claimed.
 *
 * This is the *accounting* half of the quota engine. The watchdog is the
 * *measurement* half. A reservation is the only way to obtain memory, so a
 * model is never loaded "and we'll see" — it is admitted or it is not
 * (invariant I10).
 */
public class RamBudget(
    public val limitMiB: Int = RamCategory.TOTAL_LIMIT_MIB,
) {
    private val lock = Any()
    private val usedByCategory = mutableMapOf<RamCategory, Int>()
    private val remainingByReservation = mutableMapOf<Long, Int>()
    private var nextReservationId = 1L

    init {
        require(limitMiB > 0) { "the RAM limit must be positive" }
    }

    /**
     * Claims [amountMiB] for [category].
     *
     * The check is admission control: it either grants the memory or refuses
     * it before anything is allocated.
     */
    public fun reserve(category: RamCategory, amountMiB: Int): ReservationResult = synchronized(lock) {
        require(amountMiB > 0) { "a reservation must be positive, got $amountMiB" }

        val categoryUsed = usedByCategory[category] ?: 0
        val categoryAvailable = category.capMiB - categoryUsed
        if (amountMiB > categoryAvailable) {
            return@synchronized ReservationResult.Refused(
                reason = RefusalReason.CATEGORY_FULL,
                requestedMiB = amountMiB,
                availableMiB = categoryAvailable.coerceAtLeast(0),
            )
        }

        val totalUsed = usedByCategory.values.sum()
        val totalAvailable = limitMiB - totalUsed
        if (amountMiB > totalAvailable) {
            return@synchronized ReservationResult.Refused(
                reason = RefusalReason.TOTAL_FULL,
                requestedMiB = amountMiB,
                availableMiB = totalAvailable.coerceAtLeast(0),
            )
        }

        val reservation = Reservation(
            id = nextReservationId++,
            category = category,
            amountMiB = amountMiB,
        )
        remainingByReservation[reservation.id] = amountMiB
        usedByCategory[category] = categoryUsed + amountMiB
        ReservationResult.Granted(reservation)
    }

    /**
     * Gives the memory back.
     *
     * Releasing a reservation twice is not an error and does not double-count
     * the release: the budget returns to its previous total either way.
     *
     * @param amountMiB how much of the reservation to release. A partial
     *   release is used when only part of the memory was actually freed.
     */
    public fun release(reservation: Reservation, amountMiB: Int = reservation.amountMiB): Unit =
        synchronized(lock) {
            val remaining = remainingByReservation[reservation.id] ?: return@synchronized
            val released = amountMiB.coerceAtMost(remaining)
            if (released <= 0) return@synchronized

            remainingByReservation[reservation.id] = remaining - released
            if (remaining == released) {
                remainingByReservation.remove(reservation.id)
            }

            val categoryUsed = usedByCategory[reservation.category] ?: 0
            usedByCategory[reservation.category] = (categoryUsed - released).coerceAtLeast(0)
        }

    /** The memory claimed by [category], in MiB. */
    public fun usedMiB(category: RamCategory): Int = synchronized(lock) {
        usedByCategory[category] ?: 0
    }

    /** The memory claimed across every category, in MiB. */
    public fun usedMiB(): Int = synchronized(lock) { usedByCategory.values.sum() }

    /** The memory still claimable, in MiB. */
    public fun availableMiB(): Int = synchronized(lock) { limitMiB - usedByCategory.values.sum() }

    /** The memory still claimable in [category], in MiB. */
    public fun availableMiB(category: RamCategory): Int = synchronized(lock) {
        category.capMiB - (usedByCategory[category] ?: 0)
    }

    /** The number of reservations that have not been fully released. */
    public fun activeReservationCount(): Int = synchronized(lock) { remainingByReservation.size }

    /** A per-category report of the budget. */
    public fun snapshot(): RamSnapshot = synchronized(lock) {
        RamSnapshot(
            limitMiB = limitMiB,
            usedMiB = usedByCategory.values.sum(),
            byCategory = RamCategory.entries.associateWith { category ->
                usedByCategory[category] ?: 0
            },
        )
    }
}

/** A per-category report of the RAM budget. */
public data class RamSnapshot(
    val limitMiB: Int,
    val usedMiB: Int,
    val byCategory: Map<RamCategory, Int>,
) {
    /** The memory claimed by [category], in MiB. */
    public fun usedBy(category: RamCategory): Int = byCategory[category] ?: 0

    /** The fraction of the cap in use, between 0 and 1. */
    public val utilisation: Double
        get() = if (limitMiB == 0) 0.0 else usedMiB.toDouble() / limitMiB.toDouble()
}
