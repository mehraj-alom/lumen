package dev.lumen.agent.service

import android.util.Log
import dev.lumen.agent.ipc.ApprovalPayload
import dev.lumen.agent.ipc.BudgetPayload
import dev.lumen.agent.ipc.GoalPayload
import dev.lumen.agent.ipc.Payloads
import dev.lumen.agent.ipc.PreviewPayload
import dev.lumen.core.action.ActionLedger
import dev.lumen.core.agent.AgentRuntime
import dev.lumen.core.agent.ApprovalSignal
import dev.lumen.core.agent.IndicatorState
import dev.lumen.core.agent.RunConfig
import dev.lumen.core.agent.RunProgress
import dev.lumen.core.agent.RunState
import dev.lumen.core.agent.StepEnvironment
import dev.lumen.core.agent.StepProgress
import dev.lumen.core.brain.BrainConfig
import dev.lumen.core.brain.BrainFactory
import dev.lumen.core.model.ApiError
import dev.lumen.core.model.ApprovalId
import dev.lumen.core.model.ApprovalState
import dev.lumen.core.model.ErrorCode
import dev.lumen.core.model.Goal
import dev.lumen.core.model.GoalConstraints
import dev.lumen.core.model.LumenClock
import dev.lumen.core.model.Phase
import dev.lumen.core.model.SystemLumenClock
import dev.lumen.core.model.UlidGenerator
import dev.lumen.core.policy.ApprovalBroker
import dev.lumen.core.policy.ApprovalDecision
import dev.lumen.core.policy.PolicyEngine
import dev.lumen.core.quota.QuotaWatchdog
import dev.lumen.core.sandbox.StorageQuota
import dev.lumen.core.store.RoomStateStore
import dev.lumen.platform.accessibility.AccessibilityBridgeClient
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The wiring between the service and the agent.
 *
 * The facade owns the run lifecycle and pushes what happens to the service,
 * which pushes it to the console. The core loop knows nothing about
 * Android, and this is the only place the two meet.
 */
