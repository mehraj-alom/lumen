package dev.lumen.core.agent

import dev.lumen.core.action.ActionLedger
import dev.lumen.core.action.ActionLedgerEntry
import dev.lumen.core.action.LedgeredActionExecutor
import dev.lumen.core.brain.BrainPort
import dev.lumen.core.brain.BrainReply
import dev.lumen.core.brain.CritiqueRequest
import dev.lumen.core.brain.DeviceState
import dev.lumen.core.brain.PlanRequest
import dev.lumen.core.brain.ReplanRequest
import dev.lumen.core.model.ApprovalRequest
import dev.lumen.core.model.ApprovalState
import dev.lumen.core.model.Goal
import dev.lumen.core.model.Observation
import dev.lumen.core.model.Plan
import dev.lumen.core.model.Reason
import dev.lumen.core.model.RunId
import dev.lumen.core.model.StateStore
import dev.lumen.core.model.Step
import dev.lumen.core.model.StepId
import dev.lumen.core.model.StepState
import dev.lumen.core.model.UlidGenerator
import dev.lumen.core.model.Verdict
import dev.lumen.core.policy.ApprovalBroker
import dev.lumen.core.policy.PolicyDecision
import dev.lumen.core.policy.PolicyEngine
import dev.lumen.core.policy.PolicyRequest

/**
 * The agent runtime: the Planner–Actor–Critic loop.
 *
 * The loop is deliberately small. The planner proposes, the actor performs
 * and observes, the critic judges, and the loop decides what happens next.
 * Everything else — the budgets, the approvals, the abort, the
 * checkpointing — is a rule the loop applies around that cycle.
 *
 * One action is in flight at a time and each action is followed by its
 * observation. An agent that acts faster than it can see is an agent that
 * acts on a stale screen.
 */
