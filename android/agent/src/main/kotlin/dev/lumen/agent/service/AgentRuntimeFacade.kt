package dev.lumen.agent.service

import android.util.Log
import dev.lumen.agent.crash.CrashReporter
import dev.lumen.agent.ipc.ApprovalPayload
import dev.lumen.agent.ipc.BudgetPayload
import dev.lumen.agent.ipc.DeviceSettingsPayload
import dev.lumen.agent.ipc.GoalPayload
import dev.lumen.agent.ipc.Payloads
import dev.lumen.agent.ipc.PreviewPayload
import dev.lumen.core.action.ActionLedger
import dev.lumen.core.agent.AgentRuntime
import dev.lumen.core.agent.ApprovalSignal
import dev.lumen.core.agent.IndicatorMode
import dev.lumen.core.agent.IndicatorState
import dev.lumen.core.agent.RunConfig
import dev.lumen.core.agent.RunProgress
import dev.lumen.core.agent.RunState
import dev.lumen.core.agent.StepEnvironment
import dev.lumen.core.agent.StepProgress
import dev.lumen.core.agent.toIndicatorMode
import dev.lumen.core.brain.BrainConfig
import dev.lumen.core.brain.BrainFactory
import dev.lumen.core.brain.PriceTable
import dev.lumen.core.model.ApiError
import dev.lumen.core.model.ApprovalId
import dev.lumen.core.model.ApprovalState
import dev.lumen.core.model.ErrorCode
import dev.lumen.core.model.Goal
import dev.lumen.core.model.GoalConstraints
import dev.lumen.core.model.LumenClock
import dev.lumen.core.model.Phase
import dev.lumen.core.model.RunId
import dev.lumen.core.model.SystemLumenClock
import dev.lumen.core.model.UlidGenerator
import dev.lumen.core.policy.ApprovalBroker
import dev.lumen.core.policy.ApprovalDecision
import dev.lumen.core.policy.PolicyEngine
import dev.lumen.core.quota.BatteryDecision
import dev.lumen.core.quota.BatteryGovernor
import dev.lumen.core.quota.BatteryProbe
import dev.lumen.core.quota.QuotaWatchdog
import dev.lumen.core.quota.ThermalGovernor
import dev.lumen.core.quota.ThermalProbe
import dev.lumen.core.quota.ThermalReading
import dev.lumen.core.quota.ThermalStatus
import dev.lumen.core.sandbox.StorageQuota
import dev.lumen.core.store.RoomStateStore
import dev.lumen.platform.accessibility.AccessibilityBridgeClient
import dev.lumen.platform.idle.IdleWindow
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TAG = "LumenAgentFacade"

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
    private val crashes: CrashReporter,
    private val idleWindow: IdleWindow,
    private val thermalProbe: ThermalProbe,
    private val batteryProbe: BatteryProbe,
    private val thermalGovernor: ThermalGovernor,
    private val batteryGovernor: BatteryGovernor,
    private val priceTable: PriceTable = PriceTable.Empty,
    private val framePipeline: FramePipeline,
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

    /** The cost of the live run, in micros, accumulated from brain replies. */
    @Volatile
    private var runCostMicros: Long = 0L

    /**
     * The indicator mode the user chose for this device.
     *
     * It is held here rather than in the run state because a run does not own
     * it: the mode is the same for every run, and a run that is started after
     * the setting changes has to light the indicator the user asked for.
     */
    @Volatile
    private var preferredIndicatorMode: IndicatorMode = IndicatorMode.EDGE_GLOW

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
        if (!checkNotAlreadyRunning(payload)) return

        currentRunId = payload.runId
        takeoverActive = false
        runStartedAtMs = clock.nowMs()
        runEndedAtMs = 0L
        runCostMicros = 0L
        val goal = Payloads.goal(payload)
        val config = Payloads.brainConfig(payload)
        publishPlanningState(goal)

        if (!config.isUsable) {
            publishFailure(NO_BRAIN_MESSAGE, ErrorCode.BRAIN_UNAVAILABLE)
            return
        }

        if (!checkBattery()) return

        abort.reset()

        scope.launch {
            runAgent(goal, config)
        }
    }

    private suspend fun runAgent(goal: Goal, config: BrainConfig) {
        val outcome = execute(goal, config)
        publish(
            state.copy(
                phase = phaseForOutcome(outcome),
                indicatorState = if (outcome is dev.lumen.core.agent.RunOutcome.Failed) dev.lumen.core.agent.IndicatorState.ERROR else dev.lumen.core.agent.IndicatorState.IDLE,
                currentReason = currentReasonForOutcome(outcome),
                stepCount = outcome.stepsExecuted,
            ),
        )
    }

    private fun phaseForOutcome(outcome: dev.lumen.core.agent.RunOutcome): dev.lumen.core.agent.Phase = when (outcome) {
        is dev.lumen.core.agent.RunOutcome.Completed -> dev.lumen.core.agent.Phase.DONE
        is dev.lumen.core.agent.RunOutcome.Failed -> dev.lumen.core.agent.Phase.FAILED
        is dev.lumen.core.agent.RunOutcome.Aborted -> dev.lumen.core.agent.Phase.ABORTED
        is dev.lumen.core.agent.RunOutcome.Paused -> dev.lumen.core.agent.Phase.PAUSED
    }

    private fun publishPlanningState(goal: Goal) {
        publish(
            RunState(
                runId = goal.id.value,
                phase = Phase.PLANNING,
                currentIntent = goal.text,
                indicatorState = IndicatorState.WORKING,
                indicatorMode = preferredIndicatorMode,
                brainRoute = goal.constraints.brainPin.name,
            ),
        )
    }

    private fun publishFailure(reason: String, code: ErrorCode) {
        publish(
            state.copy(
                phase = Phase.FAILED,
                indicatorState = IndicatorState.ERROR,
                currentReason = reason,
                lastError = ApiError(code = code, message = reason),
            ),
        )
    }

    private fun checkBattery(): Boolean {
        val battery = batteryGovernor.decide(batteryProbe.read())
        if (battery is BatteryDecision.Suspend) {
            publishFailure(battery.reason, ErrorCode.INVALID_STATE)
            return false
        }
        return true
    }

    private fun checkNotAlreadyRunning(payload: GoalPayload): Boolean {
        val liveBefore = state.phase != Phase.IDLE && !state.phase.isTerminal
        if (liveBefore && payload.runId != currentRunId) {
            Log.w(TAG, "goal ${payload.runId} refused: run $currentRunId is still live")
            publish(
                state.copy(
                    currentReason = ONE_RUN_MESSAGE,
                    lastError = ApiError(code = ErrorCode.INVALID_STATE, message = ONE_RUN_MESSAGE),
                ),
            )
            return false
        }
        return true
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
            progress = object : RunProgress {
                override fun onStep(step: StepProgress) {
                    reportStep(step)
                }

                override fun onStepOutcome(step: StepProgress) {
                    if (step.stepId != null) {
                        service?.publishEvent(
                            type = "step.${step.intent.lowercase().replace(" ", "_")}",
                            runId = state.runId,
                            stepId = step.stepId,
                            intent = step.intent,
                            riskClass = step.riskClass,
                            app = step.app,
                            durationMs = step.durationMs,
                            reason = step.reason,
                        )
                        // Capture a frame after the step settles and publish its
                        // path so the console can show what the screen looked
                        // like. The capture is best effort: a frame that cannot
                        // be taken is simply not published.
                        scope.launch {
                            val path = indicator.captureScreen() ?: return@launch
                            val bytes = try {
                                java.io.File(path).readBytes()
                            } catch (error: Exception) {
                                Log.w(TAG, "the captured frame could not be read", error)
                                return@launch
                            }
                            val runId = dev.lumen.core.model.RunId(state.runId)
                            val stored = framePipeline.store(runId, bytes)
                            if (stored != null) {
                                service?.publishFrame(stored.toRelativePath(), 0, 0)
                            }
                        }
                    }
                }
            },
            abort = abort,
            clock = { clock.nowMs() },
            config = runConfig(goal.constraints),
            batteryCheck = {
                val decision = batteryGovernor.decide(batteryProbe.read())
                if (decision is BatteryDecision.Suspend) decision.reason else null
            },
            thermalCheck = {
                val decision = thermalGovernor.decide(thermalProbe.read())
                if (!decision.localInferenceAllowed) decision.reason else null
            },
            onBrainReply = { reply ->
                val cost = priceTable.costMicros(
                    modelId = config.modelId,
                    promptTokens = reply.promptTokens,
                    completionTokens = reply.completionTokens,
                )
                if (cost != null) runCostMicros += cost
            },
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
                plan = step.plan.map { node ->
                    dev.lumen.core.agent.PlanNode(
                        stepId = node.stepId,
                        intent = node.intent,
                        state = node.state,
                        active = node.active,
                        riskClass = node.riskClass,
                    )
                },
            ),
        )
    }

    /** The limits the loop runs under, from the goal that arrived. */
    private fun runConfig(constraints: GoalConstraints): RunConfig = RunConfig(
        stepsSoftLimit = constraints.stepsSoftLimit,
        stepsHardLimit = constraints.stepsHardLimit,
        wallClockLimitMs = constraints.wallClockLimitMs,
        costLimitMicros = constraints.costLimitMicros,
        allowedPackages = constraints.allowedPackages,
    )

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
        // A run stopped while it waits on a gate would otherwise sit at the gate
        // until the lease runs out, with the stop button pressed and nothing
        // happening. The open gates are closed here, denied, so the loop is at
        // an action boundary again within one poll (`I12`, and `I4`: closing a
        // gate with no answer is a denial, never an approval).
        val run = currentRunId
        if (run.isNotEmpty()) {
            scope.launch { approvals.expireOpen(RunId(run)) }
        }
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
            // The run only looks alive again if it was alive at all. An answer
            // that arrived after a stop is not a reason to put a dead run back
            // in the *working* phase.
            if (!abort.isAborted && !state.phase.isTerminal) {
                publish(state.copy(phase = Phase.RUNNING, indicatorState = IndicatorState.WORKING))
            }
        }
    }

    /** Sets the brain pin. */
    public fun setBrainPin(runId: String, pin: String) {
        correlate(runId, "setBrainPin")
        publish(state.copy(brainRoute = pin.uppercase()))
    }

    /**
     * Applies the settings the console owns.
     *
     * Each of the three has somewhere it lands: the idle window is the number
     * the gate measures the next decision against (`I3`), the flag is the only
     * thing that lets [CrashReporter] write at all, and the indicator mode is
     * both relit for the surface the user is looking at and remembered for the
     * next run. A setting that arrives and changes nothing is a control that
     * lies, so this is the one place all three are honoured.
     */
    public fun applyDeviceSettings(payload: DeviceSettingsPayload) {
        idleWindow.ms = payload.idleWindowMs
        crashes.enabled = payload.crashReporting

        val mode = payload.indicatorMode.toIndicatorMode()
        if (mode == preferredIndicatorMode) return
        preferredIndicatorMode = mode
        // The state is written here rather than through publish: publish stamps
        // the moment a terminal run ended, and a setting changed an hour after a
        // run finished would move that moment and report a longer run than there
        // was. The indicator and the console still get the new mode.
        val next = state.copy(indicatorMode = mode)
        state = next
        lightIndicator(next)
        service?.publishState(next)
    }

    /**
     * Makes the reporter this process's last resort.
     *
     * Installed on the service's own creation, because a runtime that dies on a
     * step it could not recover from otherwise dies with no record at all: the
     * reporter was built, tested and never called. The platform's previous handler
     * is still chained, so writing a report does not change how the process ends —
     * the handler observes the death rather than preventing it. Nothing is written
     * while the user keeps reporting off, which is the same shape as `I4`: the
     * record exists only because the user said it may.
     */
    public fun installCrashReporting() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            crashes.record(error, thread.name)
            previous?.uncaughtException(thread, error)
        }
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
            elapsedMs = elapsedMs,
            wallClockLimitMs = 45L * 60L * 1000L,
            costMicros = runCostMicros,
            costLimitMicros = 0L,
            thermalStatus = thermalStatus(thermalProbe.read()),
            batteryPercent = batteryProbe.read().levelPercent,
        )
    }

    /**
     * How long the live run has been going.
     *
     * A terminal phase stops the clock at the moment the run got there, so a
     * finished run reports its own duration instead of the time since it ended.
     */
    /** How long the live run has been going. */
    private val elapsedMs: Long
        get() {
            if (runStartedAtMs == 0L) return 0L
            val now = if (state.phase.isTerminal) runEndedAtMs else clock.nowMs()
            return (now - runStartedAtMs).coerceAtLeast(0L)
        }

    private fun thermalStatus(reading: ThermalReading): String = when (reading.status) {
        ThermalStatus.NONE -> "NONE"
        ThermalStatus.LIGHT -> "LIGHT"
        ThermalStatus.MODERATE -> "MODERATE"
        ThermalStatus.SEVERE -> "SEVERE"
        ThermalStatus.CRITICAL -> "CRITICAL"
        ThermalStatus.EMERGENCY -> "EMERGENCY"
        ThermalStatus.SHUTDOWN -> "SHUTDOWN"
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
        // The card has to reach the console while the gate is *open*. A record
        // published only when it resolves leaves the screen with nothing to
        // answer, and `I4`'s deny-by-default then denies a step the user would
        // have approved, in silence, because they were never asked.
        store.loadApproval(approvalId)?.let { publishApproval(it) }
        val decision = awaitDecision(approvalId)
        if (!abort.isAborted && !state.phase.isTerminal) {
            publish(state.copy(phase = Phase.RUNNING, indicatorState = IndicatorState.WORKING))
        }
        return decision
    }

    /**
     * Tells a viewer about every gate the live run is still waiting on.
     *
     * The console binds when the user opens it, which can be in the middle of a
     * run. A push that went out while nobody was listening is gone, and a gate
     * nobody can see is a gate that ends denied.
     */
    public suspend fun republishOpenGates() {
        if (currentRunId.isEmpty()) return
        approvals.pending(RunId(currentRunId)).forEach { publishApproval(it) }
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
                state = record.state.name,
            ),
        )
    }

    companion object {
        private const val APPROVAL_POLL_MS = 100L

        /** What the console is told when a run arrives with no brain to plan with. */
        private const val NO_BRAIN_MESSAGE =
            "no brain is configured: set the endpoint, the model id and the key in the app before starting a run"

        /** What the console is told when a second run is asked for while one is live. */
        private const val ONE_RUN_MESSAGE =
            "a run is already going: stop it before starting another"
    }
}
