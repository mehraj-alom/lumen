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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lumen.agent.ipc.BrainConfigPayload
import dev.lumen.app.design.GlassSurface
import dev.lumen.app.design.GlassSurfaceKind
import dev.lumen.platform.overlay.GlassTokens

/**
 * The brain setup.
 *
 * A run plans with a model, and this build ships with none: no local model is
 * installed, and no endpoint is bundled. So the app asks for the three things
 * that make a planner reachable, and states what each one costs, before it will
 * start anything.
 *
 * The endpoint is any service that answers `POST {base}/chat/completions` in the
 * chat-completions shape — a hosted provider or a model running on the local
 * network. The key is kept in this app's own private storage and is sent to that
 * endpoint and nowhere else.
 */
@Composable
public fun BrainSetupScreen(initial: BrainConfigPayload, onSave: (BrainConfigPayload) -> Unit, modifier: Modifier = Modifier) {
    val form = remember(initial) { BrainForm(initial) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        SetupExplanation()
        EndpointFields(form)
        ScreenTextConsent(form)
        androidx.compose.material3.Button(
            onClick = { onSave(form.payload()) },
            enabled = form.complete,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Save")
        }
    }
}

/**
 * What is typed on this screen.
 *
 * The values are held here, rather than as four states in the composable, so the
 * three groups on the screen can be drawn by three functions and still edit one
 * form. Each field writes through to the same place the Save button reads from.
 */
private class BrainForm(initial: BrainConfigPayload) {
    var baseUrl by mutableStateOf(initial.baseUrl)
    var modelId by mutableStateOf(initial.modelId)
    var apiKey by mutableStateOf(initial.apiKey)
    var screenText by mutableStateOf(initial.screenTextEgress)

    /** Whether there is enough here to plan with. */
    val complete: Boolean
        get() = baseUrl.isNotBlank() && modelId.isNotBlank() && apiKey.isNotBlank()

    /** The configuration the next run starts with. */
    fun payload(): BrainConfigPayload = BrainConfigPayload(baseUrl, apiKey, modelId, screenText)
}

/** Why the app is asking for an endpoint at all. */
@Composable
private fun SetupExplanation() {
    Text(
        text = "One more thing before the agent can think",
        color = Color(GlassTokens.TEXT_PRIMARY),
        fontSize = 22.sp,
        fontWeight = FontWeight.SemiBold,
    )
    Text(
        text = "The planner is a language model. This build carries no model of " +
            "its own, so point it at an endpoint you control before you start a run.",
        color = Color(GlassTokens.TEXT_SECONDARY),
        fontSize = 14.sp,
        lineHeight = 20.sp,
    )
}

/** The endpoint, the model and the key. */
@Composable
private fun EndpointFields(form: BrainForm) {
    GlassSurface(kind = GlassSurfaceKind.CARD, modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Field(
                label = "Endpoint",
                value = form.baseUrl,
                onValueChange = { form.baseUrl = it },
                placeholder = EXAMPLE_ENDPOINT,
                keyboard = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            Field(
                label = "Model id",
                value = form.modelId,
                onValueChange = { form.modelId = it },
                placeholder = EXAMPLE_MODEL,
                keyboard = KeyboardOptions(keyboardType = KeyboardType.Ascii),
            )
            Field(
                label = "Key",
                value = form.apiKey,
                onValueChange = { form.apiKey = it },
                placeholder = "the bearer token this endpoint expects",
                keyboard = KeyboardOptions(keyboardType = KeyboardType.Password),
                secret = true,
            )
        }
    }
}

/** The consent that lets screen text reach the endpoint above (`I11`). */
@Composable
private fun ScreenTextConsent(form: BrainForm) {
    GlassSurface(kind = GlassSurfaceKind.INLINE, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Let the model read the screen",
                    color = Color(GlassTokens.TEXT_PRIMARY),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "The agent sends the text it reads on screen to the endpoint above. " +
                        "With this off nothing about your screen leaves the device, and a run " +
                        "that needs to see the screen stops at the first step instead.",
                    color = Color(GlassTokens.TEXT_SECONDARY),
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                )
            }
            androidx.compose.material3.Switch(checked = form.screenText, onCheckedChange = { form.screenText = it })
        }
    }
}

/** One labelled field. */
@Composable
private fun Field(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    keyboard: KeyboardOptions,
    secret: Boolean = false,
) {
    Column {
        Text(
            text = label.uppercase(),
            color = Color(GlassTokens.TEXT_TERTIARY),
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.height(4.dp))
        androidx.compose.material3.OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = { Text(placeholder, fontSize = 13.sp) },
            keyboardOptions = keyboard,
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** An example of the shape an endpoint has to answer. */
private const val EXAMPLE_ENDPOINT = "https://api.openai.com/v1"

/** An example model id, for the field hint. */
private const val EXAMPLE_MODEL = "gpt-4o-mini"
