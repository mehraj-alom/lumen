package dev.lumen.core.action

import dev.lumen.core.model.Action
import dev.lumen.core.model.ApiError
import dev.lumen.core.model.ErrorCode
import dev.lumen.core.model.Observation
import dev.lumen.core.model.Reason
import dev.lumen.core.model.RunId
import dev.lumen.core.model.StepId
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

/**
 * The single funnel through which every action reaches the device.
 *
 * There is exactly one of these. A second path to the device is a second
 * path around the ledger, the policy, and the observation, and it would
 * defeat all three.
 */
public interface ActionExecutor {
    /** Performs [action] and reports what happened. */
    public suspend fun dispatch(action: Action): ActionResult
}

/** The outcome of an action. */
public data class ActionResult(
    val action: Action,
    val ok: Boolean,
    val observation: Observation? = null,
    val durationMs: Long = 0L,
    val error: ApiError? = null,
) {
    public companion object {
        /** A successful action. */
        public fun ok(action: Action, observation: Observation? = null, durationMs: Long = 0L): ActionResult =
            ActionResult(action, ok = true, observation = observation, durationMs = durationMs)

        /** A failed action. */
        public fun failed(action: Action, code: ErrorCode, message: String, durationMs: Long = 0L): ActionResult = ActionResult(
            action = action,
            ok = false,
            durationMs = durationMs,
            error = ApiError(code = code, message = message),
        )
    }
}

/**
 * The packages the agent may act on.
 *
 * The allowlist is empty by default, so an install does nothing the user did
 * not explicitly authorise. The check is enforced on the device, at the point
 * of dispatch, and not only in the planner.
 */
public class PackageAllowlist(private val allowed: Set<String> = emptySet()) {
    /** Whether [packageName] may be acted on. */
    public fun allows(packageName: String): Boolean = allowed.contains(packageName)

    /** The allowlist as a set. */
    public fun packages(): Set<String> = allowed.toSet()

    /** An allowlist with [packages] added. */
    public fun with(packages: Set<String>): PackageAllowlist = PackageAllowlist(allowed + packages)

    /** An allowlist with [packages] removed. */
    public fun without(packages: Set<String>): PackageAllowlist = PackageAllowlist(allowed - packages)
}

/**
 * The checks that run before an action is dispatched.
 *
 * The validator is not the policy engine. The policy engine decides whether
 * a *human* must approve an action; the validator decides whether the action
 * is well formed and permitted at all.
 */
public class ActionValidator(
    private val allowlist: PackageAllowlist = PackageAllowlist(),
    private val allowedUrlSchemes: Set<String> = DEFAULT_URL_SCHEMES,
) {

    /**
     * The error that stops [action], or null when the action may proceed.
     *
     * @param foregroundPackage the package currently in front, or null when
     *   the surface is a virtual display and there is no foreground app.
     */
    public fun validate(action: Action, foregroundPackage: String?): ApiError? = when (action) {
        is Action.OpenApp -> if (allowlist.allows(action.packageName)) {
            null
        } else {
            ApiError(
                code = ErrorCode.DENIED_BY_POLICY,
                message = "the package ${action.packageName} is not on the allowlist",
                detail = mapOf("package" to action.packageName),
            )
        }

        is Action.OpenUrl -> {
            val scheme = action.url.substringBefore(':', missingDelimiterValue = "").lowercase()
            if (scheme in allowedUrlSchemes) {
                null
            } else {
                ApiError(
                    code = ErrorCode.DENIED_BY_POLICY,
                    message = "the url scheme '$scheme' is not allowed",
                    detail = mapOf("scheme" to scheme),
                )
            }
        }

        else -> {
            // A gesture acts on whatever is in front. The foreground package
            // is re-checked at dispatch time, so a screen that changed under
            // the agent cannot be driven by mistake.
            if (foregroundPackage != null && !allowlist.allows(foregroundPackage)) {
                ApiError(
                    code = ErrorCode.DENIED_BY_POLICY,
                    message = "the foreground package $foregroundPackage is not on the allowlist",
                    detail = mapOf("package" to foregroundPackage),
                )
            } else {
                null
            }
        }
    }

    public companion object {
        /** The url schemes the agent may open. */
        public val DEFAULT_URL_SCHEMES: Set<String> = setOf("http", "https")
    }
}

