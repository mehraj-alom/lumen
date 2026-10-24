package dev.lumen.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.lumen.agent.ipc.AgentStatePayload
import dev.lumen.agent.ipc.ApprovalPayload
import dev.lumen.agent.ipc.BrainConfigPayload
import dev.lumen.agent.ipc.BudgetPayload
import dev.lumen.agent.ipc.GoalPayload
import dev.lumen.agent.ipc.IAgentRuntime
import dev.lumen.agent.ipc.IAgentStateCallback
import dev.lumen.agent.ipc.StepEventPayload
import dev.lumen.agent.service.AgentRuntimeService
import dev.lumen.app.ui.ApprovalCard
import dev.lumen.app.ui.ConsoleState
import dev.lumen.app.ui.TraceLine
import dev.lumen.core.model.UlidGenerator
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.Json

/**
 * The console's side of the agent IPC.
 *
 * The runtime lives in the `:agent` process and the console in `:ui`, so every
 * fact on the screen arrives through this class and every request leaves through
 * it. The console renders what it is told and holds no run state of its own
 * (`ARCH.md` §5, `API_SPEC.md` §2).
 *
 * The AIDL callbacks arrive on a binder thread and the screen state is read by
 * Compose, so every payload is decoded and applied on the main thread. The
 * budget is fetched when the state changes rather than on a timer: a meter that
 * moves with the run is the same information as a polling one, without a loop
 * that needs cancelling.
 */
public class AgentConnection(context: Context) {

    private val appContext: Context = context.applicationContext
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val ids = UlidGenerator()
    private val main = Handler(Looper.getMainLooper())

    /** What the user configured. The console writes it and sends it with each goal. */
    public val settings: AppSettings = AppSettings(appContext)

    /** What the console renders. Written on the main thread only. */
    public var state: ConsoleState by mutableStateOf(ConsoleState())
        private set

    /** Whether a run can be started at all: the brain has to be configured. */
    public var isConfigured: Boolean by mutableStateOf(settings.brainIsConfigured)
        private set

    private var runtime: IAgentRuntime? = null
    private var runId: String = ""
    private var bound = false

    private val callbacks = object : IAgentStateCallback.Stub() {

        override fun onEvent(eventJson: String?) {
            main.post { applyEvent(eventJson) }
        }

        override fun onStateChanged(stateJson: String?) {
            main.post { applyState(stateJson) }
        }

        override fun onApprovalRequired(approvalJson: String?) {
            main.post { applyApproval(approvalJson) }
        }

        override fun onError(code: String?, message: String?, correlationId: String?) {
            main.post {
                val text = "${code ?: "error"}: ${message ?: "no detail"}"
                state = state.copy(trace = traced(TraceLine(text, KIND_ERROR)))
                Log.w(TAG, "the runtime reported $text (correlation ${correlationId ?: "none"})")
            }
        }
    }

    private val connection = object : ServiceConnection {

        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val agent = IAgentRuntime.Stub.asInterface(service)
            runtime = agent
            try {
                agent.registerStateCallback(callbacks)
            } catch (error: Exception) {
                // A dead binder here means the runtime died with the callback
                // unregistered; the screen keeps the last state and says so.
                Log.w(TAG, "the state callback could not be registered", error)
            }
            refreshBudget()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            runtime = null
            main.post { state = state.copy(trace = traced(TraceLine("the runtime process went away", KIND_ERROR))) }
        }