public class AgentRuntimeFacade(
    private val environment: StepEnvironment,
    private val brains: BrainFactory,
    private val store: RoomStateStore,
    private val ledger: ActionLedger,
    private val approvals: ApprovalBroker,
    private val policy: PolicyEngine,
    private val quota: QuotaWatchdog,
    private val storage: StorageQuota,
    private val indicator: AccessibilityBridgeClient,
    private val clock: LumenClock = SystemLumenClock,
) {

    private val scope = CoroutineScope(
        SupervisorJob() +
            Dispatchers.Default +
            CoroutineExceptionHandler { _, error ->
                // Nothing reached here may take the process down: the handler is
                // the backstop behind the catch in [execute], for the work that
                // is launched outside it — the approval resolution, the publishes.
                Log.w(TAG, "the runtime survived an unhandled failure: ${error.javaClass.simpleName}", error)
            },
    )
    private val ids = UlidGenerator(clock = clock)
    private val abort = dev.lumen.core.agent.AbortSignal()

    private var service: AgentRuntimeService? = null
    private var currentRunId: String = ""

    // Written by the run's coroutine and read by the binder threads answering
    // getState, so the write has to be visible there.
    @Volatile
    private var state: RunState = RunState(runId = "")
    private var takeoverActive = false
    private var runStartedAtMs = 0L
    private var runEndedAtMs = 0L

    /**
     * The run this facade is holding, or the empty string before one starts.
     *
     * The service needs it to answer a stop that arrives without one — the
     * notification carries the intent, not the id, and the abort must still
     * name a run to be auditable (`I9`).
     */
    public val liveRunId: String
        get() = currentRunId

    /** Attaches the service that publishes the state. */
    public fun attach(service: AgentRuntimeService) {
        this.service = service
    }

    /** Detaches the service. */
    public fun detach() {
        service = null
    }

    /**
     * Starts a run from [payload].
     *
     * The brain and the limits are read from the payload the console sent, not
     * from a process-wide default: a run told to stop at 150 steps does not get
     * 400, and a run started without screen egress consent cannot send screen
     * text (`I11`). A run that cannot be given a brain fails here, with the
     * reason, rather than starting and dying on the first step.
     */
    public fun startGoal(payload: GoalPayload) {
        val liveBefore = state.phase != Phase.IDLE && !state.phase.isTerminal
        if (liveBefore && payload.runId != currentRunId) {
            // One run at a time. Starting a second run would reset the abort
            // signal the first one is still reading, which is how a stopped run
            // comes back and acts, so the request is refused rather than queued.
            Log.w(TAG, "goal ${payload.runId} refused: run $currentRunId is still live")
            publish(
                state.copy(
                    currentReason = ONE_RUN_MESSAGE,
                    lastError = ApiError(code = ErrorCode.INVALID_STATE, message = ONE_RUN_MESSAGE),
                ),
            )
            return
        }

        currentRunId = payload.runId
        takeoverActive = false
        runStartedAtMs = clock.nowMs()
        runEndedAtMs = 0L
        val goal = Payloads.goal(payload)
        val config = Payloads.brainConfig(payload)
        publish(
            RunState(
                runId = payload.runId,
                phase = Phase.PLANNING,
                currentIntent = goal.text,
                indicatorState = IndicatorState.WORKING,
                brainRoute = goal.constraints.brainPin.name,
            ),
        )

        if (!config.isUsable) {
            publish(
                state.copy(
                    phase = Phase.FAILED,
                    indicatorState = IndicatorState.ERROR,
                    currentReason = NO_BRAIN_MESSAGE,
                    lastError = ApiError(code = ErrorCode.BRAIN_UNAVAILABLE, message = NO_BRAIN_MESSAGE),
                ),
            )
            return
        }

        // A run that was stopped leaves its abort flag behind, and the next run
        // would end before its first step without this.
        abort.reset()

        scope.launch {
            val outcome = execute(goal, config)
            publish(
                state.copy(
                    phase = when (outcome) {
                        is dev.lumen.core.agent.RunOutcome.Completed -> Phase.DONE
                        is dev.lumen.core.agent.RunOutcome.Failed -> Phase.FAILED
                        is dev.lumen.core.agent.RunOutcome.Aborted -> Phase.ABORTED
                        is dev.lumen.core.agent.RunOutcome.Paused -> Phase.PAUSED
                    },
                    indicatorState = when (outcome) {
                        is dev.lumen.core.agent.RunOutcome.Failed -> IndicatorState.ERROR
                        else -> IndicatorState.IDLE
                    },
                    currentReason = outcomeReason(outcome),
                    stepCount = outcomeSteps(outcome),
                ),
            )
        }
    }

    /**
     * Runs the loop, and turns anything it throws into a failed outcome.
     *
     * The loop raises a plain exception for a refused credential, an
     * unreachable brain, a reply outside the envelope and a denied egress
     * check. None of those is the runtime's fault, and the process must not die
     * with a run half-finished (`I12` says the user can always take over; a
     * dead process takes over nothing). What the loop threw becomes the reason
     * the run failed, so the console and the trace both say why.
     */
    private suspend fun execute(goal: Goal, config: BrainConfig): dev.lumen.core.agent.RunOutcome {
        val runtime = AgentRuntime(
            brain = brains.create(config),
            environment = environment,
            policy = policy,
            approvals = approvals,
            store = store,
            ledger = ledger,
            approvalSignal = approvalSignal(),
            ids = ids,
            progress = RunProgress { step -> reportStep(step) },
            abort = abort,
            clock = { clock.nowMs() },
            config = runConfig(goal.constraints),
        )
        return try {
            runtime.run(goal)
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "run ${goal.id.value} ended on ${error.javaClass.simpleName}: ${error.message}")
            dev.lumen.core.agent.RunOutcome.Failed(
                reason = error.message ?: error.javaClass.simpleName,
                stepsExecuted = state.stepIndex,
            )
        }
    }

    /**
     * Puts the step the loop has chosen on every surface that watches the run.
     *
     * This is the loop's own voice: the counter, the intent line and the reason
     * move while the agent works, rather than only at the ends of a run. The
     * report is not a step, and nothing here waits for the screens to agree.
     */
    private fun reportStep(step: StepProgress) {
        val current = state
        if (current.phase.isTerminal || current.takeoverActive) {
            // A step chosen before the stop or the takeover lands is still a step
            // the loop is on, but it must not make a run the user ended look
            // alive again: `I12` promises the screen agrees with the stop.
            Log.w(TAG, "step ${step.stepIndex} of run ${current.runId} is not reported: the run is ${current.phase}")
            return
        }
        publish(
            current.copy(
                phase = Phase.RUNNING,
                indicatorState = IndicatorState.WORKING,
                stepIndex = step.stepIndex,
                stepCount = step.totalSteps,
                planVersion = step.planVersion,
                currentIntent = step.intent,
                currentReason = step.reason,
            ),
        )
    }

    /** The limits the loop runs under, from the goal that arrived. */
    private fun runConfig(constraints: GoalConstraints): RunConfig = RunConfig(
        stepsSoftLimit = constraints.stepsSoftLimit,
        stepsHardLimit = constraints.stepsHardLimit,
        wallClockLimitMs = constraints.wallClockLimitMs,
        costLimitMicros = constraints.costLimitMicros,
    )

    /** What the run has to say for itself, in the console's words. */
    private fun outcomeReason(outcome: dev.lumen.core.agent.RunOutcome): String = when (outcome) {
        is dev.lumen.core.agent.RunOutcome.Completed -> outcome.summary
        is dev.lumen.core.agent.RunOutcome.Failed -> outcome.reason
        is dev.lumen.core.agent.RunOutcome.Aborted -> "stopped after ${outcome.stepsExecuted} steps"
        is dev.lumen.core.agent.RunOutcome.Paused -> outcome.reason
    }

    /** How many steps the run took. */
    private fun outcomeSteps(outcome: dev.lumen.core.agent.RunOutcome): Int = when (outcome) {
        is dev.lumen.core.agent.RunOutcome.Completed -> outcome.stepsExecuted
        is dev.lumen.core.agent.RunOutcome.Failed -> outcome.stepsExecuted
        is dev.lumen.core.agent.RunOutcome.Aborted -> outcome.stepsExecuted
        is dev.lumen.core.agent.RunOutcome.Paused -> outcome.stepsExecuted
    }

    /**
     * Pauses a run.
     *
     * @param runId the run the console named; see [correlate]
     */
    public fun pauseRun(runId: String, reason: String) {
        correlate(runId, "pauseRun")
        publish(state.copy(phase = Phase.PAUSED, currentReason = reason))
    }

    /**
     * Resumes a paused run.
     *
     * @param runId the run the console named; see [correlate]
     */
    public fun resumeRun(runId: String) {
        correlate(runId, "resumeRun")
        publish(state.copy(phase = Phase.RUNNING))
    }

    /**
     * Aborts a run.
     *
     * The abort is never conditional on [runId]: `I12` says the user can stop
     * the agent within one action boundary, and a stale id on the request must
     * not be a reason to keep running.
     */
    public fun abortRun(runId: String, reason: String) {
        correlate(runId, "abortRun")
        abort.abort()
        publish(
            state.copy(
                phase = Phase.ABORTED,
                indicatorState = IndicatorState.ABORTING,
                currentReason = reason,
            ),
        )
    }

    /** Answers an approval. */
    public fun answerApproval(runId: String, approvalId: String, decision: String, answerText: String?) {
        correlate(runId, "answerApproval")
        val resolved = when (decision.uppercase()) {
            "APPROVE" -> ApprovalDecision.Approve
            "DENY" -> ApprovalDecision.Deny
            else -> ApprovalDecision.Answer(answerText.orEmpty())
        }
        scope.launch {
            approvals.resolve(ApprovalId(approvalId), resolved)
            publish(state.copy(phase = Phase.RUNNING, indicatorState = IndicatorState.WORKING))
        }
    }

    /** Sets the brain pin. */
    public fun setBrainPin(runId: String, pin: String) {
        correlate(runId, "setBrainPin")
        publish(state.copy(brainRoute = pin.uppercase()))
    }

    /** Takes the run over, or gives it back. */
    public fun setTakeover(runId: String, takenOver: Boolean) {
        // The takeover is honoured whatever runId arrives: `I12` puts the user
        // ahead of the bookkeeping.
        correlate(runId, "setTakeover")
        takeoverActive = takenOver
        if (takenOver) {
            abort.abort()
            publish(
                state.copy(
                    takeoverActive = true,
                    phase = Phase.PAUSED,
                    indicatorState = IndicatorState.BLOCKED,
                    currentReason = "you took over",
                ),
            )
        } else {
            abort.reset()
            publish(state.copy(takeoverActive = false, phase = Phase.RUNNING))
        }
    }

    /** The state of a run. */
    public fun state(runId: String): RunState = if (runId == currentRunId) state else RunState(runId = runId)

    /** The budget of a run. */
    public fun budget(runId: String): BudgetPayload {
        correlate(runId, "budget")
        val snapshot = quota.budget.snapshot()
        val storageSnapshot = storage.snapshot()
        return BudgetPayload(
            ramUsedMiB = snapshot.usedMiB,
            ramLimitMiB = snapshot.limitMiB,
            ramByCategory = snapshot.byCategory.mapKeys { it.key.name },
            storageUsedMiB = storageSnapshot.usedMiB,
            storageLimitMiB = storageSnapshot.limitMiB,
            stepsUsed = state.stepIndex,
            stepsSoftLimit = 150,
            stepsHardLimit = 400,
            elapsedMs = elapsedMs(),
            wallClockLimitMs = 45L * 60L * 1000L,
            // There is no cost to report yet. `BrainReply` carries prompt and
            // completion token counts, but nothing adds up what they are worth,
            // so a figure here would be an invention rather than a measurement.
            costMicros = 0L,
            costLimitMicros = 0L,
        )
    }

    /**
     * How long the live run has been going.
     *
     * A terminal phase stops the clock at the moment the run got there, so a
     * finished run reports its own duration instead of the time since it ended.
     */
    private fun elapsedMs(): Long {
        if (runStartedAtMs == 0L) return 0L
        val now = if (state.phase.isTerminal) runEndedAtMs else clock.nowMs()
        return (now - runStartedAtMs).coerceAtLeast(0L)
    }

    /**
     * Notes that a control request named a run other than the live one.
     *
     * The facade holds a single run, and the IPC contract in `API_SPEC.md` §2
     * is run-scoped, so every call carries a run id. The request is still
     * honoured against the live run — refusing a stale id would mean dropping
     * an abort, which `I12` forbids — but the mismatch is on the log with the
     * id it arrived with, so a run can be audited after the fact.
     */
    private fun correlate(runId: String, request: String) {
        if (runId == currentRunId) return
        Log.w(TAG, "$request names run $runId, but the live run is $currentRunId")
    }

    private fun publish(next: RunState) {
        state = next
        if (next.phase.isTerminal && runStartedAtMs != 0L) runEndedAtMs = clock.nowMs()
        lightIndicator(next)
        service?.publishState(next)
        service?.publishEvent(
            type = "state.${next.phase.name.lowercase()}",
            runId = next.runId,
            intent = next.currentIntent,
            reason = next.currentReason,
        )
    }

    /**
     * Puts the current state on the glass indicator.
     *
     * The window belongs to the accessibility service, in the console process,
     * which is why the run reaches it through the bridge rather than drawing
     * anything itself. A run that is over, idle or refused leaves nothing lit:
     * `STYLE_GUIDE.md` §4 says the indicator means *the agent is acting*, and
     * the foreground notification is what stays up afterwards.
     */
    private fun lightIndicator(next: RunState) {
        if (next.phase == Phase.IDLE || next.phase.isTerminal) {
            indicator.hideIndicator()
        } else {
            indicator.showIndicator(next.indicatorState.name, next.indicatorMode.name)
        }
    }

    /**
     * The approval signal.
     *
     * The decision arrives from another surface, so it is read back from the
     * durable store rather than pushed.
     */
    private fun approvalSignal(): ApprovalSignal = ApprovalSignal { approvalId ->
        awaitApproval(approvalId)
    }

    /**
     * Waits for an approval to be decided.
     *
     * The decision is read back from the durable store, because it may arrive
     * from another process — the glass card, the notification, or the web
     * console. The phase says what the run is doing while it waits: this is the
     * one place the loop is known to be blocked on the user, and `I4` says it
     * stays blocked there, so the indicator goes amber and the console moves
     * out of *running* until an answer arrives.
     */
    private suspend fun awaitApproval(approvalId: dev.lumen.core.model.ApprovalId): ApprovalState {
        publish(state.copy(phase = Phase.WAITING_APPROVAL, indicatorState = IndicatorState.AWAITING_APPROVAL))
        val decision = awaitDecision(approvalId)
        publish(state.copy(phase = Phase.RUNNING, indicatorState = IndicatorState.WORKING))
        return decision
    }

    private suspend fun awaitDecision(approvalId: dev.lumen.core.model.ApprovalId): ApprovalState {
        while (true) {
            val record = store.loadApproval(approvalId) ?: return ApprovalState.EXPIRED
            if (record.state != ApprovalState.PENDING) {
                publishApproval(record)
                return record.state
            }
            delay(APPROVAL_POLL_MS)
        }
    }

    private fun publishApproval(record: dev.lumen.core.model.ApprovalRecord) {
        service?.publishApproval(
            ApprovalPayload(
                runId = record.runId.value,
                approvalId = record.id.value,
                stepId = record.stepId.value,
                riskClass = record.riskClass.name,
                title = record.title,
                detail = record.detail,
                app = record.app,
                appLabel = record.appLabel,
                consequences = record.consequences,
                actionPreview = PreviewPayload(
                    kind = record.actionPreview.kind,
                    label = record.actionPreview.label,
                    text = record.actionPreview.text,
                    target = record.actionPreview.target,
                ),
                expiresAtMs = record.leaseExpiresAtMs,
                leaseSeconds = ((record.leaseExpiresAtMs - record.createdAtMs) / 1000L).toInt(),
            ),
        )
    }

    public companion object {
        private const val TAG = "LumenAgentFacade"

        private const val APPROVAL_POLL_MS = 100L

        /** What the console is told when a run arrives with no brain to plan with. */
        private const val NO_BRAIN_MESSAGE =
            "no brain is configured: set the endpoint, the model id and the key in the app before starting a run"

        /** What the console is told when a second run is asked for while one is live. */
        private const val ONE_RUN_MESSAGE =
            "a run is already going: stop it before starting another"
    }
}
