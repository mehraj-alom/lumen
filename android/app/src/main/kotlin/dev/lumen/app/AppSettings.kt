package dev.lumen.app

import android.content.Context
import android.content.SharedPreferences
import dev.lumen.agent.ipc.BrainConfigPayload
import dev.lumen.app.ui.IDLE_WINDOW_DEFAULT_SECONDS
import dev.lumen.app.ui.SettingsState
import dev.lumen.platform.overlay.toIndicatorMode

/**
 * What the user configured on this device.
 *
 * The console owns these values. It writes them from the setup screen and it
 * sends them with every goal, so the runtime process never reads a settings
 * file that another process may be holding a stale copy of — the two processes
 * share no cache, only the wire (`ARCH.md` §5).
 *
 * The key is stored in the app's own private preferences, readable only by this
 * app, and it is sent to exactly one place: the endpoint the user typed, as the
 * bearer token of the completion call. Nothing else carries it and no log line
 * prints it.
 */
public class AppSettings(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    /** The brain the next run starts with. */
    public var brain: BrainConfigPayload
        get() = BrainConfigPayload(
            baseUrl = prefs.getString(KEY_BASE_URL, "").orEmpty(),
            apiKey = prefs.getString(KEY_API_KEY, "").orEmpty(),
            modelId = prefs.getString(KEY_MODEL, "").orEmpty(),
            screenTextEgress = prefs.getBoolean(KEY_SCREEN_TEXT, false),
        )
        set(value) {
            prefs.edit()
                .putString(KEY_BASE_URL, value.baseUrl.trim())
                .putString(KEY_API_KEY, value.apiKey.trim())
                .putString(KEY_MODEL, value.modelId.trim())
                .putBoolean(KEY_SCREEN_TEXT, value.screenTextEgress)
                .apply()
        }

    /** Whether there is enough here for a run to plan with. */
    public val brainIsConfigured: Boolean
        get() = with(brain) { baseUrl.isNotBlank() && apiKey.isNotBlank() && modelId.isNotBlank() }

    /**
     * Whether the user has read the disclosure.
     *
     * The disclosure is shown once, before the accessibility service is asked
     * for. Reading it is not the same as granting the permission, and an app
     * that keeps sending a user back to a page of instructions because the
     * service is off is an app that cannot be used at all. So the page is the
     * gate exactly once, and afterwards the console says what is missing while
     * still showing what can be done.
     */
    public var disclosureAcknowledged: Boolean
        get() = prefs.getBoolean(KEY_DISCLOSURE_ACKNOWLEDGED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_DISCLOSURE_ACKNOWLEDGED, value).apply()
        }

    /**
     * The device settings, as the settings screen shows and changes them.
     *
     * These are the four decisions the runtime acts on and the console owns: how
     * the agent shows itself, where the reasoning runs, how long the device must
     * be untouched before the agent takes the live screen, and whether a crash is
     * written down at all. Only the pin and the screen-text consent are per-run,
     * and both of those travel with the goal, so a run is auditable against the
     * configuration it was started with.
     *
     * Reading the window back gives the gate's own default when the user has
     * never chosen one, so the number on the screen and the number the gate
     * measures against start out identical.
     */
    public var deviceSettings: SettingsState
        get() = SettingsState(
            indicatorMode = prefs.getString(KEY_INDICATOR_MODE, null).toIndicatorMode(),
            brainPin = prefs.getString(KEY_BRAIN_PIN, BRAIN_PIN_AUTO).orEmpty(),
            spectatingEnabled = brain.screenTextEgress,
            crashReportingEnabled = prefs.getBoolean(KEY_CRASH_REPORTING, false),
            idleWindowSeconds = prefs.getInt(KEY_IDLE_WINDOW_SECONDS, IDLE_WINDOW_DEFAULT_SECONDS),
        )
        set(value) {
            prefs.edit()
                .putString(KEY_INDICATOR_MODE, value.indicatorMode.name)
                .putString(KEY_BRAIN_PIN, value.brainPin)
                .putBoolean(KEY_CRASH_REPORTING, value.crashReportingEnabled)
                .putInt(KEY_IDLE_WINDOW_SECONDS, value.idleWindowSeconds)
                .apply()
            if (value.spectatingEnabled != brain.screenTextEgress) {
                brain = brain.copy(screenTextEgress = value.spectatingEnabled)
            }
        }

    private companion object {
        private const val PREFERENCES = "lumen_settings"
        private const val KEY_BASE_URL = "cloud_brain_url"
        private const val KEY_API_KEY = "cloud_brain_key"
        private const val KEY_MODEL = "cloud_brain_model"
        private const val KEY_SCREEN_TEXT = "screen_text_egress"
        private const val KEY_DISCLOSURE_ACKNOWLEDGED = "disclosure_acknowledged"
        private const val KEY_INDICATOR_MODE = "indicator_mode"
        private const val KEY_BRAIN_PIN = "brain_pin"
        private const val KEY_CRASH_REPORTING = "crash_reporting"
        private const val KEY_IDLE_WINDOW_SECONDS = "idle_window_seconds"
        private const val BRAIN_PIN_AUTO = "AUTO"
    }
}
