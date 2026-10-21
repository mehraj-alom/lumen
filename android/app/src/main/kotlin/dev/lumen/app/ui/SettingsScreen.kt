package dev.lumen.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lumen.app.design.GlassSurface
import dev.lumen.app.design.GlassSurfaceKind
import dev.lumen.platform.overlay.GlassTokens
import dev.lumen.platform.overlay.IndicatorMode

/** The settings the user controls. */
public data class SettingsState(
    val indicatorMode: IndicatorMode = IndicatorMode.EDGE_GLOW,
    val brainPin: String = "AUTO",
    val spectatingEnabled: Boolean = false,
    val crashReportingEnabled: Boolean = false,
    val idleWindowSeconds: Int = 60,
)

/**
 * The settings.
 *
 * Every switch here changes what the agent is allowed to do, so each one
 * states its consequence rather than only naming itself.
 */
@Composable
public fun SettingsScreen(
    state: SettingsState,
    onIndicatorMode: (IndicatorMode) -> Unit,
    onBrainPin: (String) -> Unit,
    onSpectating: (Boolean) -> Unit,
    onCrashReporting: (Boolean) -> Unit,
    onIdleWindow: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            text = "Settings",
            color = Color(GlassTokens.TEXT_PRIMARY),
            fontSize = 24.sp,
            fontWeight = FontWeight.SemiBold,
        )

        GlassSurface(kind = GlassSurfaceKind.CARD, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "THE INDICATOR",
                    color = Color(GlassTokens.TEXT_TERTIARY),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.height(10.dp))

                Choice(
                    label = "Edge glow",
                    detail = "A glowing band along the edges of the screen.",
                    selected = state.indicatorMode == IndicatorMode.EDGE_GLOW,
                ) { onIndicatorMode(IndicatorMode.EDGE_GLOW) }
                Choice(
                    label = "Status dot",
                    detail = "A small pulsing dot at the top of the screen.",
                    selected = state.indicatorMode == IndicatorMode.STATUS_DOT,
                ) { onIndicatorMode(IndicatorMode.STATUS_DOT) }
                Choice(
                    label = "Off",
                    detail = "Nothing is drawn. The notification still shows while the agent runs.",
                    selected = state.indicatorMode == IndicatorMode.OFF,
                ) { onIndicatorMode(IndicatorMode.OFF) }
            }
        }

        GlassSurface(kind = GlassSurfaceKind.CARD, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "THE BRAIN",
                    color = Color(GlassTokens.TEXT_TERTIARY),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.height(10.dp))
                Choice(
                    label = "Automatic",
                    detail = "Use the model on the device when one is installed and fits in " +
                        "memory, otherwise use the cloud.",
                    selected = state.brainPin == "AUTO",
                ) { onBrainPin("AUTO") }
                Choice(
                    label = "On device only",
                    detail = "Never send anything to a cloud model. A run pauses if no local " +
                        "model is available.",
                    selected = state.brainPin == "LOCAL",
                ) { onBrainPin("LOCAL") }
                Choice(
                    label = "Cloud only",
                    detail = "Always use the cloud model. Nothing runs offline.",
                    selected = state.brainPin == "CLOUD",
                ) { onBrainPin("CLOUD") }
            }
        }

        GlassSurface(kind = GlassSurfaceKind.CARD, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "PRIVACY",
                    color = Color(GlassTokens.TEXT_TERTIARY),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.height(10.dp))

                Toggle(
                    label = "Live spectating",
                    detail = "Lets the web console see the screen. While this is off, no " +
                        "screen content leaves the device at all.",
                    checked = state.spectatingEnabled,
                ) { onSpectating(it) }

                Toggle(
                    label = "Send crash reports",
                    detail = "Off by default. A report contains the stack trace and the " +
                        "version, with the screen content and the file paths removed.",
                    checked = state.crashReportingEnabled,
                ) { onCrashReporting(it) }
            }
        }

        GlassSurface(kind = GlassSurfaceKind.INLINE, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "IDLE WINDOW",
                    color = Color(GlassTokens.TEXT_TERTIARY),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "The agent only uses the live screen after the device has been " +
                        "untouched for ${state.idleWindowSeconds} seconds. Lower is safer; " +
                        "higher lets the agent start sooner after you put the phone down.",
                    color = Color(GlassTokens.TEXT_SECONDARY),
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    listOf(15, 30, 60, 120).forEach { seconds ->
                        androidx.compose.material3.OutlinedButton(
                            onClick = { onIdleWindow(seconds) },
                        ) {
                            Text("${seconds}s")
                        }
                    }
                }
            }
        }
    }
}

/** One choice in a list. */
@Composable
private fun Choice(
    label: String,
    detail: String,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        androidx.compose.material3.RadioButton(selected = selected, onClick = onSelect)
        Spacer(Modifier.width(8.dp))
        Column {
            Text(
                text = label,
                color = Color(GlassTokens.TEXT_PRIMARY),
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = detail,
                color = Color(GlassTokens.TEXT_SECONDARY),
                fontSize = 13.sp,
                lineHeight = 18.sp,
            )
        }
    }
}

/** One switch. */
@Composable
private fun Toggle(
    label: String,
    detail: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                color = Color(GlassTokens.TEXT_PRIMARY),
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = detail,
                color = Color(GlassTokens.TEXT_SECONDARY),
                fontSize = 13.sp,
                lineHeight = 18.sp,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
