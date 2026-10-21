package dev.lumen.platform.overlay

/**
 * The design tokens.
 *
 * Every value in `STYLE_GUIDE.md` §2–§5 is a constant here, and the
 * composables and the views read them from this one place. A magic number
 * in a composable is a lint error, not a style preference.
 */
public object GlassTokens {

    // ------------------------------------------------------------- colour

    public const val VOID: Long = 0xFF07090F
    public const val ABYSS: Long = 0xFF0C1018
    public const val SLATE: Long = 0xFF161C28
    public const val GLASS_TINT: Long = 0x0F8FB4FF
    public const val GLASS_TINT_STRONG: Long = 0x1F6E9BFF
    public const val TEXT_PRIMARY: Long = 0xFFF2F5FC
    public const val TEXT_SECONDARY: Long = 0xFFA9B4C8
    public const val TEXT_TERTIARY: Long = 0xFF6B7789
    public const val HAIRLINE: Long = 0x1AFFFFFF

    // ------------------------------------------------------ state colours

    public const val STATE_IDLE: Long = 0xFF7C8AA3
    public const val STATE_IDLE_HALO: Long = 0x667A6580
    public const val STATE_WORKING: Long = 0xFF3FE0D0
    public const val STATE_WORKING_HALO: Long = 0x8C1FB8AC
    public const val STATE_APPROVAL: Long = 0xFFFFB020
    public const val STATE_APPROVAL_HALO: Long = 0x99E08A00
    public const val STATE_BLOCKED: Long = 0xFFFF5A5F
    public const val STATE_BLOCKED_HALO: Long = 0x99D93A3F
    public const val STATE_ERROR: Long = 0xFFFF3B3B
    public const val STATE_ERROR_HALO: Long = 0xA6C41E22
    public const val STATE_ABORT: Long = 0xFFFF8A3D
    public const val STATE_ABORT_HALO: Long = 0x99E06A1E

    // ------------------------------------------------------------ surfaces

    /** The blur radius of a card, in dp. */
    public const val BLUR_CARD_DP: Float = 24f

    /** The blur radius of a panel, in dp. */
    public const val BLUR_PANEL_DP: Float = 32f

    /** The blur radius of a sheet, in dp. */
    public const val BLUR_SHEET_DP: Float = 40f

    /** The blur radius of an overlay, in dp. */
    public const val BLUR_OVERLAY_DP: Float = 28f

    /** The blur radius of an inline surface, in dp. */
    public const val BLUR_INLINE_DP: Float = 12f

    /** The elevation of a card, in dp. */
    public const val ELEVATION_CARD_DP: Float = 8f

    /** The elevation of a panel, in dp. */
    public const val ELEVATION_PANEL_DP: Float = 16f

    /** The elevation of a sheet, in dp. */
    public const val ELEVATION_SHEET_DP: Float = 24f

    /** The elevation of an overlay, in dp. */
    public const val ELEVATION_OVERLAY_DP: Float = 20f

    /** The elevation of an inline surface, in dp. */
    public const val ELEVATION_INLINE_DP: Float = 2f

    // ---------------------------------------------------------- refraction

    /** The master refraction scale. */
    public const val REFRACTION_STRENGTH: Float = 1.0f

    /** The maximum displacement at the rim, in dp. */
    public const val MAX_DISPLACEMENT_DP: Float = 3.5f

    /** The exponent of the edge falloff. */
    public const val DISPLACEMENT_FALLOFF: Float = 2.2f

    /** The chromatic dispersion at the rim, in dp. */
    public const val CHROMATIC_ABERRATION_DP: Float = 0.6f

    /** The index of refraction. */
    public const val IOR: Float = 1.45f

    /** The fresnel exponent. */
    public const val FRESNEL_POWER: Float = 3.2f

    /** The frequency of the warp field. */
    public const val NOISE_SCALE: Float = 2.4f

    /** The amplitude of the warp field, in dp. */
    public const val NOISE_AMPLITUDE_DP: Float = 0.35f

    /** The thickness of the surface, in dp. */
    public const val SURFACE_THICKNESS_DP: Float = 6f

    // ------------------------------------------------------------- the rim

    /** The width of the rim, in dp. */
    public const val RIM_WIDTH_DP: Float = 1f

    /** The speed of the travelling highlight, in cycles per second. */
    public const val RIM_HIGHLIGHT_SPEED: Float = 0.18f

    /** The inner shadow blur, in dp. */
    public const val INNER_SHADOW_BLUR_DP: Float = 12f

    /** The outer shadow blur, in dp. */
    public const val OUTER_SHADOW_BLUR_DP: Float = 24f

    /** The outer shadow offset, in dp. */
    public const val OUTER_SHADOW_OFFSET_DP: Float = 6f

    // ------------------------------------------------------ corner radii

    public const val RADIUS_INLINE_DP: Float = 10f
    public const val RADIUS_CARD_DP: Float = 22f
    public const val RADIUS_PANEL_DP: Float = 28f
    public const val RADIUS_SHEET_DP: Float = 32f
    public const val RADIUS_OVERLAY_DP: Float = 24f

    // ------------------------------------------------------------- motion

    /** The damping ratio of the surface entry spring. */
    public const val ENTRY_DAMPING: Float = 0.78f

    /** The stiffness of the surface entry spring. */
    public const val ENTRY_STIFFNESS: Float = 320f

    /** The damping ratio of the surface exit spring. */
    public const val EXIT_DAMPING: Float = 0.90f

