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
import dev.lumen.core.agent.RunState
import dev.lumen.core.agent.StepEnvironment
import dev.lumen.core.brain.BrainPort
import dev.lumen.core.model.ApprovalId
import dev.lumen.core.model.ApprovalState
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
    private val brain: BrainPort,
    private val store: RoomStateStore,
    private val ledger: ActionLedger,
    private val approvals: ApprovalBroker,
    private val policy: PolicyEngine,
    private val quota: QuotaWatchdog,
    private val storage: StorageQuota,
    private val clock: LumenClock = SystemLumenClock,
) {

    init {
        // The facade is the one run the service can reach, so building it is
        // what makes it current. There is deliberately no factory function:
        // the wiring belongs in `AgentRuntimeFactory`, and a second place to
        // construct a facade is a second place the singleton can drift.
        instance = this
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val ids = UlidGenerator(clock = clock)
    private val abort = dev.lumen.core.agent.AbortSignal()

    private var service: AgentRuntimeService? = null
    private var currentRunId: String = ""
    private var state: RunState = RunState(runId = "")
    private var takeoverActive = false
    private var runStartedAtMs = 0L
    private var runEndedAtMs = 0L

    /** Attaches the service that publishes the state. */
    public fun attach(service: AgentRuntimeService) {
        this.service = service
    }

    /** Detaches the service. */
    public fun detach() {
        service = null
    }

    /** Starts a run from [payload]. */
    public fun startGoal(payload: GoalPayload) {
        currentRunId = payload.runId
        takeoverActive = false
        runStartedAtMs = clock.nowMs()
        runEndedAtMs = 0L
        publish(RunState(runId = payload.runId, phase = Phase.PLANNING))

        scope.launch {
            val goal = Payloads.goal(payload)
            val runtime = AgentRuntime(
                brain = brain,
                environment = environment,
                policy = policy,
                approvals = approvals,
                store = store,
                ledger = ledger,
                approvalSignal = approvalSignal(),
                ids = ids,
                abort = abort,
                clock = { clock.nowMs() },
            )
            val outcome = runtime.run(goal)
            publish(
                state.copy(
                    phase = when (outcome) {
                        is dev.lumen.core.agent.RunOutcome.Completed -> Phase.DONE
                        is dev.lumen.core.agent.RunOutcome.Failed -> Phase.FAILED
                        is dev.lumen.core.agent.RunOutcome.Aborted -> Phase.ABORTED
                        is dev.lumen.core.agent.RunOutcome.Paused -> Phase.PAUSED
                    },
                    indicatorState = IndicatorState.IDLE,
                ),
            )
        }
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
        service?.publishState(next)
        service?.publishEvent(
            type = "state.${next.phase.name.lowercase()}",
            runId = next.runId,
            intent = next.currentIntent,
            reason = next.currentReason,
        )
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
     * console.
     */
    private suspend fun awaitApproval(approvalId: dev.lumen.core.model.ApprovalId): ApprovalState {
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

        /** The running facade, or null before one has been built. */
        @Volatile
        public var instance: AgentRuntimeFacade? = null
            private set
    }
}
