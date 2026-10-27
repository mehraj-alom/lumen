package dev.lumen.agent.ipc

import com.google.common.truth.Truth.assertThat
import dev.lumen.core.model.BrainPin
import dev.lumen.platform.idle.IdleGate
import kotlinx.serialization.json.Json
import org.junit.Test

class PayloadsTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `the brain a run carries is the brain the run is given`() {
        val payload = GoalPayload(
            runId = "01JBX000000000000000000001",
            text = "send the note",
            brainPin = "CLOUD",
            brain = BrainConfigPayload(
                baseUrl = " https://example.test/v1 ",
                apiKey = " secret ",
                modelId = " planner-v1 ",
                screenTextEgress = true,
            ),
        )

        val config = Payloads.brainConfig(payload)

        assertThat(config.baseUrl).isEqualTo("https://example.test/v1")
        assertThat(config.apiKey).isEqualTo("secret")
        assertThat(config.modelId).isEqualTo("planner-v1")
        assertThat(config.pin).isEqualTo(BrainPin.CLOUD)
        assertThat(config.screenTextEgress).isTrue()
    }

    @Test
    fun `a pin the wire does not know falls back to automatic`() {
        assertThat(Payloads.brainPin("LOCAL")).isEqualTo(BrainPin.LOCAL)
        assertThat(Payloads.brainPin("local")).isEqualTo(BrainPin.LOCAL)
        assertThat(Payloads.brainPin("EVERYWHERE")).isEqualTo(BrainPin.AUTO)
        assertThat(Payloads.brainPin("")).isEqualTo(BrainPin.AUTO)
    }

    @Test
    fun `a goal from an older console still decodes and sends nothing of the screen`() {
        val payload = json.decodeFromString(GoalPayload.serializer(), """{"runId":"01JBX","text":"do it"}""")

        assertThat(payload.brain.baseUrl).isEmpty()
        assertThat(payload.brain.apiKey).isEmpty()
        assertThat(payload.brain.screenTextEgress).isFalse()
        assertThat(Payloads.brainConfig(payload).isUsable).isFalse()
    }

    @Test
    fun `the limits a run asked for are the limits it is given`() {
        val payload = GoalPayload(
            runId = "01JBX000000000000000000001",
            text = "do it",
            stepsSoftLimit = 20,
            stepsHardLimit = 40,
            wallClockLimitMs = 300_000L,
        )

        val constraints = Payloads.goal(payload).constraints

        assertThat(constraints.stepsSoftLimit).isEqualTo(20)
        assertThat(constraints.stepsHardLimit).isEqualTo(40)
        assertThat(constraints.wallClockLimitMs).isEqualTo(300_000L)
    }

    @Test
    fun `the device settings that leave the console are the ones the runtime reads`() {
        val payload = DeviceSettingsPayload(
            indicatorMode = "STATUS_DOT",
            idleWindowMs = 15_000L,
            crashReporting = true,
        )

        val readBack = json.decodeFromString(
            DeviceSettingsPayload.serializer(),
            json.encodeToString(DeviceSettingsPayload.serializer(), payload),
        )

        assertThat(readBack.indicatorMode).isEqualTo("STATUS_DOT")
        assertThat(readBack.idleWindowMs).isEqualTo(15_000L)
        assertThat(readBack.crashReporting).isTrue()
    }

    @Test
    fun `settings from a console that never chose anything are the glow, the default window, no report`() {
        val payload = json.decodeFromString(DeviceSettingsPayload.serializer(), "{}")

        assertThat(payload.indicatorMode).isEqualTo("EDGE_GLOW")
        assertThat(payload.idleWindowMs).isEqualTo(IdleGate.DEFAULT_IDLE_WINDOW_MS)
        assertThat(payload.crashReporting).isFalse()
    }
}
