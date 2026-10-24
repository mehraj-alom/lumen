package dev.lumen.app.design

import android.graphics.RuntimeShader
import android.os.Build
import android.util.Log
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.lumen.platform.overlay.GlassTokens

private const val TAG = "LumenGlass"

/**
 * A glass surface.
 *
 * A glass surface is a lens, not a sheet. It refracts what is behind it, it
 * has a thickness, and it catches the light at its edge. A surface that
 * only blurs is a mistake, and the shader is what stops it being one.
 *
 * The shader is AGSL, and AGSL is API 33. Below that the surface falls
 * back to a blurred tint with a specular rim, which is honest glass rather
 * than pretend glass.
 */
@Composable
public fun GlassSurface(
    modifier: Modifier = Modifier,
    kind: GlassSurfaceKind = GlassSurfaceKind.CARD,
    refractionStrength: Float = GlassTokens.REFRACTION_STRENGTH,
    content: @Composable BoxScope.() -> Unit,
) {
    val shape = RoundedCornerShape(kind.cornerRadiusDp)

    var entered by remember { mutableStateOf(false) }
    val entry by animateFloatAsState(
        targetValue = if (entered) 1f else 0f,
        animationSpec = spring(
            dampingRatio = GlassTokens.ENTRY_DAMPING,
            stiffness = GlassTokens.ENTRY_STIFFNESS,
        ),
        label = "glass-entry",
    )

    LaunchedEffect(Unit) { entered = true }

    val context = LocalContext.current
    val shader = remember(context) { loadGlassShader(context) }

    Box(
        modifier = modifier
            .shadow(
                elevation = kind.elevationDp,
                shape = shape,
                ambientColor = Color.Black,
                spotColor = Color.Black,
            )
            .clip(shape)
            .background(
                brush = Brush.verticalGradient(
                    listOf(
                        Color(GlassTokens.GLASS_TINT).copy(alpha = kind.tintAlpha),
                        Color(GlassTokens.GLASS_TINT_STRONG).copy(alpha = kind.tintAlpha * 0.5f),
                    ),
                ),
            )
            .then(
                if (shader != null) {
                    Modifier.drawBehind { drawRefraction(shader, kind, refractionStrength, entry) }
                } else {
                    Modifier
                },
            )
            .border(
                width = GlassTokens.RIM_WIDTH_DP.dp,
                color = Color.White.copy(alpha = 0.22f),
                shape = shape,
            ),
        content = content,
    )
}

/** The AGSL shader, or null where the platform does not have it. */
private fun loadGlassShader(context: android.content.Context): RuntimeShader? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
    return try {
        RuntimeShader(
            context.assets
                .open("shaders/glass_refraction.frag")
                .bufferedReader()
                .use { it.readText() },
        )
    } catch (error: Exception) {
        // The tinted, un-refracted surface is the fallback below API 33, and a
        // shader that will not compile lands on the same path: the surface
        // loses its lens, it does not lose its glass.
        Log.w(TAG, "the glass shader could not be compiled; drawing the fallback surface", error)
        null
    }
}

/** Draws the refraction layer. */
private fun DrawScope.drawRefraction(shader: RuntimeShader, kind: GlassSurfaceKind, refractionStrength: Float, entry: Float) {
    // AGSL uniforms are a 33 thing. The shader is only ever built on 33+, but
    // the platform needs to be told, and a device that lost the check gets a
    // surface without refraction rather than a crash.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val density = this.density
    shader.setFloatUniform("uSize", size.width, size.height)
    shader.setFloatUniform("uCornerRadius", kind.cornerRadiusDp.toPx())
    shader.setFloatUniform("uRefraction", refractionStrength * entry)
    shader.setFloatUniform("uIor", GlassTokens.IOR)
    shader.setFloatUniform("uFresnelPower", GlassTokens.FRESNEL_POWER)
    shader.setFloatUniform("uNoiseScale", GlassTokens.NOISE_SCALE)
    shader.setFloatUniform("uNoiseAmplitude", GlassTokens.NOISE_AMPLITUDE_DP * density)
    shader.setFloatUniform("uThickness", GlassTokens.SURFACE_THICKNESS_DP * density)
    shader.setFloatUniform("uRimWidth", GlassTokens.RIM_WIDTH_DP * density)
    // The colour uniforms are half4, and the tokens are ARGB Longs; the
    // platform setter that takes a colour wants an android.graphics.Color,
    // so the components are written as floats.
    val tint = Color(GlassTokens.GLASS_TINT.toInt())
    shader.setFloatUniform("uTint", tint.red, tint.green, tint.blue, tint.alpha)
    shader.setFloatUniform("uRimColor", 1f, 1f, 1f, 1f)

    drawRect(brush = ShaderBrush(shader))
}
