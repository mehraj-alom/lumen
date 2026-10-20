package dev.lumen.agent.service

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * The long-horizon agent runtime, hosted as a foreground service.
 *
 * M0 is a skeleton so the manifest, the process split and the notification
 * contract can be verified. M9 wires the Planner–Actor–Critic loop into it
 * and M10 adds the AIDL interface, the state stream and the keepalive job
 * (`TASK.md`).
 */
class AgentRuntimeService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY
}