/**
 * One recorded action, with the state that preceded it.
 *
 * [seq] belongs to the ledger, which stamps the entry as it takes it, so a
 * caller writing an entry down before it is recorded leaves it alone.
 */
public data class ActionLedgerEntry(
    val runId: RunId,
    val stepId: StepId,
    val seq: Long = 0L,
    val action: Action,
    val reason: Reason,
    val preState: String?,
    val startedAtMs: Long,
)

/**
 * Where the ledger is written.
 *
 * The sink must make the entry durable before it returns. An action that is
 * not durable is an action that cannot be audited, and an action that cannot
 * be audited is not dispatched.
 *
 * A sink reports a durability problem by throwing: `IOException` when the
 * storage itself refused, `IllegalStateException` when the sink is not in a
 * state to accept entries. Those are the failures [ActionLedger] turns into
 * a denial; anything else is a defect and is allowed to surface.
 */
public interface LedgerSink {
    /** Writes [entry] and makes it durable. */
    public suspend fun append(entry: ActionLedgerEntry)

    /** Whether the sink is currently able to accept entries. */
    public suspend fun isHealthy(): Boolean
}

/**
 * The write-ahead ledger.
 *
 * Every action is recorded before it runs, and the record includes the state
 * that preceded it. That is what makes an action reversible: the ledger says
 * what the screen looked like before the agent touched it (invariant I6).
 */
public class ActionLedger(private val sink: LedgerSink) {
    private val lock = Any()
    private var sequence = 0L

    /**
     * Records [entry].
     *
     * @return false when the entry could not be made durable, in which case
     *   the action must not be dispatched.
     */
    public suspend fun record(entry: ActionLedgerEntry): Boolean {
        val next = synchronized(lock) { sequence++ }
        val stamped = entry.copy(seq = next)
        return try {
            sink.append(stamped)
            true
        } catch (cancellation: CancellationException) {
            // A cancelled run is not a ledger failure (invariant I12): the
            // abort surfaces, it is not quietly converted into "not recorded".
            throw cancellation
        } catch (ignored: IOException) {
            // Fail closed: the sink's detail is dropped on purpose, because
            // it can quote the entry and the entry carries pre-state screen
            // text that may not travel unredacted (§5). The boolean already
            // carries the only decision the dispatcher needs: deny (I6).
            false
        } catch (ignored: IllegalStateException) {
            false
        }
    }

    /** Whether the ledger can currently accept entries. */
    public suspend fun isHealthy(): Boolean = try {
        sink.isHealthy()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (ignored: IOException) {
        // Same deliberate drop as in `record`: an unreachable sink is
        // unhealthy, whatever it claims the reason is.
        false
    } catch (ignored: IllegalStateException) {
        false
    }
}

/**
 * An executor that records every action before it runs.
 *
 * This is the composition that makes invariant I6 true by construction: there
 * is no way to reach the delegate without going through the ledger.
 *
 * The [entry] is the record the step leaves behind — the run, the step, the
 * reason the planner gave (invariant I9), the state the action is taken from,
 * and the time it was staged. The executor writes it with the action it is
 * about to dispatch, so the record cannot describe an action other than the
 * one that ran.
 */
public class LedgeredActionExecutor(
    private val delegate: ActionExecutor,
    private val ledger: ActionLedger,
    private val entry: ActionLedgerEntry,
) : ActionExecutor {

    override suspend fun dispatch(action: Action): ActionResult {
        val recorded = ledger.record(entry.copy(action = action))

        if (!recorded) {
            return ActionResult.failed(
                action = action,
                code = ErrorCode.INTERNAL,
                message = "the action was not recorded, so it was not dispatched",
            )
        }

        return delegate.dispatch(action)
    }
}
