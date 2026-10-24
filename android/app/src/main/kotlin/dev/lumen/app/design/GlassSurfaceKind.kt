package dev.lumen.app.design

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.lumen.platform.overlay.GlassTokens

/**
 * The kind of glass a surface is made of.
 *
 * The blur, elevation, radius and tint of each kind are the tokens in
 * `STYLE_GUIDE.md` §2 and §3; this enum only groups them, so no kind carries
 * a number of its own.
 */
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
