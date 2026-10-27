package dev.lumen.core.agent

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The indicator mode is chosen on a settings screen and arrives as a word, so
 * the parse is the boundary where an unrecognised word could otherwise become
 * an indicator that is off.
 */
class IndicatorModeTest {

    @Test
    fun `each name parses to its mode`() {
        assertThat("EDGE_GLOW".toIndicatorMode()).isEqualTo(IndicatorMode.EDGE_GLOW)
        assertThat("STATUS_DOT".toIndicatorMode()).isEqualTo(IndicatorMode.STATUS_DOT)
        assertThat("OFF".toIndicatorMode()).isEqualTo(IndicatorMode.OFF)
    }

    @Test
    fun `a name in any case parses to its mode`() {
        assertThat("off".toIndicatorMode()).isEqualTo(IndicatorMode.OFF)
        assertThat("status_dot".toIndicatorMode()).isEqualTo(IndicatorMode.STATUS_DOT)
    }

    @Test
    fun `a word that names no mode leaves the indicator glowing`() {
        assertThat("GLITTER".toIndicatorMode()).isEqualTo(IndicatorMode.EDGE_GLOW)
        assertThat(null.toIndicatorMode()).isEqualTo(IndicatorMode.EDGE_GLOW)
        assertThat("".toIndicatorMode()).isEqualTo(IndicatorMode.EDGE_GLOW)
    }
}
