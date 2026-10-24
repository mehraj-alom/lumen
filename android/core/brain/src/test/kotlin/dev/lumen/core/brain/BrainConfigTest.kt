package dev.lumen.core.brain

import com.google.common.truth.Truth.assertThat
import dev.lumen.core.model.BrainPin
import org.junit.Test

class BrainConfigTest {

    @Test
    fun `a config with nothing in it is not usable`() {
        assertThat(BrainConfig().isUsable).isFalse()
    }

    @Test
    fun `a config with a blank part is not usable`() {
        assertThat(BrainConfig(baseUrl = "https://example.test/v1", apiKey = "  ", modelId = "m").isUsable).isFalse()
        assertThat(BrainConfig(baseUrl = "", apiKey = "k", modelId = "m").isUsable).isFalse()
        assertThat(BrainConfig(baseUrl = "https://example.test/v1", apiKey = "k", modelId = "").isUsable).isFalse()
    }

    @Test
    fun `a config with an endpoint, a key and a model is usable`() {
        assertThat(BrainConfig(baseUrl = "https://example.test/v1", apiKey = "k", modelId = "m").isUsable).isTrue()
    }

    @Test
    fun `screen text does not leave the device unless the user said so`() {
        assertThat(BrainConfig().screenTextEgress).isFalse()
    }

    @Test
    fun `the pin is automatic unless the run says otherwise`() {
        assertThat(BrainConfig().pin).isEqualTo(BrainPin.AUTO)
    }
}
