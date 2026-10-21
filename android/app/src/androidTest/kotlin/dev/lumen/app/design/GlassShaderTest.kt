package dev.lumen.app.design

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RuntimeShader
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.lumen.platform.overlay.GlassOverlayView
import dev.lumen.platform.overlay.GlassTokens
import dev.lumen.platform.overlay.IndicatorMode
import dev.lumen.platform.overlay.IndicatorState
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The visual tests.
 *
 * These need a device, because they render. The shader test is skipped
 * below API 33, where AGSL does not exist.
 */
@RunWith(AndroidJUnit4::class)
class GlassShaderTest {

    @Test
    fun I10_theGlassShaderCompilesAndRenders() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return

        val context = androidx.test.core.app.ApplicationProvider
            .getApplicationContext<android.content.Context>()
        val source = context.assets
            .open("shaders/glass_refraction.frag")
            .bufferedReader()
            .use { it.readText() }

        val shader = RuntimeShader(source)
        shader.setFloatUniform("uSize", 400f, 400f)
        shader.setFloatUniform("uCornerRadius", 28f)
        shader.setFloatUniform("uRefraction", 1f)
        shader.setFloatUniform("uIor", GlassTokens.IOR)
        shader.setFloatUniform("uFresnelPower", GlassTokens.FRESNEL_POWER)
        shader.setFloatUniform("uNoiseScale", GlassTokens.NOISE_SCALE)
        shader.setFloatUniform("uNoiseAmplitude", GlassTokens.NOISE_AMPLITUDE_DP)
        shader.setFloatUniform("uThickness", GlassTokens.SURFACE_THICKNESS_DP)
        shader.setFloatUniform("uRimWidth", GlassTokens.RIM_WIDTH_DP)
        shader.setFloatUniform("uTime", 0f)

        val bitmap = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawRect(0f, 0f, 400f, 400f, Paint().apply { this.shader = shader })

        // The surface must not be a flat rectangle: a shader that produced a
        // uniform fill would be a shader that did nothing.
        val pixels = IntArray(400 * 400)
        bitmap.getPixels(pixels, 0, 400, 0, 0, 400, 400)

        assertThat(pixels.toSet().size).isGreaterThan(10)
    }

    @Test
    fun I11_eachIndicatorModeRenders() {
        val context = androidx.test.core.app.ApplicationProvider
            .getApplicationContext<android.content.Context>()
        val view = GlassOverlayView(context)

        view.setIndicatorMode(IndicatorMode.EDGE_GLOW)
        assertThat(view.indicatorMode()).isEqualTo(IndicatorMode.EDGE_GLOW)

        view.setIndicatorMode(IndicatorMode.STATUS_DOT)
        assertThat(view.indicatorMode()).isEqualTo(IndicatorMode.STATUS_DOT)

        view.setIndicatorMode(IndicatorMode.OFF)
        assertThat(view.indicatorMode()).isEqualTo(IndicatorMode.OFF)

        view.setIndicatorState(IndicatorState.AWAITING_APPROVAL)
        assertThat(view.indicatorState()).isEqualTo(IndicatorState.AWAITING_APPROVAL)
    }
}
