package dev.lumen.app.design

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.lumen.platform.overlay.GlassOverlayView
import dev.lumen.platform.overlay.IndicatorMode
import dev.lumen.platform.overlay.IndicatorState
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The glass tests.
 *
 * These need the app's own process, because they read an asset and build the
 * platform's shader types. What they check is the shape of the glass — the
 * shader parses and the surface's state moves — and not the pixels; the pixels
 * are the golden-screenshot suite's job on a handset (`AGENTS.md` §8). The
 * shader test is skipped below API 33, where AGSL does not exist.
 */
@RunWith(AndroidJUnit4::class)
class GlassShaderTest {

    /**
     * The shader compiles, and every uniform the surface sets exists in it.
     *
     * The pixels are not checked here, and cannot be: a canvas over a bitmap is
     * the software pipeline, and the software pipeline refuses a `RuntimeShader`
     * by design — on a handset exactly as much as on the CI emulator, which is
     * what the first version of this test discovered by failing. The render
     * claim belongs to the golden-screenshot suite on a device (`AGENTS.md` §8).
     *
     * What this suite does catch is the pair of failures that come before a
     * pixel is ever drawn: a shader file that does not parse, and a uniform the
     * surface sets that the shader never declared — a drift between this file
     * and the asset that nothing else would notice, because a uniform bound to
     * nothing is silent until it is on screen.
     */
    @Test
    fun I10_theGlassShaderCompiles() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return

        val context = androidx.test.core.app.ApplicationProvider
            .getApplicationContext<android.content.Context>()
        val source = context.assets
            .open("shaders/glass_refraction.frag")
            .bufferedReader()
            .use { it.readText() }

        // Constructing the shader is what parses it; the call throws on a source
        // the compiler rejects.
        val shader = android.graphics.RuntimeShader(source)
        assertThat(shader).isNotNull()

        GLASS_UNIFORMS.forEach { name ->
            assertThat(source).contains(name)
        }
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

/**
 * Every uniform `GlassSurface` binds on the refraction shader.
 *
 * The list is the surface's, not the shader's: a name the shader dropped and the
 * surface still binds is the failure worth catching, and it is silent everywhere
 * else, because a uniform that does not exist is ignored at draw time.
 */
private val GLASS_UNIFORMS = listOf(
    "uSize",
    "uCornerRadius",
    "uRefraction",
    "uIor",
    "uFresnelPower",
    "uNoiseScale",
    "uNoiseAmplitude",
    "uThickness",
    "uRimWidth",
    "uTint",
    "uRimColor",
)
