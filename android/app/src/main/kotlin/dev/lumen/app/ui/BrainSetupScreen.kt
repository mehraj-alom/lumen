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
public fun BrainSetupScreen(
    initial: BrainConfigPayload,
    onSave: (BrainConfigPayload) -> Unit,
    modifier: Modifier = Modifier,
) {
    var baseUrl by remember { mutableStateOf(initial.baseUrl) }
    var apiKey by remember { mutableStateOf(initial.apiKey) }
    var modelId by remember { mutableStateOf(initial.modelId) }
    var screenText by remember { mutableStateOf(initial.screenTextEgress) }

    val complete = baseUrl.isNotBlank() && apiKey.isNotBlank() && modelId.isNotBlank()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
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

        GlassSurface(kind = GlassSurfaceKind.CARD, modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Field(
                    label = "Endpoint",
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    placeholder = EXAMPLE_ENDPOINT,
                    keyboard = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                Field(
                    label = "Model id",
                    value = modelId,
                    onValueChange = { modelId = it },
                    placeholder = EXAMPLE_MODEL,
                    keyboard = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                )
                Field(
                    label = "Key",
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    placeholder = "the bearer token this endpoint expects",
                    keyboard = KeyboardOptions(keyboardType = KeyboardType.Password),
                    secret = true,
                )
            }
        }

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
                androidx.compose.material3.Switch(checked = screenText, onCheckedChange = { screenText = it })
            }
        }

        androidx.compose.material3.Button(
            onClick = { onSave(BrainConfigPayload(baseUrl, apiKey, modelId, screenText)) },
            enabled = complete,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Save")
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