        override fun onBindingDied(name: ComponentName?) {
            runtime = null
            // The service was killed. The bind is dropped so the next onResume
            // can start it again rather than leaving a dead hook in place.
            unbind()
        }
    }

    /**
     * Starts the runtime process and binds to it.
     *
     * The service is started in the foreground because a run lasts as long as
     * forty-five minutes and the platform would otherwise kill it; binding and
     * starting are two calls on purpose, because the binder only exists for a
     * bound service and the notification only exists for a started one.
     *
     * Notifications are not checked here. A blocked notification hides the
     * runtime's indicator, it does not stop the service, and a run is still
     * stoppable from the console's own control. Refusing the bind over it would
     * leave the app unable to reach the runtime at all, with no way back for a
     * user who declined the prompt and cannot get it to show again.
     */
    public fun connect() {
        if (bound) return
        val intent = Intent(appContext, AgentRuntimeService::class.java)
        try {
            appContext.startForegroundService(intent)
        } catch (error: Exception) {
            // A device that refuses the foreground start still allows the bind:
            // the run goes, the notification does not.
            Log.w(TAG, "the runtime could not be started in the foreground; binding anyway", error)
        }
        bound = appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        if (!bound) Log.w(TAG, "the runtime could not be bound")
    }

    /** Drops the bind. The run continues; the console is only a view. */
    public fun disconnect() {
        unbind()
    }

    private fun unbind() {
        runtime?.let { agent ->
            try {
                agent.unregisterStateCallback(callbacks)
            } catch (error: Exception) {
                Log.w(TAG, "the state callback could not be unregistered", error)
            }
        }
        if (bound) {
            runCatching { appContext.unbindService(connection) }
                .onFailure { Log.w(TAG, "the runtime bind was already gone", it) }
        }
        bound = false
        runtime = null
    }

    /**
     * Starts a run.
     *
     * The console mints the run id so it can name the run on every later
     * request; the service returns the id it accepted, and that answer wins.
     * The goal carries the brain configuration with it, because the run has to
     * be auditable against the endpoint and the consent it was started with.
     */
    public fun start(text: String) {
        val agent = runtime
        if (agent == null) {
            // A goal typed while the runtime is not bound would otherwise be
            // swallowed by the screen the user is looking at. The bind is asked
            // for again so the next attempt has something to talk to.
            state = state.copy(trace = traced(TraceLine(NO_GOAL_MESSAGE, KIND_ERROR)))
            connect()
            return
        }
        val proposed = ids.next()
        val payload = GoalPayload(runId = proposed, text = text, brain = settings.brain)
        val accepted = try {
            agent.startGoal(json.encodeToString(GoalPayload.serializer(), payload))
        } catch (error: Exception) {
            Log.w(TAG, "the goal could not be sent", error)
            null
        }
        runId = accepted ?: proposed
        state = state.copy(
            phase = PHASE_PLANNING,
            currentIntent = text,
            trace = traced(TraceLine("goal sent: $text", KIND_GOAL)),
        )
    }

    /** Saves the brain configuration. Nothing is sent anywhere by this call. */
    public fun saveBrain(brain: BrainConfigPayload) {
        settings.brain = brain
        isConfigured = settings.brainIsConfigured
    }

    /** Answers an approval. The decision is the only irreversible thing here. */
    public fun decide(approvalId: String, approve: Boolean) {
        val agent = runtime
        if (agent == null) {
            // The card stays on the screen, because an answer that never left
            // must not look like an answer that was given: the gate times out
            // denied, and the user has to know the difference.
            state = state.copy(trace = traced(TraceLine(NO_ANSWER_MESSAGE, KIND_ERROR)))
            connect()
            return
        }
        val decision = if (approve) "APPROVE" else "DENY"
        try {
            agent.answerApproval(runId, approvalId, decision, null)
        } catch (error: Exception) {
            Log.w(TAG, "the approval $approvalId could not be answered", error)
            return
        }
        state = state.copy(approvals = state.approvals.filterNot { it.approvalId == approvalId })
    }

    /** Stops the run. `I12` says the user outranks the bookkeeping, so this
     *  asks the runtime to abort whatever it is holding. */
    public fun abort() {
        val agent = runtime
        if (agent == null) {
            // `I12` does not allow a stop button that does nothing in silence.
            // The screen says the request did not leave, and names the control
            // that reaches the runtime without this bind.
            state = state.copy(trace = traced(TraceLine(NO_ABORT_MESSAGE, KIND_ERROR)))
            connect()
            return
        }
        try {
            agent.abortRun(runId, "stopped from the console")
        } catch (error: Exception) {
            Log.w(TAG, "the abort could not be sent", error)
        }
    }

    private fun refreshBudget() {
        val agent = runtime ?: return
        val wire = try {
            agent.getBudget(runId)
        } catch (error: Exception) {
            Log.w(TAG, "the budget could not be read", error)
            null
        }
        val budget = decode(BudgetPayload.serializer(), wire) ?: return
        main.post {
            state = state.copy(
                ramUsedMiB = budget.ramUsedMiB,
                ramLimitMiB = budget.ramLimitMiB,
                storageUsedMiB = budget.storageUsedMiB.toInt(),
                storageLimitMiB = budget.storageLimitMiB.toInt(),
                stepsUsed = budget.stepsUsed,
                stepsHardLimit = budget.stepsHardLimit,
            )
        }
    }

    private fun applyState(wire: String?) {
        val payload = decode(AgentStatePayload.serializer(), wire) ?: return
        state = state.copy(
            phase = payload.phase,
            stepIndex = payload.stepIndex,
            stepCount = payload.stepCount,
            currentIntent = payload.currentIntent.orEmpty(),
            currentReason = payload.currentReason.orEmpty(),
            brainRoute = payload.brainRoute,
            takeoverActive = payload.takeoverActive,
        )
        refreshBudget()
    }

    private fun applyEvent(wire: String?) {
        val event = decode(StepEventPayload.serializer(), wire) ?: return
        val text = event.reason ?: event.intent ?: event.text ?: event.type
        state = state.copy(trace = traced(TraceLine(text, event.type)))
    }

    private fun applyApproval(wire: String?) {
        val approval = decode(ApprovalPayload.serializer(), wire) ?: return
        if (state.approvals.any { it.approvalId == approval.approvalId }) return
        state = state.copy(
            phase = PHASE_WAITING,
            approvals = state.approvals + ApprovalCard(
                approvalId = approval.approvalId,
                title = approval.title,
                detail = approval.detail,
                riskClass = approval.riskClass,
            ),
        )
    }

    private fun traced(line: TraceLine): List<TraceLine> = (state.trace + line).takeLast(MAX_TRACE_LINES)

    private fun <T> decode(strategy: DeserializationStrategy<T>, wire: String?): T? = try {
        wire?.let { json.decodeFromString(strategy, it) }
    } catch (error: Exception) {
        // A payload the console cannot read is a contract change, not a reason
        // to lose the screen. The line is kept so the shape can be compared
        // against API_SPEC.md.
        Log.w(TAG, "a runtime payload could not be read", error)
        null
    }

    private companion object {
        private const val TAG = "LumenConsoleConnection"

        private const val MAX_TRACE_LINES = 50

        private const val PHASE_PLANNING = "PLANNING"
        private const val PHASE_WAITING = "WAITING_APPROVAL"

        /** What a control says when there is no binder behind it. */
        private const val NO_GOAL_MESSAGE = "the runtime is not connected; the goal was not sent"
        private const val NO_ABORT_MESSAGE = "the runtime is not connected; the stop did not leave — the notification's Stop reaches it"
        private const val NO_ANSWER_MESSAGE = "the runtime is not connected; the answer was not sent, so the gate will time out denied"

        private const val KIND_ERROR = "error"
        private const val KIND_GOAL = "goal"
    }
}
