package dev.lumen.core.quota

/** The device's thermal state. */
public enum class ThermalStatus {
    NONE,
    LIGHT,
    MODERATE,
    SEVERE,
    CRITICAL,
    EMERGENCY,
    SHUTDOWN,
}

/** A thermal reading. */
public data class ThermalReading(
    val temperatureCelsius: Double,
    val status: ThermalStatus = ThermalStatus.NONE,
)

/** What the governor decided in response to a reading. */
public data class GovernorDecision(
    val workerLimit: Int,
    val localInferenceAllowed: Boolean,
    val reason: String,
)

/**
 * The thermal governor.
 *
 * The agent is a background process, so it gives way to the user first. At
 * 42 °C the worker count drops to one; at 46 °C, or when the platform reports
 * a critical thermal status, local inference stops and the run continues on
 * the cloud.
 */
public class ThermalGovernor(
    private val maxWorkers: Int = DEFAULT_MAX_WORKERS,
    private val reduceWorkersAtCelsius: Double = 42.0,
    private val suspendLocalInferenceAtCelsius: Double = 46.0,
) {
    init {
        require(maxWorkers > 0) { "the worker limit must be positive" }
        require(reduceWorkersAtCelsius <= suspendLocalInferenceAtCelsius) {
            "the worker reduction must happen before the inference suspension"
        }
    }

    /** The decision for [reading]. */
    public fun decide(reading: ThermalReading): GovernorDecision {
        val isCriticalStatus = reading.status >= ThermalStatus.CRITICAL

        if (reading.temperatureCelsius >= suspendLocalInferenceAtCelsius || isCriticalStatus) {
            return GovernorDecision(
                workerLimit = 1,
                localInferenceAllowed = false,
                reason = "the device is at ${reading.temperatureCelsius} °C, so local inference is suspended",
            )
        }

        if (reading.temperatureCelsius >= reduceWorkersAtCelsius) {
            return GovernorDecision(
                workerLimit = 1,
                localInferenceAllowed = true,
                reason = "the device is at ${reading.temperatureCelsius} °C, so the agent uses a single worker",
            )
        }

        return GovernorDecision(
            workerLimit = maxWorkers,
            localInferenceAllowed = true,
            reason = "the device is at ${reading.temperatureCelsius} °C",
        )
    }

    public companion object {
        /** The default worker ceiling from `AGENTS.md` §2. */
        public const val DEFAULT_MAX_WORKERS: Int = 4
    }
}

/** A battery reading. */
public data class BatteryReading(
    val levelPercent: Int,
    val isCharging: Boolean,
)

/** What the battery governor decided. */
public sealed interface BatteryDecision {

    /** The run may continue. */
    public data object Proceed : BatteryDecision

    /** The run must be suspended. */
    public data class Suspend(val reason: String) : BatteryDecision
}

/**
 * The battery governor.
 *
 * Below 20 % and not charging, an autonomous run is suspended. The user can
 * still run a short goal manually; the governor only stops the agent from
 * starting work it cannot finish.
 */
public class BatteryGovernor(
    private val suspendBelowPercent: Int = DEFAULT_SUSPEND_BELOW_PERCENT,
) {
    init {
        require(suspendBelowPercent in 1..100) { "the battery threshold must be a percentage" }
    }

    /** The decision for [reading]. */
    public fun decide(reading: BatteryReading): BatteryDecision {
        if (reading.isCharging) return BatteryDecision.Proceed
        if (reading.levelPercent >= suspendBelowPercent) return BatteryDecision.Proceed

        return BatteryDecision.Suspend(
            "the battery is at ${reading.levelPercent} % and the device is not charging",
        )
    }

    public companion object {
        /** The default threshold from `AGENTS.md` §2. */
        public const val DEFAULT_SUSPEND_BELOW_PERCENT: Int = 20
    }
}
