package dev.lumen.core.quota

/**
 * The measured memory footprint of the agent runtime.
 *
 * The platform implementation lives outside `core`; this is the seam that
 * keeps the quota engine testable on the JVM.
 */
public fun interface MemoryProbe {

    /**
     * The resident memory of every process the agent owns, in MiB.
     *
     * The figure is the sum of the PSS of the agent's processes, which is the
     * number the operating system would charge the agent for.
     */
    public fun measuredMiB(): Int
}

/** Reads the device's thermal state. */
public fun interface ThermalProbe {

    /** The current thermal reading. */
    public fun read(): ThermalReading
}

/** Reads the device's battery state. */
public fun interface BatteryProbe {

    /** The current battery reading. */
    public fun read(): BatteryReading
}

/**
 * A component that can give memory back.
 *
 * An implementation must release its own [RamBudget] reservation when it
 * evicts, so that the ledger stays truthful.
 */
public interface Evictable {

    /**
     * Releases up to [targetMiB] of memory.
     *
     * @return the number of MiB actually released, which may be less than the
     *   target when the component holds less than the target.
     */
    public fun evict(targetMiB: Int): Int
}

/** How much pressure the runtime is under. */
public enum class PressureLevel {
    /** Comfortably inside the cap. */
    NOMINAL,

    /** Above 85 % of the cap. Evict, but do not change the run's behaviour. */
    ELEVATED,

    /** Above 95 %. Unload the local model and route to the cloud. */
    CRITICAL,

    /** At or above the cap. Stop the run. */
    EXCEEDED,
    ;

    public companion object {
        /** The fraction of the cap at which pressure becomes elevated. */
        public const val ELEVATED_AT: Double = 0.85

        /** The fraction of the cap at which pressure becomes critical. */
        public const val CRITICAL_AT: Double = 0.95

        /** The pressure for a measured value against a cap. */
        public fun forMeasurement(measuredMiB: Int, limitMiB: Int): PressureLevel {
            if (limitMiB <= 0) return PressureLevel.EXCEEDED
            val fraction = measuredMiB.toDouble() / limitMiB.toDouble()
            return when {
                fraction >= 1.0 -> PressureLevel.EXCEEDED
                fraction >= CRITICAL_AT -> PressureLevel.CRITICAL
                fraction >= ELEVATED_AT -> PressureLevel.ELEVATED
                else -> PressureLevel.NOMINAL
            }
        }
    }
}

/** A change the runtime must make because of a quota. */
public sealed interface Escalation {

    /** The local model was unloaded to stay inside the cap. */
    public data class UnloadLocalModel(val reason: String) : Escalation

    /** Inference must move to the cloud. */
    public data class RouteToCloud(val reason: String) : Escalation

    /** Local inference is suspended, but the run continues. */
    public data class SuspendLocalInference(val reason: String) : Escalation
}

/** A request to stop the run because a quota cannot be satisfied. */
public data class HardStop(val reason: String, val measuredMiB: Int, val limitMiB: Int)

/** The outcome of one watchdog tick. */
public data class QuotaTick(
    val measuredMiB: Int,
    val limitMiB: Int,
    val pressure: PressureLevel,
    val evictedMiB: Int,
    val escalations: List<Escalation>,
    val hardStop: HardStop? = null,
)
