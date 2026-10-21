package dev.lumen.platform.virtualdisplay

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Build
import android.util.Log

/**
 * The private display the agent can move an app onto.
 *
 * A virtual display is a surface the user cannot see. An app launched onto
 * it renders there, and the live screen is untouched. That is the strongest
 * form of the isolation the product promises.
 *
 * Not every app can live on a secondary display. Most can, but some refuse:
 * a single-display app, or one that checks whether it is on a secondary
 * screen. The manager reports what it can do rather than assuming, and the
 * surface policy falls back to the live surface when the idle gate allows.
 */
public class VirtualDisplayManager(private val context: Context) {

    private val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    private val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

    private val lock = Any()
    private val displays = mutableMapOf<Int, VirtualDisplay>()

    /** The number of private displays that are currently open. */
    public fun openDisplayCount(): Int = synchronized(lock) { displays.size }

    /**
     * Opens a private display.
     *
     * @return the display id, or null when the platform refused
     */
    public fun acquire(
        widthPx: Int = DEFAULT_WIDTH,
        heightPx: Int = DEFAULT_HEIGHT,
        densityDpi: Int = DEFAULT_DENSITY,
    ): Int? = try {
        val display = displayManager.createVirtualDisplay(
            VIRTUAL_DISPLAY_NAME,
            widthPx,
            heightPx,
            densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            null,
            null,
        )
        synchronized(lock) { displays[display.displayId] = display }
        display.displayId
    } catch (error: Exception) {
        Log.w(TAG, "the virtual display could not be created", error)
        null
    }

    /**
     * Whether [packageName] is likely to render on a secondary display.
     *
     * This is a hint, not a promise. The platform decides when the activity
     * actually starts, and an app that refuses lands on the default display
     * instead. The surface policy treats a refusal as a fallback rather
     * than a failure.
     */
    public fun canHost(packageName: String): Boolean {
        val intent = packageManager.getLaunchIntentForPackage(packageName) ?: return false
        val component = intent.component ?: return false

        val info = try {
            packageManager.getActivityInfo(component, PackageManager.GET_META_DATA)
        } catch (error: PackageManager.NameNotFoundException) {
            return false
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            if (!info.isResizeableMode) return false
        }
        if (info.excludeFromRecents) return false
        return true
    }

    /**
     * Launches [packageName] onto the display with [displayId].
     *
     * @return true when the launch was requested; the platform decides
     *   whether the app actually went there
     */
    public fun launchOn(packageName: String, displayId: Int): Boolean {
        val intent = packageManager.getLaunchIntentForPackage(packageName) ?: return false

        val options = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            android.app.ActivityOptions.makeBasic().setLaunchDisplayId(displayId)
        } else {
            return false
        }

        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent, options.toBundle())
            true
        } catch (error: Exception) {
            Log.w(TAG, "the app could not be launched on the private display", error)
            false
        }
    }

    /**
     * Whether the task of [packageName] is on the display with [displayId].
     */
    public fun isTaskOn(packageName: String, displayId: Int): Boolean {
        val tasks = try {
            activityManager.appTasks
        } catch (error: Exception) {
            return false
        }
        return tasks.any { task ->
            task.taskInfo.displayId == displayId &&
                task.taskInfo.baseIntent?.packageName == packageName
        }
    }

    /** Closes a private display. */
    public fun release(displayId: Int) {
        synchronized(lock) {
            displays.remove(displayId)?.release()
        }
    }

    /** Closes every private display. */
    public fun releaseAll() {
        synchronized(lock) {
            displays.values.forEach { it.release() }
            displays.clear()
        }
    }

    public companion object {
        private const val TAG = "LumenVirtualDisplay"
        private const val VIRTUAL_DISPLAY_NAME = "lumen-private"
        private const val DEFAULT_WIDTH = 1080
        private const val DEFAULT_HEIGHT = 1920
        private const val DEFAULT_DENSITY = 420

        /** The pixel format of the private display. */
        public val PIXEL_FORMAT: Int = PixelFormat.RGBA_8888
    }
}
