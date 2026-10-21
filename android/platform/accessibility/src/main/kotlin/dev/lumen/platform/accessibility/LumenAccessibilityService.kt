package dev.lumen.platform.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import dev.lumen.core.model.Action
import dev.lumen.core.model.Observation
import dev.lumen.core.model.RelativePoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The accessibility service.
 *
 * This is the **only** class in the project that touches the
 * `AccessibilityService` APIs. Everything else goes through
 * [IAccessibilityBridge], so the actuator can be replaced by a fake and the
 * agent can be tested without a device.
 *
 * The service lives in the console process, because Android requires an
 * accessibility service to be in a process the user can enable. The agent
 * binds to it from its own process.
 */
public class LumenAccessibilityService : AccessibilityService() {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val reader = NodeReader()
    private val tracker = UiEventTracker()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val listeners = mutableListOf<IAccessibilityListener>()
    private val lock = Any()

    private val bridge = object : Binder(), IAccessibilityBridge.Stub() {

        override fun readTree(maxNodes: Int): String? {
            val snapshot = readSnapshot(maxNodes)
            return snapshot?.let { json.encodeToString(snapshot) }
        }

        override fun performAction(actionJson: String): Boolean {
            val action = try {
                json.decodeFromString(Action.serializer(), actionJson)
            } catch (error: Exception) {
                Log.w(TAG, "the action could not be read", error)
                return false
            }
            return performBlocking(action)
        }

        override fun foregroundPackage(): String? = host.foregroundPackage()

        override fun isReady(): Boolean = host.isServiceEnabled()

        override fun captureScreen(): String? = captureScreenBlocking()

        override fun openApp(packageName: String): Boolean {
            val intent = packageManager.getLaunchIntentForPackage(packageName)
                ?: return false
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return try {
                startActivity(intent)
                true
            } catch (error: Exception) {
                Log.w(TAG, "the app could not be opened", error)
                false
            }
        }

        override fun setIndicator(state: String?, mode: String?) {
            overlay.showIndicator(
                state = state.toIndicatorState(),
                mode = mode.toIndicatorMode(),
            )
        }

        override fun hideIndicator() {
            overlay.hideIndicator()
        }

        override fun registerListener(listener: IAccessibilityListener?) {
            if (listener == null) return
            synchronized(lock) { listeners += listener }
        }

        override fun unregisterListener(listener: IAccessibilityListener?) {
            if (listener == null) return
            synchronized(lock) { listeners -= listener }
        }
    }

    private val host: AccessibilityServiceHost by lazy { ServiceAccessibilityHost(this) }

    /** The overlay windows: the indicator and the approval card. */
    private val overlay: dev.lumen.platform.overlay.OverlayHost by lazy {
        dev.lumen.platform.overlay.OverlayHost(
            context = this,
            windowManager = getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager,
        )
    }

    private val injector: GestureInjector by lazy {
        GestureInjector(this) {
            val metrics = resources.displayMetrics
            metrics.widthPixels to metrics.heightPixels
        }
    }

