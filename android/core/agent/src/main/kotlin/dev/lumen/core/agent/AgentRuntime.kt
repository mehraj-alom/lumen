package dev.lumen.core.agent

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
    private val approvalSignal: ApprovalSignal,
    private val ids: UlidGenerator,
    private val config: RunConfig = RunConfig(),
    private val abort: AbortSignal = AbortSignal(),
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /**
     * Runs [goal] to completion, to failure, or to an abort.
     *
     * The run resumes from the stored plan when one exists, which is what
     * makes a run survive the process being killed.
     */
    public suspend fun run(goal: Goal): RunOutcome {
        val startedAt = clock()
        var stepsExecuted = 0
        var replans = 0

        var plan = store.loadPlan(goal.id) ?: createPlan(goal)
        var observation = environment.observe()

        while (true) {
            if (abort.isAborted) return RunOutcome.Aborted(stepsExecuted)

            checkBudget(startedAt, stepsExecuted)?.let { return it }

            val step = nextStep(plan)
            if (step == null) {
                val unfinished = plan.steps.count { it.state != StepState.COMPLETE }
                if (unfinished == 0) {
                    return RunOutcome.Completed("the goal is satisfied", stepsExecuted)
                }
                if (replans >= config.maxReplans) {
                    return RunOutcome.Failed("the plan is blocked and cannot be revised", stepsExecuted)
                }
                replans++
                plan = revisePlan(goal, plan, failed = null, observation = observation)
                continue
            }

            when (val result = executeStep(goal, plan, step, observation)) {
                is StepResult.Completed -> {
                    stepsExecuted++
                    observation = result.observation
                    plan = result.plan
                }

                is StepResult.Finished -> {
                    stepsExecuted++
                    return RunOutcome.Completed(result.summary, stepsExecuted)
                }

                is StepResult.NeedsReplan -> {
                    stepsExecuted++
                    observation = result.observation
                    if (replans >= config.maxReplans) {
                        return RunOutcome.Failed("the step could not be recovered from", stepsExecuted)
                    }
                    replans++
                    plan = revisePlan(goal, plan, failed = step, observation = result.observation)
                }

                is StepResult.Failed -> {
                    return RunOutcome.Failed(result.reason, stepsExecuted)
                }

                StepResult.Aborted -> {
                    return RunOutcome.Aborted(stepsExecuted)
                }
            }
        }
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
        val plan = reply.toPlan(goal.id, ids, clock())
        store.savePlan(plan)
        return plan
    }

    private suspend fun revisePlan(
        goal: Goal,
        previous: Plan,
        failed: Step?,
        observation: Observation?,
    ): Plan {
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
        val plan = reply.toPlan(goal.id, ids, clock(), previous.version)
        store.savePlan(plan)
        return plan
    }

    // -------------------------------------------------------------- execution

    private suspend fun executeStep(
        goal: Goal,
        plan: Plan,
        step: Step,
        observation: Observation?,
    ): StepResult {
        if (abort.isAborted) return StepResult.Aborted

        // The policy runs before the action, never after.
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

        if (abort.isAborted) return StepResult.Aborted

        val result = environment.perform(step.action)
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
    private suspend fun awaitApproval(
        goal: Goal,
        step: Step,
        request: ApprovalRequest,
    ): ApprovalOutcome {
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

    private suspend fun deviceState(): DeviceState =
        DeviceState(foregroundPackage = environment.foregroundPackage())

    /** The outcome of one step. */
    private sealed interface StepResult {
        data class Completed(val plan: Plan, val observation: Observation?) : StepResult
        data class NeedsReplan(val plan: Plan, val observation: Observation?) : StepResult
        data class Finished(val summary: String) : StepResult
        data class Failed(val reason: String) : StepResult
        data object Aborted : StepResult
    }
}

/**
 * The plan the brain proposed, as a plan.
 *
 * The steps are given real identifiers and the reasons the brain gave, so
 * the trace says why each step happened (invariant I9).
 */
public fun BrainReply.toPlan(
    runId: RunId,
    ids: UlidGenerator,
    nowMs: Long,
    previousVersion: Int = 0,
): Plan = Plan(
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
        intent.contains("pay") || intent.contains("book") -> "book"
        intent.contains("publish") || intent.contains("note") -> "publish"
        intent.contains("send") || intent.contains("message") -> "notify"
        intent.contains("verify") || intent.contains("confirm") -> "verify"
        intent.contains("compare") || intent.contains("check") -> "compare"
        else -> "research"
    }
}
