package dev.lumen.app.design

import android.graphics.RuntimeShader
import android.os.Build
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
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.lumen.platform.overlay.GlassTokens

/** The kind of glass a surface is made of. */
public enum class GlassSurfaceKind(
    public val blurRadiusDp: Dp,
    public val elevationDp: Dp,
    public val cornerRadiusDp: Dp,
    public val tintAlpha: Float,
) {
    CARD(
        GlassTokens.BLUR_CARD_DP.dp,
        GlassTokens.ELEVATION_CARD_DP.dp,
        GlassTokens.RADIUS_CARD_DP.dp,
        0.06f,
    ),
    PANEL(
        GlassTokens.BLUR_PANEL_DP.dp,
        GlassTokens.ELEVATION_PANEL_DP.dp,
        GlassTokens.RADIUS_PANEL_DP.dp,
        0.08f,
    ),
    SHEET(
        GlassTokens.BLUR_SHEET_DP.dp,
        GlassTokens.ELEVATION_SHEET_DP.dp,
        GlassTokens.RADIUS_SHEET_DP.dp,
        0.12f,
    ),
    INLINE(
        GlassTokens.BLUR_INLINE_DP.dp,
        GlassTokens.ELEVATION_INLINE_DP.dp,
        GlassTokens.RADIUS_INLINE_DP.dp,
        0.04f,
    ),
}

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

    var entered by remember { mutableFloatStateOf(0f) }
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
        null
    }
}

/** Draws the refraction layer. */
private fun DrawScope.drawRefraction(
    shader: RuntimeShader,
    kind: GlassSurfaceKind,
    refractionStrength: Float,
    entry: Float,
) {
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
    shader.setColorUniform("uTint", Color(GlassTokens.GLASS_TINT))
    shader.setColorUniform("uRimColor", Color.White)

    drawRect(brush = ShaderBrush(shader))
}