    /** The stiffness of the surface exit spring. */
    public const val EXIT_STIFFNESS: Float = 420f

    /** The duration of a state change, in milliseconds. */
    public const val STATE_CHANGE_MS: Int = 220

    /** The duration of a takeover handover, in milliseconds. */
    public const val TAKEOVER_MS: Int = 160

    // ---------------------------------------------------------- indicator

    /** The thickness of the edge glow, in dp. */
    public const val EDGE_GLOW_THICKNESS_DP: Float = 8f

    /** The edge glow thickness on a compact width, in dp. */
    public const val EDGE_GLOW_THICKNESS_COMPACT_DP: Float = 6f

    /** The outward falloff of the edge glow, in dp. */
    public const val EDGE_GLOW_FALLOFF_DP: Float = 24f

    /** The number of nodes in the travelling trail. */
    public const val EDGE_GLOW_NODES: Float = 34f

    /** The spacing of the nodes, as a fraction of the perimeter. */
    public const val EDGE_GLOW_NODE_SPACING: Float = 0.0052f

    /** The radius of a halo node, in dp. */
    public const val EDGE_GLOW_HALO_RADIUS_DP: Float = 2.4f

    /** The radius of a core node, in dp. */
    public const val EDGE_GLOW_CORE_RADIUS_DP: Float = 1.5f

    /** The shadow blur of a halo node, in dp. */
    public const val EDGE_GLOW_HALO_SHADOW_DP: Float = 11f

    /** The shadow blur of a core node, in dp. */
    public const val EDGE_GLOW_CORE_SHADOW_DP: Float = 5f

    /** The diameter of the status dot, in dp. */
    public const val STATUS_DOT_DIAMETER_DP: Float = 10f

    /** The distance of the status dot below the status bar, in dp. */
    public const val STATUS_DOT_TOP_INSET_DP: Float = 24f

    /** The radius of the status dot's halo, in dp. */
    public const val STATUS_DOT_HALO_RADIUS_DP: Float = 16f

    /** The period of the status dot's pulse, in milliseconds. */
    public const val STATUS_DOT_PULSE_MS: Int = 900

    /** The idle period of the status dot's pulse, in milliseconds. */
    public const val STATUS_DOT_PULSE_IDLE_MS: Int = 1_600

    // --------------------------------------------------------- indicator

    /** The travel speed of the edge glow, in cycles per second, per state. */
    public fun edgeGlowSpeed(state: IndicatorState): Float = when (state) {
        IndicatorState.IDLE -> 0.25f
        IndicatorState.WORKING -> 0.9f
        IndicatorState.AWAITING_APPROVAL -> 2.0f
        IndicatorState.BLOCKED -> 0.4f
        IndicatorState.ERROR -> 3.0f
        IndicatorState.ABORTING -> 3.0f
    }

    /** The core colour of [state]. */
    public fun stateColor(state: IndicatorState): Long = when (state) {
        IndicatorState.IDLE -> STATE_IDLE
        IndicatorState.WORKING -> STATE_WORKING
        IndicatorState.AWAITING_APPROVAL -> STATE_APPROVAL
        IndicatorState.BLOCKED -> STATE_BLOCKED
        IndicatorState.ERROR -> STATE_ERROR
        IndicatorState.ABORTING -> STATE_ABORT
    }

    /** The halo colour of [state]. */
    public fun stateHaloColor(state: IndicatorState): Long = when (state) {
        IndicatorState.IDLE -> STATE_IDLE_HALO
        IndicatorState.WORKING -> STATE_WORKING_HALO
        IndicatorState.AWAITING_APPROVAL -> STATE_APPROVAL_HALO
        IndicatorState.BLOCKED -> STATE_BLOCKED_HALO
        IndicatorState.ERROR -> STATE_ERROR_HALO
        IndicatorState.ABORTING -> STATE_ABORT_HALO
    }
}

/** What the indicator is showing. */
public enum class IndicatorState {
    /** The agent is registered and idle. */
    IDLE,

    /** The agent is executing. */
    WORKING,

    /** A human decision is required. */
    AWAITING_APPROVAL,

    /** The agent is blocked: the idle gate, the allowlist, or the policy. */
    BLOCKED,

    /** The run failed. */
    ERROR,

    /** The run is being aborted. */
    ABORTING,
}

/** How the indicator is shown. */
public enum class IndicatorMode {
    /** A glowing band along the screen edges. */
    EDGE_GLOW,

    /** A small pulsing dot at the top of the screen. */
    STATUS_DOT,

    /** Nothing is drawn. */
    OFF,
}

/** The indicator state named [value], or [IndicatorState.IDLE]. */
public fun String?.toIndicatorState(): IndicatorState = when (this?.uppercase()) {
    "WORKING" -> IndicatorState.WORKING
    "AWAITING_APPROVAL" -> IndicatorState.AWAITING_APPROVAL
    "BLOCKED" -> IndicatorState.BLOCKED
    "ERROR" -> IndicatorState.ERROR
    "ABORTING" -> IndicatorState.ABORTING
    else -> IndicatorState.IDLE
}

/** The indicator mode named [value], or [IndicatorMode.EDGE_GLOW]. */
public fun String?.toIndicatorMode(): IndicatorMode = when (this?.uppercase()) {
    "STATUS_DOT" -> IndicatorMode.STATUS_DOT
    "OFF" -> IndicatorMode.OFF
    else -> IndicatorMode.EDGE_GLOW
}