    override fun onBind(intent: Intent?): IBinder = bridge

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        notifyListeners { it.onServiceConnected() }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        tracker.onEvent(event, SystemClock.uptimeMillis())
    }

    override fun onInterrupt() {
        // The agent is not in the middle of a gesture when this is called.
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        scope.cancel()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        overlay.hideAll()
        scope.cancel()
        super.onDestroy()
    }

    /** The tree, in the model. */
    private fun readSnapshot(maxNodes: Int): Observation? {
        val (width, height) = host.screenSize()
        val root = host.rootNode() ?: return null
        val packageName = host.foregroundPackage() ?: root.packageName?.toString().orEmpty()

        val snapshot = reader.read(
            root = root,
            packageName = packageName,
            screenWidth = width,
            screenHeight = height,
        )
        return snapshot.toObservation()
    }

    /**
     * Performs an action and waits for the result.
     *
     * A binder call is synchronous, and the gesture is not, so the call is
     * made on a coroutine and the binder thread waits for it. The binder
     * thread is a background thread, and the wait is bounded.
     */
    private fun performBlocking(action: Action): Boolean {
        val latch = CountDownLatch(1)
        var result = false

        scope.launch {
            result = try {
                perform(action)
            } catch (error: Exception) {
                Log.w(TAG, "the action failed", error)
                false
            }
            latch.countDown()
        }

        val completed = latch.await(ACTION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        if (!completed) Log.w(TAG, "the action timed out")
        return result && completed
    }

    private suspend fun perform(action: Action): Boolean = when (action) {
        is Action.Tap -> injector.tap(action.x, action.y)
        is Action.DoubleTap -> injector.doubleTap(action.x, action.y)
        is Action.LongPress -> injector.longPress(action.x, action.y, action.durationMs)
        is Action.Swipe -> injector.swipe(
            action.fromX, action.fromY, action.toX, action.toY, action.durationMs,
        )
        is Action.Drag -> injector.drag(action.path, action.durationMs)
        is Action.Scroll -> scroll(action)
        is Action.ClickById -> clickById(action.viewId)
        is Action.ClickText -> clickText(action.text, action.occurrence)
        is Action.OpenApp -> openApp(action.packageName)
        is Action.OpenUrl -> openUrl(action.url)
        is Action.InputText -> inputText(action.text)
        is Action.PasteText -> inputText(action.text)
        Action.PressEnter -> pressEnter()
        is Action.ListApps -> true
        is Action.ObserveScreen -> true
        is Action.Screenshot -> captureScreenBlocking() != null
        is Action.ReadFile -> false
    }

    private suspend fun scroll(action: Action.Scroll): Boolean {
        val (width, height) = host.screenSize()
        val centreX = action.lane?.fraction ?: 0.5
        val startY = when (action.direction) {
            dev.lumen.core.model.ScrollDirection.UP -> 0.75
            dev.lumen.core.model.ScrollDirection.DOWN -> 0.25
            else -> 0.5
        }
        val endY = when (action.direction) {
            dev.lumen.core.model.ScrollDirection.UP -> 0.25
            dev.lumen.core.model.ScrollDirection.DOWN -> 0.75
            else -> 0.5
        }
        val distance = (action.amount).coerceIn(0.1, 1.0) * 0.4

        val from = RelativePoint((centreX).coerceIn(0.0, 1.0)) to
            RelativePoint((startY).coerceIn(0.0, 1.0))
        val to = RelativePoint((centreX).coerceIn(0.0, 1.0)) to
            RelativePoint((startY + (endY - startY) * distance).coerceIn(0.0, 1.0))

        return injector.swipe(
            RelativePoint(from.first.fraction), RelativePoint(from.second.fraction),
            RelativePoint(to.first.fraction), RelativePoint(to.second.fraction),
        )
    }

    private suspend fun clickById(viewId: String): Boolean {
        val root = host.rootNode() ?: return false
        val node = root.findAccessibilityNodeInfosByViewId(viewId).firstOrNull() ?: return false
        val rect = android.graphics.Rect()
        node.getBoundsInScreen(rect)
        val (width, height) = host.screenSize()
        if (width <= 0 || height <= 0) return false
        return injector.tap(
            RelativePoint((rect.centerX().toDouble() / width).coerceIn(0.0, 1.0)),
            RelativePoint((rect.centerY().toDouble() / height).coerceIn(0.0, 1.0)),
        )
    }

    private suspend fun clickText(text: String, occurrence: Int): Boolean {
        val root = host.rootNode() ?: return false
        val nodes = root.findAccessibilityNodeInfosByText(text)
        val node = nodes.getOrNull(occurrence) ?: return false
        val rect = android.graphics.Rect()
        node.getBoundsInScreen(rect)
        val (width, height) = host.screenSize()
        if (width <= 0 || height <= 0) return false
        return injector.tap(
            RelativePoint((rect.centerX().toDouble() / width).coerceIn(0.0, 1.0)),
            RelativePoint((rect.centerY().toDouble() / height).coerceIn(0.0, 1.0)),
        )
    }

    private suspend fun inputText(text: String): Boolean {
        val action = ACTION_SET_TEXT
        val bundle = Bundle().apply { putCharSequence(action, text) }
        val node = host.rootNode()?.findFocus(FOCUS_INPUT)
            ?: host.rootNode()?.findAccessibilityNodeInfosByViewId(EDIT_TEXT_ID)?.firstOrNull()
            ?: return false
        return node.performAction(action, bundle)
    }

    private suspend fun pressEnter(): Boolean {
        val node = host.rootNode()?.findFocus(FOCUS_INPUT) ?: return false
        return node.performAction(ACTION_CLICK)
    }

    private suspend fun openApp(packageName: String): Boolean {
        val intent = packageManager.getLaunchIntentForPackage(packageName) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            startActivity(intent)
            true
        } catch (error: Exception) {
            false
        }
    }

    private suspend fun openUrl(url: String): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            startActivity(intent)
            true
        } catch (error: Exception) {
            false
        }
    }

    /**
     * Captures the screen with the accessibility API.
     *
     * This is the consent-free path: it needs no MediaProjection dialog, so
     * the spectating view works without interrupting the user.
     */
    private fun captureScreenBlocking(): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null

        val latch = CountDownLatch(1)
        var path: String? = null

        try {
            takeScreenshot(
                android.view.Display.DEFAULT_DISPLAY,
                mainExecutor,
                { result ->
                    path = result?.let { saveCapture(it) }
                    latch.countDown()
                },
            )
        } catch (error: Exception) {
            Log.w(TAG, "the screen could not be captured", error)
            return null
        }

        if (!latch.await(CAPTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) return null
        return path
    }

    private fun saveCapture(screenshot: android.graphics.Bitmap): String? = try {
        val directory = java.io.File(filesDir, "frames")
        if (!directory.isDirectory && !directory.mkdirs()) return null
        val file = java.io.File(directory, "capture-${System.currentTimeMillis()}.png")
        file.outputStream().use { out -> screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out) }
        screenshot.recycle()
        file.absolutePath
    } catch (error: Exception) {
        Log.w(TAG, "the capture could not be saved", error)
        null
    }

    private fun notifyListeners(notify: (IAccessibilityListener) -> Unit) {
        val snapshot = synchronized(lock) { listeners.toList() }
        snapshot.forEach(notify)
    }

    public companion object {
        private const val TAG = "LumenAccessibility"

        private const val ACTION_SET_TEXT = "android.view.accessibility.action.ACTION_SET_TEXT"
        private const val ACTION_CLICK = "android.view.accessibility.action.ACTION_CLICK"
        private const val FOCUS_INPUT = android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT
        private const val EDIT_TEXT_ID = "android:id/edit"

        private const val ACTION_TIMEOUT_SECONDS: Long = 20L
        private const val CAPTURE_TIMEOUT_SECONDS: Long = 10L

        /** The connected service, or null. */
        @Volatile
        public var instance: LumenAccessibilityService? = null
            private set
    }
}
