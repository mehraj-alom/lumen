package dev.lumen.app.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import dev.lumen.app.AgentConnection
import dev.lumen.platform.accessibility.LumenAccessibilityService

/**
 * The console's single activity.
 *
 * The activity is a host and nothing more. It shows the disclosure until it has
 * been read, the brain setup until an endpoint is configured, then the console —
 * and it owns the connection to the runtime process. No run state lives here:
 * every figure on the console arrives from `:agent` over the IPC in
 * `API_SPEC.md` §2, and every control on it is a request back through the same
 * channel.
 *
 * The accessibility check runs on every resume, because the user answers the
 * disclosure in another app and comes back with the switch either on or off. It
 * decides what the console warns about, not whether the console exists: a
 * permission the user believes they granted must never be a reason the app has
 * no screen they can use.
 */
class MainActivity : ComponentActivity() {

    private var agent: AgentConnection? = null

    /** Whether the accessibility service is switched on. Read during composition. */
    private var grounded by mutableStateOf(false)

    /** Whether the disclosure has been read. Written once, and persisted. */
    private var acknowledged by mutableStateOf(false)

    private val askForNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) {
            // The run does not need the notification; only the user's ability to
            // see it and stop the run from it does. The runtime binds regardless.
            Log.i(TAG, "notifications are off; the runtime notification will not show")
        }
        agent?.connect()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val connection = AgentConnection(application)
        agent = connection
        grounded = isAccessibilityEnabled(this)
        acknowledged = connection.settings.disclosureAcknowledged
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    when {
                        !grounded && !acknowledged -> DisclosureScreen(
                            onEnable = { openAccessibilitySettings() },
                            onContinue = { acknowledgeDisclosure(connection) },
                        )

                        !connection.isConfigured -> BrainSetupScreen(
                            initial = connection.settings.brain,
                            onSave = { connection.saveBrain(it) },
                        )

                        else -> {
                            // The bind belongs to this branch rather than only to
                            // `onResume`: the user arrives here by saving an
                            // endpoint, not by leaving the app and coming back,
                            // and a console that never bound to `:agent` swallows
                            // every goal typed into it.
                            LaunchedEffect(Unit) { connection.connect() }
                            Column(modifier = Modifier.fillMaxSize()) {
                                // The service being off is a fact about the device
                                // the user can still change, and the way to change
                                // it is one tap from the screen they are on. It is
                                // not a reason to hide the console: an app that
                                // answers a granted permission with a page of
                                // instructions is an app that cannot be used.
                                if (!grounded) {
                                    GroundingNotice(onEnable = { openAccessibilitySettings() })
                                }
                                LumenConsole(
                                    state = connection.state,
                                    onStart = { connection.start(it) },
                                    onApprove = { connection.decide(it, approve = true) },
                                    onDeny = { connection.decide(it, approve = false) },
                                    onStop = { connection.abort() },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        grounded = isAccessibilityEnabled(this)
        val connection = agent ?: return
        // The runtime is only started once there is something for it to do: a
        // foreground service whose notification says "agent is running" should
        // not appear while the user is still reading the disclosure or setting
        // up an endpoint.
        if (!grounded || !connection.isConfigured) return
        askForNotificationPermission()
        connection.connect()
    }

    override fun onPause() {
        // The console is a view. Dropping the bind leaves the run going in
        // `:agent`; the platform kills `:ui` before it touches `:agent`.
        agent?.disconnect()
        super.onPause()
    }

    /** Records that the disclosure was read, and moves on to the app. */
    private fun acknowledgeDisclosure(connection: AgentConnection) {
        connection.settings.disclosureAcknowledged = true
        acknowledged = true
    }

    private fun openAccessibilitySettings() {
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } catch (error: Exception) {
            // No settings screen on this device. The user is told in the log
            // and stays on the disclosure rather than seeing a crash.
            Log.w(TAG, "the accessibility settings screen is not available", error)
        }
    }

    private fun askForNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) askForNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    companion object {
        private const val TAG = "LumenMainActivity"

        /**
         * Whether the user has enabled [LumenAccessibilityService].
         *
         * The platform keeps the enabled services in a colon-separated secure
         * setting; there is no API that asks a service whether it is running,
         * so the setting is the only answer available. The entries are parsed as
         * components instead of compared as text: a device may write either the
         * short form or the qualified one, and a string compare would trap the
         * user on the disclosure with the service already switched on.
         */
        private fun isAccessibilityEnabled(context: Context): Boolean {
            val expected = ComponentName(context, LumenAccessibilityService::class.java)
            val enabled = Settings.Secure
                .getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                .orEmpty()
            return enabled.split(':').any { ComponentName.unflattenFromString(it) == expected }
        }
    }
}
