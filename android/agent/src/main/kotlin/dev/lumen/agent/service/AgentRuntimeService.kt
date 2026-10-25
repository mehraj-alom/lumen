package dev.lumen.agent.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import dev.lumen.agent.AgentRuntimeFactory
import dev.lumen.agent.R
import dev.lumen.agent.ipc.IAgentFrameCallback
import dev.lumen.agent.ipc.IAgentRuntime
import dev.lumen.agent.ipc.IAgentStateCallback
import dev.lumen.core.agent.RunState
import dev.lumen.core.model.Phase
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * The agent runtime service.
 *
 * The service runs in the `:agent` process, away from the console, so that
 * destroying the console does not interrupt a run. It is a foreground
 * service, because a run can take forty-five minutes and the platform
 * would otherwise kill it.
 *
 * The notification is not the visual indicator. It is the platform's
 * requirement for a foreground service, and it is the one signal that
 * always tells the user the agent is running — including when the
 * accessibility service has been revoked and the overlay has gone with it.
 */
public class AgentRuntimeService : android.app.Service() {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val sequence = AtomicLong(0)
    private val main = Handler(Looper.getMainLooper())

    /** Whether a run is going. The notification says so, so it has to be true. */
    @Volatile
    private var runIsLive = false

    private val stateCallbacks = mutableListOf<IAgentStateCallback>()
    private val frameCallbacks = mutableListOf<IAgentFrameCallback>()
    private val callbackLock = Any()

    private val binder = object : IAgentRuntime.Stub() {

        override fun startGoal(goalJson: String?): String {
            val payload = goalJson?.let { parseGoal(it) } ?: return ""
            val runId = payload.runId
            scope.launch { runtime?.startGoal(payload) }
            return runId
        }

        override fun pauseRun(runId: String?, reason: String?) {
            scope.launch { runtime?.pauseRun(runId.orEmpty(), reason.orEmpty()) }
        }

        override fun resumeRun(runId: String?) {
            scope.launch { runtime?.resumeRun(runId.orEmpty()) }
        }

        override fun abortRun(runId: String?, reason: String?) {
            scope.launch { runtime?.abortRun(runId.orEmpty(), reason.orEmpty()) }
        }

        override fun answerApproval(runId: String?, approvalId: String?, decision: String?, answerText: String?) {
            scope.launch {
                runtime?.answerApproval(
                    runId.orEmpty(),
                    approvalId.orEmpty(),
                    decision.orEmpty(),
                    answerText,
                )
            }
        }

        override fun setBrainPin(runId: String?, pin: String?) {
            scope.launch { runtime?.setBrainPin(runId.orEmpty(), pin.orEmpty()) }
        }

        override fun setTakeover(runId: String?, takenOver: Boolean) {
            scope.launch { runtime?.setTakeover(runId.orEmpty(), takenOver) }
        }

        override fun getState(runId: String?): String {
            val state = runtime?.state(runId.orEmpty())
            val payload = if (state == null) {
                dev.lumen.agent.ipc.AgentStatePayload(
                    runId = runId.orEmpty(),
                    phase = "IDLE",
                    stepIndex = 0,
                    stepCount = 0,
                )
            } else {
                dev.lumen.agent.ipc.Payloads.state(state)
            }
            return json.encodeToString(dev.lumen.agent.ipc.AgentStatePayload.serializer(), payload)
        }

        override fun getBudget(runId: String?): String {
            val budget = runtime?.budget(runId.orEmpty())
                ?: dev.lumen.agent.ipc.BudgetPayload(
                    ramUsedMiB = 0, ramLimitMiB = 2_048,
                    storageUsedMiB = 0L, storageLimitMiB = 20_000L,
                    stepsUsed = 0, stepsSoftLimit = 150, stepsHardLimit = 400,
                    elapsedMs = 0L, wallClockLimitMs = 45L * 60L * 1000L,
                    costMicros = 0L, costLimitMicros = 0L,
                )
            return json.encodeToString(dev.lumen.agent.ipc.BudgetPayload.serializer(), budget)
        }

        override fun registerStateCallback(callback: IAgentStateCallback?) {
            if (callback == null) return
            synchronized(callbackLock) { stateCallbacks += callback }
            // A listener that registers in the middle of a run is a console the
            // user has just come back to. Everything the run published while it
            // was away is gone, and the one thing on the screen that cannot be
            // reconstructed later is the gate the loop is blocked on: a push
            // nobody received is an approval that ends denied. So the state and
            // the open gates go out again, to the listener that just arrived.
            val facade = runtime ?: return
            scope.launch {
                publishState(facade.state(facade.liveRunId))
                facade.republishOpenGates()
            }
        }

        override fun unregisterStateCallback(callback: IAgentStateCallback?) {
            if (callback == null) return
            synchronized(callbackLock) { stateCallbacks -= callback }
        }

        override fun registerFrameCallback(callback: IAgentFrameCallback?) {
            if (callback == null) return
            synchronized(callbackLock) { frameCallbacks += callback }
        }

        override fun unregisterFrameCallback(callback: IAgentFrameCallback?) {
            if (callback == null) return
            synchronized(callbackLock) { frameCallbacks -= callback }
        }
    }

