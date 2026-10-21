package dev.lumen.agent.service

import dev.lumen.agent.ipc.ApprovalPayload
import dev.lumen.agent.ipc.BudgetPayload
import dev.lumen.agent.ipc.GoalPayload
import dev.lumen.agent.ipc.Payloads
import dev.lumen.agent.ipc.PreviewPayload
import dev.lumen.core.agent.AgentRuntime
import dev.lumen.core.agent.ApprovalSignal
import dev.lumen.core.agent.IndicatorState
import dev.lumen.core.agent.RunState
import dev.lumen.core.agent.StepEnvironment
import dev.lumen.core.model.ApprovalId
import dev.lumen.core.model.ApprovalState
import dev.lumen.core.policy.ApprovalBroker
import dev.lumen.core.policy.ApprovalDecision
import dev.lumen.core.policy.PolicyEngine
import dev.lumen.core.store.RoomStateStore
import dev.lumen.core.brain.BrainPort
import dev.lumen.core.quota.QuotaWatchdog
import dev.lumen.core.model.UlidGenerator
import dev.lumen.core.model.LumenClock
import dev.lumen.core.model.SystemLumenClock
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
    private val approvals: ApprovalBroker,
    private val policy: PolicyEngine,
    private val quota: QuotaWatchdog,
    private val clock: LumenClock = SystemLumenClock,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val ids = UlidGenerator(clock = clock)
    private val abort = dev.lumen.core.agent.AbortSignal()

    private var service: AgentRuntimeService? = null
    private var currentRunId: String = ""
    private var state: RunState = RunState(runId = "")
    private var takeoverActive = false

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
        publish(RunState(runId = payload.runId, phase = Phase.PLANNING))

        scope.launch {
            val goal = Payloads.goal(payload)
            val runtime = AgentRuntime(
                brain = brain,
                environment = environment,
                policy = policy,
                approvals = approvals,
                store = store,
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

    /** Pauses a run. */
    public fun pauseRun(runId: String, reason: String) {
        publish(state.copy(phase = Phase.PAUSED, currentReason = reason))
    }

    /** Resumes a paused run. */
    public fun resumeRun(runId: String) {
        publish(state.copy(phase = Phase.RUNNING))
    }

    /** Aborts a run. */
    public fun abortRun(runId: String, reason: String) {
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
    public fun answerApproval(
        runId: String,
        approvalId: String,
        decision: String,
        answerText: String?,
    ) {
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
        publish(state.copy(brainRoute = pin.uppercase()))
    }

    /** Takes the run over, or gives it back. */
    public fun setTakeover(runId: String, takenOver: Boolean) {
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
        val snapshot = quota.budget.snapshot()
        return BudgetPayload(
            ramUsedMiB = snapshot.usedMiB,
            ramLimitMiB = snapshot.limitMiB,
            ramByCategory = snapshot.byCategory.mapKeys { it.key.name },
            storageUsedMiB = 0L,
            storageLimitMiB = 20_000L,
            stepsUsed = state.stepIndex,
            stepsSoftLimit = 150,
            stepsHardLimit = 400,
            elapsedMs = 0L,
            wallClockLimitMs = 45L * 60L * 1000L,
            costMicros = 0L,
            costLimitMicros = 0L,
        )
    }

    private fun publish(next: RunState) {
        state = next
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
        while (true) {
            val record = store.loadApproval(approvalId)
            if (record == null) return@ApprovalSignal ApprovalState.EXPIRED
            if (record.state != ApprovalState.PENDING) {
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
                return@ApprovalSignal record.state
            }
            delay(APPROVAL_POLL_MS)
        }
    }

    public companion object {
        private const val APPROVAL_POLL_MS = 100L

        /** The running facade, or null before the service wires one. */
        @Volatile
        public var instance: AgentRuntimeFacade? = null
            private set

        /** Builds the facade and publishes it. */
        public fun create(
            environment: StepEnvironment,
            brain: BrainPort,
            store: RoomStateStore,
            approvals: ApprovalBroker,
            policy: PolicyEngine,
            quota: QuotaWatchdog,
            clock: LumenClock = SystemLumenClock,
        ): AgentRuntimeFacade {
            val facade = AgentRuntimeFacade(
                environment = environment,
                brain = brain,
                store = store,
                approvals = approvals,
                policy = policy,
                quota = quota,
                clock = clock,
            )
            instance = facade
            return facade
        }
    }
}
