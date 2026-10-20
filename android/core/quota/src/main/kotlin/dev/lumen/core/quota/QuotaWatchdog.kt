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
    private val budget: RamBudget,
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

        var measured = initialMeasured
        var pressure = initialPressure
        var evicted = 0
        val escalations = mutableListOf<Escalation>()

        if (initialPressure != PressureLevel.NOMINAL) {
            for (category in RamCategory.EVICTION_LADDER) {
                val evictable = synchronized(lock) { evictables[category] } ?: continue

                val target = targetFor(pressure, measured, budget.limitMiB)
                if (target <= 0) break

                val freed = evictable.evict(target)
                evicted += freed

                if (category == RamCategory.BRAIN_MODEL && freed > 0) {
                    val unloaded = Escalation.UnloadLocalModel(
                        "the local model was unloaded to stay inside the ${budget.limitMiB} MiB cap",
                    )
                    val routed = Escalation.RouteToCloud(
                        "the local model is gone, so inference moves to the cloud",
                    )
                    escalations += unloaded
                    escalations += routed
                    onEscalation(unloaded)
                    onEscalation(routed)
                }

                measured = probe.measuredMiB()
                pressure = PressureLevel.forMeasurement(measured, budget.limitMiB)

                if (pressure == PressureLevel.NOMINAL) break
            }
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
            evictedMiB = evicted,
            escalations = escalations,
            hardStop = hardStop,
        )
        synchronized(lock) { lastTick = tick }
        return tick
    }

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
        return (limit / 10).coerceAtLeast(1)
    }
}