    /**
     * The runtime this process acts through.
     *
     * It is built here, in the `:agent` process, when the service is created —
     * not by the application in the console process. One process, one runtime,
     * one claim on the RAM and storage budgets (`AGENTS.md` §2).
     */
    private var runtime: AgentRuntimeFacade? = null

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val built = AgentRuntimeFactory(applicationContext).start()
        runtime = built
        built.attach(this)
    }

    /**
     * Handles a start.
     *
     * A stop is answered before anything else, because the tap that brought it
     * may have arrived while the app is in the background — and on API 34 a
     * foreground start refused there would take the process down with the run
     * still going. The service is already in the foreground when its own action
     * is tapped, so promoting it again buys nothing.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopRun("stopped from the notification")
            return START_NOT_STICKY
        }
        goForeground()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        // A run nobody can see or stop must not keep going. The loop checks the
        // abort signal before every dispatch, so this ends it at the next action
        // boundary (`I12`) — the facade's own scope outlives the service's.
        runtime?.let { it.abortRun(it.liveRunId, "the runtime service stopped") }
        runtime?.detach()
        runtime = null
        scope.cancel()
        super.onDestroy()
    }

    /**
     * Promotes the service to the foreground.
     *
     * A failure here is not fatal to the run: on a device that refuses a
     * background foreground-start the agent keeps working and simply loses the
     * notification, so the refusal is logged rather than allowed to kill the
     * process mid-run.
     */
    private fun goForeground() {
        try {
            startForeground(NOTIFICATION_ID, buildNotification())
        } catch (error: Exception) {
            Log.w(TAG, "the runtime could not go foreground; the notification is lost", error)
        }
    }

    /**
     * Aborts the live run.
     *
     * The run id is the one the facade is holding, so the abort is not lost to
     * a stale id on the request (`I12`).
     */
    private fun stopRun(reason: String) {
        val runId = runtime?.liveRunId.orEmpty()
        scope.launch { runtime?.abortRun(runId, reason) }
    }

    /** Pushes a state to the console, and re-posts the notification when the run starts or ends. */
    public fun publishState(state: RunState) {
        val live = state.phase != Phase.IDLE && !state.phase.isTerminal
        if (live != runIsLive) {
            runIsLive = live
            // The notification is the platform's requirement for the foreground
            // service, and the only signal that is always on the device. It has
            // to say whether a run is going, not merely that the app is open.
            main.post { goForeground() }
        }
        val payload = json.encodeToString(
            dev.lumen.agent.ipc.AgentStatePayload.serializer(),
            dev.lumen.agent.ipc.Payloads.state(state),
        )
        push(stateCallbacks) { listener -> listener.onStateChanged(payload) }
    }

    /** Pushes a step event to the console. */
    public fun publishEvent(type: String, runId: String, intent: String?, reason: String?) {
        val event = dev.lumen.agent.ipc.StepEventPayload(
            type = type,
            runId = runId,
            seq = sequence.incrementAndGet(),
            tsMs = System.currentTimeMillis(),
            intent = intent,
            reason = reason,
        )
        val payload = json.encodeToString(
            dev.lumen.agent.ipc.StepEventPayload.serializer(),
            event,
        )
        push(stateCallbacks) { listener -> listener.onEvent(payload) }
    }

    /** Pushes an approval to the console. */
    public fun publishApproval(approval: dev.lumen.agent.ipc.ApprovalPayload) {
        val payload = json.encodeToString(
            dev.lumen.agent.ipc.ApprovalPayload.serializer(),
            approval,
        )
        push(stateCallbacks) { listener -> listener.onApprovalRequired(payload) }
    }

    /** Pushes a frame path to the console. */
    public fun publishFrame(path: String, width: Int, height: Int) {
        val seq = sequence.incrementAndGet()
        push(frameCallbacks) { listener -> listener.onFrame(path, width, height, seq) }
    }

    /**
     * Delivers a payload to every registered callback.
     *
     * A callback whose process is gone is dropped rather than allowed to fail
     * the publish: the console is a view, and the platform killing it — or the
     * user swiping the app away — is not a reason to break a run that is acting
     * on the device. A oneway call to a dead binder still throws, so the throw
     * is what says the listener is gone.
     */
    private inline fun <T> push(list: MutableList<T>, deliver: (T) -> Unit) {
        val listeners = synchronized(callbackLock) { list.toList() }
        listeners.forEach { listener ->
            try {
                deliver(listener)
            } catch (error: Exception) {
                Log.w(TAG, "a runtime callback could not be reached and is dropped", error)
                synchronized(callbackLock) { list -= listener }
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            "LUMEN runtime",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shown while the agent is running."
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val live = runIsLive
        val stopIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, AgentRuntimeService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(if (live) R.string.notification_title else R.string.notification_title_idle))
            .setContentText(getString(if (live) R.string.notification_text else R.string.notification_text_idle))
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        if (!live) return builder.build()

        return builder
            .addAction(
                NotificationCompat.Action(
                    null,
                    getString(R.string.notification_stop),
                    stopIntent,
                ),
            )
            .build()
    }

    private fun parseGoal(goalJson: String): dev.lumen.agent.ipc.GoalPayload? = try {
        json.decodeFromString(dev.lumen.agent.ipc.GoalPayload.serializer(), goalJson)
    } catch (error: Exception) {
        // The console sent something that is not a goal. The run is not
        // started, and the reason stays in the log with the payload it came
        // from, so a malformed write is auditable.
        Log.w(TAG, "the goal payload could not be read", error)
        null
    }

    public companion object {
        private const val TAG = "LumenAgentService"

        private const val CHANNEL_ID = "lumen_runtime"
        private const val NOTIFICATION_ID = 0x4C55
        private const val ACTION_STOP = "dev.lumen.agent.action.STOP"
    }
}
