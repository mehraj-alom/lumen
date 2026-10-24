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
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lumen.app.design.GlassSurface
import dev.lumen.app.design.GlassSurfaceKind
import dev.lumen.platform.overlay.GlassTokens

/**
 * The disclosure, shown before the accessibility service is enabled.
 *
 * Android requires an accessibility service to be used responsibly, and the
 * Play Store review process asks for exactly this: a plain statement of what
 * the service reads and does, shown to the user before the service is
 * enabled, in the app, not in a privacy policy they may never read.
 *
 * The screen is not a legal document. It is a short, honest answer to the
 * question a user actually has: *what is this thing going to do to my
 * phone?*
 */
@Composable
public fun DisclosureScreen(onEnable: () -> Unit, onDecline: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            text = "Before you turn LUMEN on",
            color = Color(GlassTokens.TEXT_PRIMARY),
            fontSize = 24.sp,
            fontWeight = FontWeight.SemiBold,
        )

        GlassSurface(kind = GlassSurfaceKind.CARD, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Heading("It can do this")
                Bullet("Read what is on your screen, so it can find the button it needs.")
                Bullet("Tap, swipe, scroll and type, so it can carry out a task you asked for.")
                Bullet("Take a screenshot, so the live view can show you what it sees.")

                Spacer(Modifier.height(12.dp))
                Heading("It will not do this")
                Bullet("Start on its own. Every run begins with a goal you wrote.")
                Bullet("Act while you are using the phone. It waits until the device is idle.")
                Bullet("Touch anything you have not allowed.")
                Bullet("Spend money, publish, delete or send without asking you first.")
                Bullet("Write or run its own code.")
            }
        }

        GlassSurface(kind = GlassSurfaceKind.INLINE, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Heading("You are always in control")
                Text(
                    text = "The agent shows an indicator whenever it is working. You can take " +
                        "over at any moment by touching the screen, and it pauses within a " +
                        "second. You can turn the service off in Android settings at any " +
                        "time, and it stops immediately.",
                    color = Color(GlassTokens.TEXT_SECONDARY),
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onEnable) {
                Text("Open settings")
            }
            OutlinedButton(onClick = onDecline) {
                Text("Not now")
            }
        }
    }
}

/** A heading inside the disclosure. */
@Composable
private fun Heading(text: String) {
    Text(
        text = text,
        color = Color(GlassTokens.TEXT_PRIMARY),
        fontSize = 16.sp,
        fontWeight = FontWeight.SemiBold,
    )
    Spacer(Modifier.height(6.dp))
}

/** One bullet. */
@Composable
private fun Bullet(text: String) {
    Row(modifier = Modifier.padding(vertical = 3.dp)) {
        Text(text = "—", color = Color(GlassTokens.TEXT_TERTIARY), fontSize = 14.sp)
        Spacer(Modifier.width(10.dp))
        Text(
            text = text,
            color = Color(GlassTokens.TEXT_SECONDARY),
            fontSize = 14.sp,
            lineHeight = 20.sp,
        )
    }
}
