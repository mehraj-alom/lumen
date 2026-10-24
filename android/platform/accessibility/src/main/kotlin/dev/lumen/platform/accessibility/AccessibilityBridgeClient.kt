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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * The agent's client for the accessibility service.
 *
 * The service lives in the console process, so the agent binds to it. The
 * binding is asynchronous, and the agent has to know whether it succeeded:
 * a revoked accessibility service pauses a run with a clear reason rather
 * than failing obscurely.
 */
public class AccessibilityBridgeClient(private val context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val connectedState = MutableStateFlow(false)

    // The binder is set on the main thread by the service connection and read by
    // the run's workers, so the write has to be visible there.
    @Volatile
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

    /**
     * Binds to the bridge.
     *
     * The target is `AccessibilityBridgeService`, never the accessibility
     * service itself: that component is guarded by
     * `BIND_ACCESSIBILITY_SERVICE`, a signature permission only the system
     * holds, so binding to it from this process is a permission denial that
     * reaches the caller as a `SecurityException` and takes the runtime process
     * down before a run exists. The bridge service has no such guard, is not
     * exported, and hands out the same binder.
     */
    public fun connect() {
        if (connection != null) return
        connection = serviceConnection
        val intent = Intent(context, AccessibilityBridgeService::class.java)
        val bound = context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        if (!bound) {
            Log.w(TAG, "the accessibility bridge could not be bound")
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
    public suspend fun openApp(packageName: String): Boolean = awaitBridge()?.openApp(packageName) ?: false

    /**
     * Tells the overlay what the run is doing.
     *
     * Best effort, and it never waits for the binding: the indicator is a
     * signal about a step, not a step itself, and a state change must not be
     * held up — or reordered — behind a binder that may never arrive. With no
     * binding there is no window to light, which is the same outcome as the
     * accessibility service having been revoked.
     */
    public fun showIndicator(state: String, mode: String) {
        val bridge = binder ?: return
        try {
            bridge.setIndicator(state, mode)
        } catch (error: Exception) {
            Log.w(TAG, "the indicator could not be told to show $state in $mode", error)
        }
    }

    /** Takes the indicator off: a run that is over leaves nothing lit. */
    public fun hideIndicator() {
        val bridge = binder ?: return
        try {
            bridge.hideIndicator()
        } catch (error: Exception) {
            Log.w(TAG, "the indicator could not be told to hide", error)
        }
    }

    /**
     * Waits for the binding, or gives up.
     *
     * The bridge only exists while the user has the accessibility service
     * switched on, so a bind can legitimately never complete. The wait is
     * bounded and cancellable: a run that is being aborted must not stay
     * suspended because a binder is missing, and nothing here may hold the main
     * thread.
     */
    private suspend fun awaitBridge(): IAccessibilityBridge? {
        binder?.let { return it }
        withTimeoutOrNull(BIND_TIMEOUT_MS) { connectedState.first { connected -> connected } }
        return binder
    }

    public companion object {
        private const val TAG = "LumenBridge"
        private const val BIND_TIMEOUT_MS = 2_000L
    }
}
