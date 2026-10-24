package dev.lumen.app.ui

import dev.lumen.platform.overlay.IndicatorMode

/**
 * The settings the user controls.
 *
 * The idle window defaults to the same 60 seconds the gate is built with
 * (`IdleGate.DEFAULT_IDLE_WINDOW_MS`), so the screen and the gate agree
 * before the user touches either.
 */
public data class SettingsState(
    val indicatorMode: IndicatorMode = IndicatorMode.EDGE_GLOW,
    val brainPin: String = "AUTO",
    val spectatingEnabled: Boolean = false,
    val crashReportingEnabled: Boolean = false,
    val idleWindowSeconds: Int = IDLE_WINDOW_DEFAULT_SECONDS,
)

/** The idle window the agent is built with, and the one offered as the default. */
public const val IDLE_WINDOW_DEFAULT_SECONDS: Int = 60

/** The shortest wait offered: the agent takes the live screen soonest after a touch. */
private const val IDLE_WINDOW_MIN_SECONDS: Int = 15

/** The second-shortest wait offered. */
private const val IDLE_WINDOW_SHORT_SECONDS: Int = 30

/** The longest wait offered: twice the default, so the agent stays off the screen longer. */
private const val IDLE_WINDOW_LONG_SECONDS: Int = 120

/** The idle windows the settings screen offers, shortest first. */
internal val IDLE_WINDOW_CHOICES_SECONDS: List<Int> =
    listOf(IDLE_WINDOW_MIN_SECONDS, IDLE_WINDOW_SHORT_SECONDS, IDLE_WINDOW_DEFAULT_SECONDS, IDLE_WINDOW_LONG_SECONDS)
