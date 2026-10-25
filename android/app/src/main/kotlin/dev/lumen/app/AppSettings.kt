package dev.lumen.app

import android.content.Context
import android.content.SharedPreferences
import dev.lumen.agent.ipc.BrainConfigPayload

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

    private companion object {
        private const val PREFERENCES = "lumen_settings"
        private const val KEY_BASE_URL = "cloud_brain_url"
        private const val KEY_API_KEY = "cloud_brain_key"
        private const val KEY_MODEL = "cloud_brain_model"
        private const val KEY_SCREEN_TEXT = "screen_text_egress"
        private const val KEY_DISCLOSURE_ACKNOWLEDGED = "disclosure_acknowledged"
    }
}
