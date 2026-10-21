package dev.lumen.platform.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * The overlay windows.
 *
 * The windows are added by the accessibility service, which is why they are
 * `TYPE_ACCESSIBILITY_OVERLAY` and not `TYPE_APPLICATION_OVERLAY`. That
 * keeps the app from needing `SYSTEM_ALERT_WINDOW`, and it keeps the
 * overlay inside the accessibility service's lifecycle: when the service is
 * revoked, the overlays go with it, and the foreground notification is the
 * only thing left telling the user the agent is running.
 *
 * There are two windows. The **indicator** is not focusable and not
 * touchable, so it cannot steal a tap. The **card** is focusable, because
 * the user has to be able to answer it.
 */
public class OverlayHost(
    private val context: Context,
    private val windowManager: WindowManager,
) {

    private var indicatorView: GlassOverlayView? = null
    private var indicatorAdded: Boolean = false
    private var cardView: View? = null
    private var cardAdded: Boolean = false

    /** Whether the indicator is on screen. */
    public val isIndicatorVisible: Boolean get() = indicatorAdded

    /** Whether a card is on screen. */
    public val isCardVisible: Boolean get() = cardAdded

    /**
     * Shows the indicator.
     *
     * The window is added once and then updated. Adding and removing a
     * window on every state change would flicker, and the indicator is
     * supposed to be calm.
     */
    public fun showIndicator(state: IndicatorState, mode: IndicatorMode) {
        if (mode == IndicatorMode.OFF) {
            hideIndicator()
            return
        }

        val view = indicatorView ?: GlassOverlayView(context).also {
            it.setIndicatorMode(mode)
            indicatorView = it
        }
        view.setIndicatorMode(mode)
        view.setIndicatorState(state)

        if (!indicatorAdded) {
            try {
                windowManager.addView(view, indicatorParams())
                indicatorAdded = true
            } catch (error: Exception) {
                indicatorAdded = false
            }
        }
    }

    /** Removes the indicator. */
    public fun hideIndicator() {
        val view = indicatorView ?: return
        if (!indicatorAdded) return
        try {
            windowManager.removeViewImmediate(view)
        } catch (error: Exception) {
            // The window was already gone.
        }
        indicatorAdded = false
    }

    /** Shows a card, replacing any card that is already up. */
    public fun showCard(view: View) {
        hideCard()
        cardView = view
        try {
            windowManager.addView(view, cardParams())
            cardAdded = true
        } catch (error: Exception) {
            cardAdded = false
            cardView = null
        }
    }

    /** Removes the card. */
    public fun hideCard() {
        val view = cardView ?: return
        if (!cardAdded) return
        try {
            windowManager.removeViewImmediate(view)
        } catch (error: Exception) {
            // The window was already gone.
        }
        cardAdded = false
        cardView = null
    }

    /** Removes everything. */
    public fun hideAll() {
        hideCard()
        hideIndicator()
    }

    private fun indicatorParams(): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }

    private fun cardParams(): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.CENTER }

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
        }
}
