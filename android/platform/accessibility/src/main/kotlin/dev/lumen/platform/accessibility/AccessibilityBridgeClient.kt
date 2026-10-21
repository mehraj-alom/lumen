package dev.lumen.platform.accessibility

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import dev.lumen.core.model.Action
import dev.lumen.core.model.Observation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlin.coroutines.resume

/**
 * The agent's client for the accessibility service.
 *
 * The service lives in the console process, so the agent binds to it. The
 * binding is asynchronous, and the agent has to know whether it succeeded:
 * a revoked accessibility service pauses a run with a clear reason rather
 * than failing obscurely.
 */
public class AccessibilityBridgeClient(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val connectedState = MutableStateFlow(false)

    private var binder: IAccessibilityBridge? = null
    private var connection: ServiceConnection? = null

    /** Whether the service is connected. */
    public val isConnected: StateFlow<Boolean> = connectedState

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            binder = IAccessibilityBridge.Stub.asInterface(service)
            connectedState.value = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            binder = null
            connectedState.value = false
        }
    }

    /** Binds to the service. */
    public fun connect() {
        if (connection != null) return
        connection = serviceConnection
        val intent = Intent(context, LumenAccessibilityService::class.java)
        val bound = context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        if (!bound) {
            Log.w(TAG, "the accessibility service could not be bound")
            connection = null
        }
    }

    /** Unbinds from the service. */
    public fun disconnect() {
        connection?.let { context.unbindService(it) }
        connection = null
        binder = null
        connectedState.value = false
    }

    /** Reads the screen, or null when the service is not connected. */
    public suspend fun readTree(maxNodes: Int = 400): Observation? {
        val bridge = awaitBridge() ?: return null
        val payload = bridge.readTree(maxNodes) ?: return null
        return try {
            json.decodeFromString(Observation.serializer(), payload)
        } catch (error: Exception) {
            Log.w(TAG, "the tree could not be read", error)
            null
        }
    }

    /** Performs an action, on the main thread, because gestures require it. */
    public suspend fun perform(action: Action): Boolean {
        val bridge = awaitBridge() ?: return false
        return bridge.performAction(json.encodeToString(Action.serializer(), action))
    }

    /** The package currently in front. */
    public suspend fun foregroundPackage(): String? = awaitBridge()?.foregroundPackage()

    /** Whether the service is able to act. */
    public suspend fun isReady(): Boolean = awaitBridge()?.isReady() ?: false

    /** Captures the screen, returning the frame's path. */
    public suspend fun captureScreen(): String? = awaitBridge()?.captureScreen()

    /** Opens an application. */
    public suspend fun openApp(packageName: String): Boolean =
        awaitBridge()?.openApp(packageName) ?: false

    /** Waits for the binding, or gives up. */
    private suspend fun awaitBridge(): IAccessibilityBridge? {
        if (binder != null) return binder
        return suspendCancellableCoroutine { continuation ->
            val listener = object : android.os.Handler(android.os.Looper.getMainLooper()) {
                override fun handleMessage(message: android.os.Message) {
                    continuation.resume(binder)
                }
            }
            listener.sendEmptyMessageDelayed(0, BIND_TIMEOUT_MS)
        }
    }

    public companion object {
        private const val TAG = "LumenBridge"
        private const val BIND_TIMEOUT_MS = 2_000L
    }
}
