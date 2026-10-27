package dev.lumen.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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

/**
 * The settings.
 *
 * Every switch here changes what the agent is allowed to do, so each one
 * states its consequence rather than only naming itself. Each section is its
 * own composable, so changing one decision does not touch the markup of
 * another.
 *
 * Each callback writes through the connection, which saves the value, sends it
 * to the runtime and re-sends it on the next bind — so the screen and the
 * device are never allowed to drift apart silently, and no control on this page
 * is a preference the runtime never hears about. The pin is the one exception:
 * it belongs to a run, so it travels with the next goal rather than crossing the
 * wire now, and a run keeps the pin it was planned with.
 */
@Composable
public fun SettingsScreen(
    state: SettingsState,
    onIndicatorMode: (IndicatorMode) -> Unit,
    onBrainPin: (String) -> Unit,
    onScreenTextEgress: (Boolean) -> Unit,
    onCrashReporting: (Boolean) -> Unit,
    onIdleWindow: (Int) -> Unit,
    onDone: () -> Unit,
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

        IndicatorSection(state.indicatorMode, onIndicatorMode)
        BrainSection(state.brainPin, onBrainPin)
        PrivacySection(
            screenTextEgress = state.spectatingEnabled,
            crashReportingEnabled = state.crashReportingEnabled,
            onScreenTextEgress = onScreenTextEgress,
            onCrashReporting = onCrashReporting,
        )
        IdleWindowSection(state.idleWindowSeconds, onIdleWindow)

        androidx.compose.material3.Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
            Text("Back to the console")
        }
    }
}

/** How the agent shows itself on the screen. */
@Composable
private fun IndicatorSection(selected: IndicatorMode, onIndicatorMode: (IndicatorMode) -> Unit) {
    GlassSurface(kind = GlassSurfaceKind.CARD, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            SectionHeader("THE INDICATOR")
            Spacer(Modifier.height(10.dp))
            Choice(
                label = "Edge glow",
                detail = "A glowing band along the edges of the screen.",
                selected = selected == IndicatorMode.EDGE_GLOW,
            ) { onIndicatorMode(IndicatorMode.EDGE_GLOW) }
            Choice(
                label = "Status dot",
                detail = "A small pulsing dot at the top of the screen.",
                selected = selected == IndicatorMode.STATUS_DOT,
            ) { onIndicatorMode(IndicatorMode.STATUS_DOT) }
            Choice(
                label = "Off",
                detail = "Nothing is drawn. The notification still shows while the agent runs.",
                selected = selected == IndicatorMode.OFF,
            ) { onIndicatorMode(IndicatorMode.OFF) }
        }
    }
}

/** Where the reasoning runs. */
@Composable
private fun BrainSection(pin: String, onBrainPin: (String) -> Unit) {
    GlassSurface(kind = GlassSurfaceKind.CARD, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            SectionHeader("THE BRAIN")
            Spacer(Modifier.height(10.dp))
            Choice(
                label = "Automatic",
                detail = "Use the model on the device when one is installed and fits in " +
                    "memory, otherwise use the cloud.",
                selected = pin == "AUTO",
            ) { onBrainPin("AUTO") }
            Choice(
                label = "On device only",
                detail = "Never send anything to a cloud model. A run pauses if no local " +
                    "model is available.",
                selected = pin == "LOCAL",
            ) { onBrainPin("LOCAL") }
            Choice(
                label = "Cloud only",
                detail = "Always use the cloud model. Nothing runs offline.",
                selected = pin == "CLOUD",
            ) { onBrainPin("CLOUD") }
        }
    }
}

/** What leaves the device. */
@Composable
private fun PrivacySection(
    screenTextEgress: Boolean,
    crashReportingEnabled: Boolean,
    onScreenTextEgress: (Boolean) -> Unit,
    onCrashReporting: (Boolean) -> Unit,
) {
    GlassSurface(kind = GlassSurfaceKind.CARD, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            SectionHeader("PRIVACY")
            Spacer(Modifier.height(10.dp))
            Toggle(
                label = "Send the text on screen to the brain",
                detail = "Off by default. The plan is built from what is on the screen, " +
                    "and this is what lets that text go to the endpoint you configured. " +
                    "Screenshots never leave the device.",
                checked = screenTextEgress,
            ) { onScreenTextEgress(it) }
            Toggle(
                label = "Write down a crash",
                detail = "Off by default. When it is on, a crash of the runtime is written " +
                    "into the sandbox as a report you can read or delete. It holds the " +
                    "version, the stack trace and nothing the screen showed.",
                checked = crashReportingEnabled,
            ) { onCrashReporting(it) }
        }
    }
}

/** How long the device must be untouched before the agent uses the live screen. */
@Composable
private fun IdleWindowSection(idleWindowSeconds: Int, onIdleWindow: (Int) -> Unit) {
    GlassSurface(kind = GlassSurfaceKind.INLINE, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            SectionHeader("IDLE WINDOW")
            Spacer(Modifier.height(8.dp))
            Text(
                text = "The agent only uses the live screen after the device has been " +
                    "untouched for $idleWindowSeconds seconds. Higher keeps the agent off a " +
                    "screen you might pick up again; lower lets it start sooner, and it " +
                    "never acts while your own touch is going on.",
                color = Color(GlassTokens.TEXT_SECONDARY),
                fontSize = 14.sp,
                lineHeight = 20.sp,
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                IDLE_WINDOW_CHOICES_SECONDS.forEach { seconds ->
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

/** The caption that opens a section. */
@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        color = Color(GlassTokens.TEXT_TERTIARY),
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
    )
}

/** One choice in a list. */
@Composable
private fun Choice(label: String, detail: String, selected: Boolean, onSelect: () -> Unit) {
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
private fun Toggle(label: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
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
