package dev.lumen.platform.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import dev.lumen.core.model.Action
import dev.lumen.core.model.Observation
import dev.lumen.core.model.RelativePoint
import dev.lumen.platform.overlay.toIndicatorMode
import dev.lumen.platform.overlay.toIndicatorState
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

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

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val reader = NodeReader()
    private val tracker = UiEventTracker()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val listeners = mutableListOf<IAccessibilityListener>()
    private val lock = Any()

    /**
     * The bridge the agent calls.
     *
     * `AccessibilityService.onBind` is final, so the bridge cannot be the
     * service's own binder. It is exposed through a separate bound service,
     * [AccessibilityBridgeService], in the same process.
     */
    public val bridge: IAccessibilityBridge = object : IAccessibilityBridge.Stub() {

        override fun readTree(maxNodes: Int): String? {
            val observation = readSnapshot(maxNodes)
            return observation?.let { json.encodeToString(Observation.serializer(), it) }
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
            // The reader clamps the request to what the node index is budgeted
            // for (`ARCH.md` §7); the bridge's caller can only ask for less.
            maxNodes = maxNodes,
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

    /**
     * Dispatches one action.
     *
     * The pointer gestures are handled here because they are the actions that
     * put a finger on the screen, and they are the ones `I3` is about.
     * Everything else is answered by the two helpers below, so that each
     * branch list stays short enough to read as the whole of what the device
     * can do.
     */
    private suspend fun perform(action: Action): Boolean = when (action) {
        is Action.Tap -> injector.tap(action.x, action.y)
        is Action.DoubleTap -> injector.doubleTap(action.x, action.y)
        is Action.LongPress -> injector.longPress(action.x, action.y, action.durationMs)
        is Action.Swipe -> injector.swipe(
            action.fromX,
            action.fromY,
            action.toX,
            action.toY,
            action.durationMs,
        )

        is Action.Drag -> injector.drag(action.path, action.durationMs)
        is Action.Scroll -> scroll(action)
        else -> performOnTarget(action)
    }

    /** The actions that work on an element: a node, an app, a field, a link. */
    private suspend fun performOnTarget(action: Action): Boolean = when (action) {
        is Action.ClickById -> clickById(action.viewId)
        is Action.ClickText -> clickText(action.text, action.occurrence)
        is Action.OpenApp -> openApp(action.packageName)
        is Action.OpenUrl -> openUrl(action.url)
        is Action.InputText -> inputText(action.text)
        is Action.PasteText -> inputText(action.text)
        Action.PressEnter -> pressEnter()
        else -> performWithoutEffect(action)
    }

    /**
     * The actions that answer the planner instead of moving the device.
     *
     * A primitive this service does not implement is refused rather than
     * guessed at, and the refusal is logged, because `I9` says no action
     * happens without an auditable reason and an invented one is worse.
     */
    private suspend fun performWithoutEffect(action: Action): Boolean = when (action) {
        is Action.ListApps, is Action.ObserveScreen -> true
        is Action.Screenshot -> captureScreenBlocking() != null
        is Action.ReadFile -> false
        else -> {
            Log.w(TAG, "the action is not implemented on this device: ${action.javaClass.simpleName}")
            false
        }
    }

    /**
     * Turns a scroll into a swipe.
     *
     * A scroll is a short stroke along a lane, not a fling: it has to stay
     * inside the list it is scrolling, so both the lane and the travel are
     * fractions of the display rather than pixel counts.
     */
    private suspend fun scroll(action: Action.Scroll): Boolean {
        val centreX = action.lane?.fraction ?: SCROLL_DEFAULT_LANE_FRACTION
        val startY = when (action.direction) {
            dev.lumen.core.model.ScrollDirection.UP -> SCROLL_LOWER_ANCHOR_FRACTION
            dev.lumen.core.model.ScrollDirection.DOWN -> SCROLL_UPPER_ANCHOR_FRACTION
            else -> SCROLL_CENTRE_ANCHOR_FRACTION
        }
        val endY = when (action.direction) {
            dev.lumen.core.model.ScrollDirection.UP -> SCROLL_UPPER_ANCHOR_FRACTION
            dev.lumen.core.model.ScrollDirection.DOWN -> SCROLL_LOWER_ANCHOR_FRACTION
            else -> SCROLL_CENTRE_ANCHOR_FRACTION
        }
        val distance = action.amount.coerceIn(SCROLL_MIN_AMOUNT_FRACTION, SCROLL_MAX_AMOUNT_FRACTION) *
            SCROLL_TRAVEL_FRACTION

        val from = RelativePoint(centreX.coerceIn(0.0, 1.0)) to
            RelativePoint(startY.coerceIn(0.0, 1.0))
        val to = RelativePoint(centreX.coerceIn(0.0, 1.0)) to
            RelativePoint((startY + (endY - startY) * distance).coerceIn(0.0, 1.0))

        return injector.swipe(
            RelativePoint(from.first.fraction),
            RelativePoint(from.second.fraction),
            RelativePoint(to.first.fraction),
            RelativePoint(to.second.fraction),
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
        val action = android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT
        val key = "android.view.accessibility.action.ACTION_SET_TEXT"
        val bundle = Bundle().apply { putCharSequence(key, text) }
        val node = host.rootNode()?.findFocus(FOCUS_INPUT)
            ?: host.rootNode()?.findAccessibilityNodeInfosByViewId(EDIT_TEXT_ID)?.firstOrNull()
            ?: return false
        return node.performAction(action, bundle)
    }

    private suspend fun pressEnter(): Boolean {
        val node = host.rootNode()?.findFocus(FOCUS_INPUT) ?: return false
        return node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
    }

    private suspend fun openApp(packageName: String): Boolean {
        val intent = packageManager.getLaunchIntentForPackage(packageName) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            startActivity(intent)
            true
        } catch (error: Exception) {
            // The launch is refused by the platform (a profile restriction, or
            // an app that is installed but disabled). The step fails, and the
            // reason stays with it.
            Log.w(TAG, "the app could not be opened: $packageName", error)
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
            // No activity is registered for the scheme, or the user's default
            // browser is disabled. Either way the step fails and the platform's
            // reason is kept.
            Log.w(TAG, "the url could not be opened", error)
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
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                        path = saveCapture(result)
                        latch.countDown()
                    }

                    override fun onFailure(errorCode: Int) {
                        path = null
                        latch.countDown()
                    }
                },
            )
        } catch (error: Exception) {
            Log.w(TAG, "the screen could not be captured", error)
            return null
        }

        if (!latch.await(CAPTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) return null
        return path
    }

    /**
     * Saves a screenshot.
     *
     * The accessibility API hands back a hardware buffer, not a bitmap, and
     * the buffer is only readable when the platform made it CPU-readable. A
     * buffer that cannot be wrapped is not a failure of the capture, so it is
     * reported as "no frame" rather than as an error.
     */
    private fun saveCapture(result: AccessibilityService.ScreenshotResult): String? {
        // The hardware buffer and its colourspace are API 30, and the callback
        // that reaches here runs outside the guard in captureScreenBlocking.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val bitmap = wrapHardwareBuffer(result) ?: return null
        return try {
            writeFrame(bitmap)
        } catch (error: Exception) {
            Log.w(TAG, "the capture could not be saved", error)
            null
        } finally {
            // The buffer came off the capture path and is the biggest thing in
            // this process at this moment, so it goes back on every exit.
            bitmap.recycle()
        }
    }

    /**
     * The bitmap form of the hardware buffer, or null when the platform made
     * it unreadable.
     *
     * The guard is repeated here rather than trusted from the caller: the
     * buffer and its colourspace are API 30, and a helper that compiles
     * against them has to say so itself.
     */
    private fun wrapHardwareBuffer(result: AccessibilityService.ScreenshotResult): android.graphics.Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return try {
            val buffer = result.hardwareBuffer ?: return null
            android.graphics.Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
        } catch (error: Exception) {
            Log.w(TAG, "the hardware buffer could not be wrapped", error)
            null
        }
    }

    /**
     * Writes one frame into the rotating frames directory.
     *
     * @return the path of the frame, or null when the directory could not be
     *   made — the storage quota is enforced, not retried.
     */
    private fun writeFrame(bitmap: android.graphics.Bitmap): String? {
        val directory = java.io.File(filesDir, FRAME_DIRECTORY)
        if (!directory.isDirectory && !directory.mkdirs()) return null
        val file = java.io.File(directory, "capture-${System.currentTimeMillis()}.png")
        file.outputStream().use { out ->
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, PNG_COMPRESSION_QUALITY, out)
        }
        return file.absolutePath
    }

    private fun notifyListeners(notify: (IAccessibilityListener) -> Unit) {
        val snapshot = synchronized(lock) { listeners.toList() }
        snapshot.forEach(notify)
    }

    public companion object {
        private const val TAG = "LumenAccessibility"

        private const val FOCUS_INPUT = android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT
        private const val EDIT_TEXT_ID = "android:id/edit"

        private const val ACTION_TIMEOUT_SECONDS: Long = 20L
        private const val CAPTURE_TIMEOUT_SECONDS: Long = 10L

        /** The directory the rotating frames live in (`ARCH.md` §7). */
        private const val FRAME_DIRECTORY = "frames"

        /** PNG is lossless, so the frame a spectator sees is the frame the planner saw. */
        private const val PNG_COMPRESSION_QUALITY = 100

        /** The lane a scroll runs down when the planner did not name one. */
        private const val SCROLL_DEFAULT_LANE_FRACTION = 0.5

        /** Where a downward scroll starts: a quarter of the way down the screen. */
        private const val SCROLL_UPPER_ANCHOR_FRACTION = 0.25

        /** Where the centre of an unspecified scroll direction sits. */
        private const val SCROLL_CENTRE_ANCHOR_FRACTION = 0.5

        /** Where an upward scroll starts: three quarters of the way down. */
        private const val SCROLL_LOWER_ANCHOR_FRACTION = 0.75

        /** The shortest scroll the planner can ask for, as a share of the travel. */
        private const val SCROLL_MIN_AMOUNT_FRACTION = 0.1

        /** The longest scroll the planner can ask for. */
        private const val SCROLL_MAX_AMOUNT_FRACTION = 1.0

        /** How far down the screen one full scroll travels. */
        private const val SCROLL_TRAVEL_FRACTION = 0.4

        /** The connected service, or null. */
        @Volatile
        public var instance: LumenAccessibilityService? = null
            private set
    }
}
