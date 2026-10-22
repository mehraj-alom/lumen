package dev.lumen.agent.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dev.lumen.agent.R
import dev.lumen.agent.ipc.IAgentFrameCallback
import dev.lumen.agent.ipc.IAgentRuntime
import dev.lumen.agent.ipc.IAgentStateCallback
import dev.lumen.core.agent.RunState
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

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val sequence = AtomicLong(0)

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

        override fun answerApproval(
            runId: String?,
            approvalId: String?,
            decision: String?,
            answerText: String?,
        ) {
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
                ?: return json.encodeToString(
                    dev.lumen.agent.ipc.AgentStatePayload.serializer(),
                    dev.lumen.agent.ipc.AgentStatePayload(runId = runId.orEmpty(), phase = "IDLE"),
                )
            return json.encodeToString(dev.lumen.agent.ipc.AgentStatePayload.serializer(), state)
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

    private val runtime: AgentRuntimeFacade? get() = AgentRuntimeFacade.instance

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        runtime?.attach(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        instance = null
        runtime?.detach()
        scope.cancel()
        super.onDestroy()
    }

    /** Pushes a state to the console. */
    public fun publishState(state: RunState) {
        val payload = json.encodeToString(
            dev.lumen.agent.ipc.AgentStatePayload.serializer(),
            dev.lumen.agent.ipc.Payloads.state(state),
        )
        val listeners = synchronized(callbackLock) { stateCallbacks.toList() }
        listeners.forEach { listener -> listener.onStateChanged(payload) }
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
        val listeners = synchronized(callbackLock) { stateCallbacks.toList() }
        listeners.forEach { listener -> listener.onEvent(payload) }
    }

    /** Pushes an approval to the console. */
    public fun publishApproval(approval: dev.lumen.agent.ipc.ApprovalPayload) {
        val payload = json.encodeToString(
            dev.lumen.agent.ipc.ApprovalPayload.serializer(),
            approval,
        )
        val listeners = synchronized(callbackLock) { stateCallbacks.toList() }
        listeners.forEach { listener -> listener.onApprovalRequired(payload) }
    }

    /** Pushes a frame path to the console. */
    public fun publishFrame(path: String, width: Int, height: Int) {
        val listeners = synchronized(callbackLock) { frameCallbacks.toList() }
        listeners.forEach { listener ->
            listener.onFrame(path, width, height, sequence.incrementAndGet())
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
        val stopIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, AgentRuntimeService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
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
        null
    }

    public companion object {
        private const val CHANNEL_ID = "lumen_runtime"
        private const val NOTIFICATION_ID = 0x4C55
        private const val ACTION_STOP = "dev.lumen.agent.action.STOP"

        /** The running service, or null. */
        @Volatile
        public var instance: AgentRuntimeService? = null
            private set
    }
}