public class AgentRuntime(
    private val brain: BrainPort,
    private val environment: StepEnvironment,
    private val policy: PolicyEngine,
    private val approvals: ApprovalBroker,
    private val store: StateStore,
    private val ledger: ActionLedger,
    private val approvalSignal: ApprovalSignal,
    private val ids: UlidGenerator,
    private val progress: RunProgress = RunProgress.NONE,
    private val config: RunConfig = RunConfig(),
    private val abort: AbortSignal = AbortSignal(),
    private val clock: () -> Long = System::currentTimeMillis,
    /** Returns a failure reason if the battery forbids starting, or null. */
    private val batteryCheck: () -> String? = { null },
    /** Returns a failure reason if the thermal state forbids continuing, or null. */
    private val thermalCheck: () -> String? = { null },
    /** Called with every brain reply, so the runtime's owner can account its cost. */
    private val onBrainReply: (BrainReply) -> Unit = {},
) {

    private val device = DeviceActionExecutor(environment)

    /**
     * Runs [goal] to completion, to failure, or to an abort.
     *
     * The run resumes from the stored plan when one exists, which is what
     * makes a run survive the process being killed.
     */
    public suspend fun run(goal: Goal): RunOutcome {
        val state = RunState(
            goal = goal,
            startedAt = clock(),
            plan = store.loadPlan(goal.id) ?: createPlan(goal),
            observation = environment.observe(),
        )
        batteryCheck()?.let {
            return RunOutcome.Failed(it, state.stepsExecuted)
        }
        while (true) {
            val finished = advance(state)
            if (finished != null) return finished
        }
    }

    /**
     * Takes one iteration of the loop, in the fixed order the run has always
     * applied: the abort is honoured first, then the budget, then the next
     * step is chosen and executed. A non-null return ends the run; null means
     * the loop advances with the state this call mutated.
     */
    private suspend fun advance(state: RunState): RunOutcome? {
        var result: RunOutcome? = null

        if (abort.isAborted) {
            result = RunOutcome.Aborted(state.stepsExecuted)
        } else {
            thermalCheck()?.let { result = RunOutcome.Failed(it, state.stepsExecuted) }

            if (result == null) {
                checkBudget(state.startedAt, state.stepsExecuted)?.let { result = it }
            }

            if (result == null) {
                val step = nextStep(state.plan)
                if (step == null) {
                    result = idleAdvance(state)
                } else {
                    report(state, step)
                    result = executeStepResult(state, step)
                }
            }
        }

        return result
    }

    private suspend fun executeStepResult(state: RunState, step: Step): RunOutcome? =
        when (val stepResult = executeStep(state.goal, state.plan, step, state.observation)) {
            is StepResult.Completed -> {
                state.stepsExecuted++
                state.observation = stepResult.observation
                state.plan = stepResult.plan
                reportOutcome(state, step, stepResult)
                null
            }

            is StepResult.Finished -> {
                state.stepsExecuted++
                reportOutcome(state, step, stepResult)
                RunOutcome.Completed(stepResult.summary, state.stepsExecuted)
            }

            is StepResult.NeedsReplan -> {
                state.stepsExecuted++
                state.observation = stepResult.observation
                reportOutcome(state, step, stepResult)
                if (state.replans >= config.maxReplans) {
                    RunOutcome.Failed("the step could not be recovered from", state.stepsExecuted)
                } else {
                    state.replans++
                    state.plan = revisePlan(state.goal, state.plan, failed = step, observation = stepResult.observation)
                    null
                }
            }

            is StepResult.Failed -> {
                reportOutcome(state, step, stepResult)
                RunOutcome.Failed(stepResult.reason, state.stepsExecuted)
            }

            StepResult.Aborted -> RunOutcome.Aborted(state.stepsExecuted)
        }

    /**
     * Tells the watcher how a step finished, so the console's step event carries
     * the step id, risk class, target app and duration rather than only the
     * intent the loop chose before dispatching.
     */
    private suspend fun reportOutcome(state: RunState, step: Step, result: StepResult) {
        val durationMs = clock() - state.stepStartedAt.coerceAtLeast(0L)
        val app = when (result) {
            is StepResult.Completed -> result.observation?.app
            is StepResult.NeedsReplan -> result.observation?.app
            else -> null
        } ?: ""
        progress.onStepOutcome(
            StepProgress(
                stepIndex = state.stepsExecuted,
                totalSteps = state.plan.steps.size,
                planVersion = step.planVersion,
                intent = step.intent,
                reason = step.reason.text,
                stepId = step.id.value,
                riskClass = step.riskClass.name,
                app = app,
                durationMs = durationMs,
            ),
        )
    }

    /**
     * Tells the watcher which step the loop has chosen.
     *
     * Before the dispatch rather than after it: the point of the report is that
     * the run is working on this intent now, and a step that goes on to wait for
     * an approval, or for a slow device, would otherwise say nothing until it
     * was finished. A watcher that throws is not caught here, because the loop
     * has no use for a progress report it had to swallow.
     */
    private fun report(state: RunState, step: Step) {
        state.stepStartedAt = clock()
        progress.onStep(
            StepProgress(
                stepIndex = state.stepsExecuted + 1,
                totalSteps = state.plan.steps.size,
                planVersion = step.planVersion,
                intent = step.intent,
                reason = step.reason.text,
                plan = state.plan.steps.map { s ->
                    PlanNode(
                        stepId = s.id.value,
                        intent = s.intent,
                        state = s.state.name,
                        active = s.id == step.id,
                        riskClass = s.riskClass.name,
                    )
                },
            ),
        )
    }

    /**
     * The decision when the plan holds no runnable step: a plan with no steps
     * says nothing about the goal, a fully complete plan is done, and a
     * blocked plan is revised until the replan budget runs out.
     */
    private suspend fun idleAdvance(state: RunState): RunOutcome? {
        if (state.plan.steps.isEmpty()) {
            // A plan with no steps says nothing about the goal, and there is
            // no failed step to replan from.
            return RunOutcome.Failed("the brain returned a plan with no steps", state.stepsExecuted)
        }

        val unfinished = state.plan.steps.count { it.state != StepState.COMPLETE }
        if (unfinished == 0) {
            return RunOutcome.Completed("the goal is satisfied", state.stepsExecuted)
        }

        if (state.replans >= config.maxReplans) {
            return RunOutcome.Failed("the plan is blocked and cannot be revised", state.stepsExecuted)
        }

        state.replans++
        state.plan = revisePlan(state.goal, state.plan, failed = null, observation = state.observation)
        return null
    }

    /** Asks the run to stop. */
    public fun abortRun(): Unit = abort.abort()

    // ---------------------------------------------------------------- planning

    private suspend fun createPlan(goal: Goal): Plan {
        val reply = brain.plan(
            PlanRequest(
                goal = goal,
                deviceState = deviceState(),
                observations = emptyList(),
                remainingSteps = config.stepsHardLimit,
            ),
        )
        onBrainReply(reply)
        val plan = reply.toPlan(goal.id, ids, clock())
        store.savePlan(plan)
        return plan
    }

    private suspend fun revisePlan(goal: Goal, previous: Plan, failed: Step?, observation: Observation?): Plan {
        val failedStep = failed
            ?: previous.steps.lastOrNull { it.state != StepState.COMPLETE }
            ?: previous.steps.first()
        val reply = brain.replan(
            ReplanRequest(
                goal = goal,
                failedStep = failedStep,
                observation = observation,
                previousPlanVersion = previous.version,
                deviceState = deviceState(),
                remainingSteps = config.stepsHardLimit,
            ),
        )
        onBrainReply(reply)
        val plan = reply.toPlan(goal.id, ids, clock(), previous.version)
        store.savePlan(plan)
        return plan
    }

    // -------------------------------------------------------------- execution

    /**
     * Runs one step. The order is fixed by the invariants: the policy is
     * consulted before anything is dispatched, an abort is checked both before
     * the policy and before the action, and the action is only performed once
     * a required approval has come back granted (I4, I12).
     */
    private suspend fun executeStep(goal: Goal, plan: Plan, step: Step, observation: Observation?): StepResult {
        if (abort.isAborted) return StepResult.Aborted

        val blocked = preDispatchBlocker(goal, step, observation)
        if (blocked != null) return blocked

        if (abort.isAborted) return StepResult.Aborted

        return dispatchAction(goal, plan, step, observation)
    }

    /**
     * The policy answer for [step], and the approval gate if it asked for one.
     * A denied step, or a step whose approval did not come back granted, is a
     * failure and nothing is dispatched.
     */
    private suspend fun preDispatchBlocker(goal: Goal, step: Step, observation: Observation?): StepResult? {
        val decision = policy.decide(
            PolicyRequest(
                runId = goal.id,
                stepId = step.id,
                action = step.action,
                observation = observation,
                plannerRiskClass = step.riskClass,
            ),
            nowMs = clock(),
        )

        if (decision is PolicyDecision.Deny) {
            return StepResult.Failed(decision.reason.text)
        }

        if (decision is PolicyDecision.RequiresApproval) {
            val outcome = awaitApproval(goal, step, decision.request)
            if (outcome != ApprovalOutcome.APPROVED) {
                return StepResult.Failed("the step was not approved")
            }
        }

        return null
    }

    /**
     * Performs the action, records its observation, and asks the critic. A
     * failed action is recorded as failed and ends the step; otherwise the
     * critic's verdict decides how the step resolves.
     *
     * The action reaches the device through the ledger and nothing else: an
     * action whose pre-state could not be written is denied there, and the
     * loop sees it as a failed action (I6).
     */
    private suspend fun dispatchAction(goal: Goal, plan: Plan, step: Step, observation: Observation?): StepResult {
        val executor = LedgeredActionExecutor(
            delegate = device,
            ledger = ledger,
            entry = ActionLedgerEntry(
                runId = goal.id,
                stepId = step.id,
                action = step.action,
                reason = step.reason,
                preState = preStateDigest(observation),
                startedAtMs = clock(),
            ),
        )

        val result = executor.dispatch(step.action, config.allowedPackages)
        if (!result.ok) {
            val reason = result.error?.message ?: "the action failed"
            withState(plan, step, StepState.FAILED)
            return StepResult.Failed(reason)
        }

        val newObservation = result.observation
        if (newObservation != null) {
            store.appendObservation(goal.id, step.id, newObservation)
        }

        val critique = brain.critique(
            CritiqueRequest(
                goal = goal,
                step = step,
                observation = newObservation,
                deviceState = deviceState(),
            ),
        )

        return when (critique.verdict) {
            Verdict.CONTINUE -> {
                StepResult.Completed(withState(plan, step, StepState.COMPLETE), newObservation)
            }

            Verdict.COMPLETE -> {
                withState(plan, step, StepState.COMPLETE)
                StepResult.Finished(critique.reason)
            }

            Verdict.FAIL -> {
                withState(plan, step, StepState.FAILED)
                StepResult.Failed(critique.reason)
            }

            Verdict.REPLAN -> {
                StepResult.NeedsReplan(withState(plan, step, StepState.REPLAN), newObservation)
            }
        }
    }

    /**
     * Stages the approval and waits for the decision.
     *
     * The approval is durable before the wait starts, so it survives the
     * process being killed. The wait reads the decision from the store,
     * because it may arrive from another process.
     */
    private suspend fun awaitApproval(goal: Goal, step: Step, request: ApprovalRequest): ApprovalOutcome {
        val approvalId = approvals.stage(request)
        store.saveStep(
            runId = goal.id,
            step = step.copy(state = StepState.AWAITING_APPROVAL, updatedAtMs = clock()),
        )

        return when (approvalSignal.await(approvalId)) {
            ApprovalState.APPROVED -> ApprovalOutcome.APPROVED
            ApprovalState.DENIED -> ApprovalOutcome.DENIED
            ApprovalState.EXPIRED, ApprovalState.PENDING -> ApprovalOutcome.EXPIRED
        }
    }

    // ------------------------------------------------------------------ state

    /**
     * Records the new state of a step, in the plan and in the store.
     *
     * The plan is updated as well, because the loop picks the next step
     * from the plan in memory. A step that ran and is not marked would be
     * picked again, and the run would never advance.
     */
    private suspend fun withState(plan: Plan, step: Step, state: StepState): Plan {
        val updatedStep = step.copy(state = state, updatedAtMs = clock())
        store.saveStep(runId = plan.runId, step = updatedStep)
        return plan.copy(
            steps = plan.steps.map { if (it.id == updatedStep.id) updatedStep else it },
        )
    }

    private fun nextStep(plan: Plan): Step? = plan.steps.firstOrNull { step ->
        step.state == StepState.PLANNED || step.state == StepState.REPLAN
    }

    private fun checkBudget(startedAt: Long, stepsExecuted: Int): RunOutcome? {
        if (stepsExecuted >= config.stepsHardLimit) {
            return RunOutcome.Failed("the run reached the hard step limit", stepsExecuted)
        }
        if (clock() - startedAt >= config.wallClockLimitMs) {
            return RunOutcome.Failed("the run reached the wall-clock limit", stepsExecuted)
        }
        return null
    }

    private suspend fun deviceState(): DeviceState = DeviceState(foregroundPackage = environment.foregroundPackage())

    /**
     * The pre-state the ledger records for an action about to be dispatched.
     *
     * It is a digest and not the screen: the app in front, how many nodes were
     * visible, the signature the settle detector compares against, and where
     * the captured frame lives in the sandbox. Together those say what the
     * device looked like before the agent touched it, which is what a reversal
     * needs, without copying screen text into a file the egress guard treats as
     * content (I11).
     */
    private fun preStateDigest(observation: Observation?): String? = observation?.let { screen ->
        buildString {
            append("app=").append(screen.app)
            append(";nodes=").append(screen.nodes.size)
            append(";signature=").append(screen.signature ?: "none")
            screen.screenshot?.let { append(";frame=").append(it.path) }
        }
    }

    /** The outcome of one step. */
    private sealed interface StepResult {
        data class Completed(val plan: Plan, val observation: Observation?) : StepResult
        data class NeedsReplan(val plan: Plan, val observation: Observation?) : StepResult
        data class Finished(val summary: String) : StepResult
        data class Failed(val reason: String) : StepResult
        data object Aborted : StepResult
    }

    /**
     * The mutable cursor of a run: the plan and observation in play, and how
     * many steps and replans have been spent. It is a single holder so the loop
     * advances by mutating one object rather than threading every counter
     * through each step of the iteration.
     */
    private class RunState(val goal: Goal, val startedAt: Long, var plan: Plan, var observation: Observation?) {
        var stepsExecuted: Int = 0
        var replans: Int = 0
        var stepStartedAt: Long = 0L
    }
}

