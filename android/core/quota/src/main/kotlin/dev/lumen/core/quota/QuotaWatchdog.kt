package dev.lumen.core.quota

/**
 * The watchdog: the measurement half of the quota engine.
 *
 * The [RamBudget] decides what may be allocated. The watchdog notices when
 * the process actually holds more than it should, and takes the memory back
 * in a defined order. The two together make the cap enforceable rather than
 * merely observed (invariant I10).
 */
public class QuotaWatchdog(
    public val budget: RamBudget,
    private val probe: MemoryProbe,
    private val onEscalation: (Escalation) -> Unit = {},
    private val onHardStop: (HardStop) -> Unit = {},
) {

    private val lock = Any()
    private val evictables = mutableMapOf<RamCategory, Evictable>()
    private var lastTick: QuotaTick? = null

    /** Registers the component that can release [category]'s memory. */
    public fun register(category: RamCategory, evictable: Evictable): Unit = synchronized(lock) {
        evictables[category] = evictable
    }

    /** Removes the component registered for [category]. */
    public fun unregister(category: RamCategory): Unit = synchronized(lock) {
        evictables.remove(category)
    }

    /** A report of the budget. */
    public fun snapshot(): RamSnapshot = budget.snapshot()

    /** The most recent tick, or null when the watchdog has not run yet. */
    public fun lastTick(): QuotaTick? = synchronized(lock) { lastTick }

    /**
     * Measures, and reacts.
     *
     * The order of reactions is fixed: evict in the ladder order until the
     * measurement is back inside the cap, unload the local model when the
     * ladder reaches it and the pressure is still high, and stop the run when
     * the cap has been passed.
     *
     * The reported pressure is the pressure that was *found*, not the pressure
     * that was left behind: the tick is an alarm record, and the alarm was
     * raised.
     */
    public fun tick(): QuotaTick {
        val initialMeasured = probe.measuredMiB()
        val initialPressure = PressureLevel.forMeasurement(initialMeasured, budget.limitMiB)

        val pass = EvictionPass(initialMeasured, initialPressure)
        if (initialPressure != PressureLevel.NOMINAL) {
            walkEvictionLadder(pass)
        }

        // The cap is a hard boundary. Reaching it stops the run, even if the
        // eviction that followed brought the measurement back under the cap.
        val hardStop = if (initialPressure == PressureLevel.EXCEEDED) {
            HardStop(
                reason = "the runtime reached $initialMeasured MiB, past the ${budget.limitMiB} MiB cap",
                measuredMiB = initialMeasured,
                limitMiB = budget.limitMiB,
            ).also { onHardStop(it) }
        } else {
            null
        }

        val tick = QuotaTick(
            measuredMiB = initialMeasured,
            limitMiB = budget.limitMiB,
            pressure = initialPressure,
            evictedMiB = pass.evictedMiB,
            escalations = pass.escalations,
            hardStop = hardStop,
        )
        synchronized(lock) { lastTick = tick }
        return tick
    }

    /**
     * Walks the eviction ladder as far as the pressure still requires.
     *
     * A category that has no registered component is stepped over, because it
     * holds nothing the watchdog was told it can take back.
     */
    private fun walkEvictionLadder(pass: EvictionPass) {
        val ladder = RamCategory.EVICTION_LADDER
        var rung = 0
        var keepWalking = true
        while (keepWalking && rung < ladder.size) {
            keepWalking = evictFrom(ladder[rung], pass)
            rung++
        }
    }

    /**
     * Runs one rung of the ladder, and says whether the walk continues.
     *
     * The walk ends on either boundary: nothing left to reclaim, or the
     * measurement back inside the cap.
     */
    private fun evictFrom(category: RamCategory, pass: EvictionPass): Boolean {
        val evictable = synchronized(lock) { evictables[category] } ?: return true
        val target = targetFor(pass.pressure, pass.measuredMiB, budget.limitMiB)
        if (target <= 0) return false

        val freed = evictable.evict(target)
        pass.evictedMiB += freed
        if (category == RamCategory.BRAIN_MODEL && freed > 0) {
            unloadModelAndRouteToCloud(pass)
        }

        pass.measuredMiB = probe.measuredMiB()
        pass.pressure = PressureLevel.forMeasurement(pass.measuredMiB, budget.limitMiB)
        return pass.pressure != PressureLevel.NOMINAL
    }

    /**
     * Recording the model eviction as an escalation is not optional: unloading
     * the local model changes the run's behaviour, so the route changes with
     * it and both are announced to the listener.
     */
    private fun unloadModelAndRouteToCloud(pass: EvictionPass) {
        val unloaded = Escalation.UnloadLocalModel(
            "the local model was unloaded to stay inside the ${budget.limitMiB} MiB cap",
        )
        val routed = Escalation.RouteToCloud(
            "the local model is gone, so inference moves to the cloud",
        )
        pass.escalations += unloaded
        pass.escalations += routed
        onEscalation(unloaded)
        onEscalation(routed)
    }

    /**
     * The running state of one tick's eviction pass: the measurement the next
     * rung is sized against, and what the walk has given back so far.
     */
    private class EvictionPass(
        var measuredMiB: Int,
        var pressure: PressureLevel,
        var evictedMiB: Int = 0,
        val escalations: MutableList<Escalation> = mutableListOf(),
    )

    /**
     * How much memory this tick should try to release.
     *
     * Under elevation the target is the amount needed to get back under 85 %.
     * Under critical pressure it is the amount needed to get back under 95 %,
     * so the eviction is aggressive but not total. When the measurement is
     * already past the target ceiling the target would be negative, so a
     * fixed slice of the cap is released instead: the eviction has to make
     * progress even when there is no "target" to reach.
     */
    private fun targetFor(pressure: PressureLevel, measured: Int, limit: Int): Int {
        val ceiling = when (pressure) {
            PressureLevel.ELEVATED -> PressureLevel.ELEVATED_AT
            PressureLevel.CRITICAL -> PressureLevel.CRITICAL_AT
            PressureLevel.EXCEEDED -> 1.0
            PressureLevel.NOMINAL -> return 0
        }
        val target = (limit * ceiling).toInt() - measured
        if (target > 0) return target
        return (limit / FALLBACK_SLICE_OF_CAP).coerceAtLeast(1)
    }

    private companion object {
        /**
         * The cap is divided into this many slices, and one whole slice is
         * released when there is no reachable target left to aim at.
         */
        const val FALLBACK_SLICE_OF_CAP: Int = 10
    }
}
