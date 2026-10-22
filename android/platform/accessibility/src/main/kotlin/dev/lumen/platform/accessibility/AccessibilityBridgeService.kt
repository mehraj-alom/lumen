package dev.lumen.platform.accessibility

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log

/**
 * The bound service that carries the accessibility bridge.
 *
 * `AccessibilityService.onBind` is final, so the accessibility service
 * cannot hand out its own binder. This service runs in the same process, in
 * the same application, and returns the bridge binder that the agent process
 * binds to.
 *
 * The service is not exported. The agent is another process of the same
 * application, so it is allowed, and nothing outside the application is.
 */
public class AccessibilityBridgeService : Service() {

    override fun onBind(intent: Intent?): IBinder? {
        val service = LumenAccessibilityService.instance
        if (service == null) {
            Log.w(TAG, "the accessibility service is not enabled, so the bridge is not available")
            return null
        }
        return service.bridge
    }

    public companion object {
        private const val TAG = "LumenBridge"
    }
}
