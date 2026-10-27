package dev.lumen.core.action.fixtures

import dev.lumen.core.action.ActionExecutor
import dev.lumen.core.action.ActionLedgerEntry
import dev.lumen.core.action.ActionResult
import dev.lumen.core.action.LedgerSink
import dev.lumen.core.model.Action
import dev.lumen.core.model.ErrorCode
import dev.lumen.core.model.Observation

/**
 * An executor that records what it was asked to do.
 *
 * The fake is the reason the whole loop can be tested without a device. It
 * can also be told to fail, which is how the loop's error paths are covered.
 */
public class FakeActionExecutor(private val observation: Observation? = null) : ActionExecutor {

    /** Every action that was dispatched, in order. */
    public val dispatched: MutableList<Action> = mutableListOf()

    /** The error the next dispatch fails with, or null. */
    public var failure: ErrorCode? = null

    /** The number of dispatches that were refused. */
    public var failureCount: Int = 0

    override suspend fun dispatch(action: Action, allowedPackages: Set<String>): ActionResult {
        dispatched += action

        val error = failure
        if (error != null && failureCount > 0) {
            failureCount--
            return ActionResult.failed(action, error, "the fake executor was told to fail")
        }

        return ActionResult.ok(action, observation)
    }

    /** The last action that was dispatched, or null. */
    public fun last(): Action? = dispatched.lastOrNull()
}

/**
 * A ledger sink that records entries and can be made to fail.
 *
 * The failure mode matters: a sink that fails is how the "the action was not
 * recorded, so it was not dispatched" path is exercised.
 */
public class FakeLedgerSink : LedgerSink {

    /** Every entry that was appended. */
    public val entries: MutableList<ActionLedgerEntry> = mutableListOf()

    /** Whether the sink refuses entries. */
    public var failing: Boolean = false

    override suspend fun append(entry: ActionLedgerEntry) {
        if (failing) throw IllegalStateException("the fake sink is failing")
        entries += entry
    }

    override suspend fun isHealthy(): Boolean = !failing
}
