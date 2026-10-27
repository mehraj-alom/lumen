package dev.lumen.core.agent

import dev.lumen.core.action.ActionExecutor
import dev.lumen.core.action.ActionResult
import dev.lumen.core.model.Action
import dev.lumen.core.model.ApprovalState
import dev.lumen.core.model.Observation
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The device the agent drives.
 *
 * The loop talks to the device through this one port, which is what lets
 * the whole of the agent be tested without a phone, a screen, or a
 * gesture injector.
 */
public interface StepEnvironment {

    /** Reads the current screen. */
    public suspend fun observe(): Observation?

    /** Performs [action] and reports what happened, including the screen afterwards. */
    public suspend fun perform(action: Action, allowedPackages: Set<String> = emptySet()): ActionResult

    /** The package currently in front, or null on a virtual display. */
    public suspend fun foregroundPackage(): String?
}

/**
 * The device, seen as the single dispatch funnel.
 *
 * The loop does not call [StepEnvironment.perform] directly. It dispatches
 * through a `LedgeredActionExecutor`, which records the action and the state
 * that preceded it before the device is touched, and this adapter is what that
 * executor delegates to. Keeping the two apart is what makes invariant I6 a
 * rule of construction rather than a convention: there is no short path from
 * the loop to the screen.
 */
public class DeviceActionExecutor(private val environment: StepEnvironment) : ActionExecutor {

    override suspend fun dispatch(action: Action, allowedPackages: Set<String>): ActionResult = environment.perform(action, allowedPackages)
}

/**
 * The signal that stops a run.
 *
 * It is checked before every dispatch, so an abort takes effect within one
 * action boundary (invariant I12).
 */
public class AbortSignal {
    private val aborted = AtomicBoolean(false)

    /** Whether the run has been asked to stop. */
    public val isAborted: Boolean get() = aborted.get()

    /** Asks the run to stop. */
    public fun abort(): Unit = aborted.set(true)

    /** Clears the signal, for a run that is being resumed. */
    public fun reset(): Unit = aborted.set(false)
}

/** The limits a run executes under. */
public data class RunConfig(
    val stepsSoftLimit: Int = 150,
    val stepsHardLimit: Int = 400,
    val wallClockLimitMs: Long = DEFAULT_WALL_CLOCK_MS,
    val costLimitMicros: Long = 0L,
    val approvalPollIntervalMs: Long = 50L,
    val maxReplans: Int = 25,
    val allowedPackages: Set<String> = emptySet(),
)

/** The default wall-clock ceiling for a run: 45 minutes (`AGENTS.md` §2). */
private const val DEFAULT_WALL_CLOCK_MS: Long = 45L * 60L * 1000L

/** How a run ended. */
public sealed interface RunOutcome {

    /** The goal was satisfied. */
    public data class Completed(val summary: String, val stepsExecuted: Int) : RunOutcome

    /** The run could not continue. */
    public data class Failed(val reason: String, val stepsExecuted: Int) : RunOutcome

    /** The user stopped it. */
    public data class Aborted(val stepsExecuted: Int) : RunOutcome

    /** The run paused and can be resumed. */
    public data class Paused(val reason: String, val stepsExecuted: Int) : RunOutcome
}

/** The decision that came back from an approval. */
public enum class ApprovalOutcome {
    APPROVED,
    DENIED,
    EXPIRED,
}

/**
 * The signal that an approval has been decided.
 *
 * The decision arrives from another surface — a glass card, a notification,
 * the web console — and the runtime may be in a different process from it, so
 * the decision is read from the durable store rather than pushed.
 */
public fun interface ApprovalSignal {
    /**
     * Suspends until [approvalId] is decided.
     *
     * @return the state the approval reached
     */
    public suspend fun await(approvalId: dev.lumen.core.model.ApprovalId): ApprovalState
}