/**
 * The plan the brain proposed, as a plan.
 *
 * The steps are given real identifiers and the reasons the brain gave, so
 * the trace says why each step happened (invariant I9).
 */
public fun BrainReply.toPlan(runId: RunId, ids: UlidGenerator, nowMs: Long, previousVersion: Int = 0): Plan = Plan(
    runId = runId,
    version = previousVersion + 1,
    steps = steps.map { planned ->
        Step(
            id = StepId(ids.next()),
            planVersion = previousVersion + 1,
            phase = phaseFor(planned),
            intent = planned.intent,
            reason = Reason.planner(planned.reason),
            action = planned.action,
            expectation = planned.expectation,
            riskClass = planned.riskClass,
            state = StepState.PLANNED,
            createdAtMs = nowMs,
            updatedAtMs = nowMs,
        )
    },
    createdAtMs = nowMs,
    summary = thought,
)

/** The phase a planned step belongs to, taken from its intent. */
private fun phaseFor(planned: dev.lumen.core.brain.PlannedStep): String {
    val intent = planned.intent.lowercase()
    return when {
        intent containsAny arrayOf("pay", "book") -> "book"
        intent containsAny arrayOf("publish", "note") -> "publish"
        intent containsAny arrayOf("send", "message") -> "notify"
        intent containsAny arrayOf("verify", "confirm") -> "verify"
        intent containsAny arrayOf("compare", "check") -> "compare"
        else -> "research"
    }
}

/** Whether the intent names any of [terms]. The first matching group wins the ladder above. */
private infix fun String.containsAny(terms: Array<String>): Boolean = terms.any { contains(it) }
