package dev.lumen.platform.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RuntimeShader
import android.os.Build
import android.util.AttributeSet
import android.util.Log
import android.view.View

/**
 * The indicator, drawn over whatever is on screen.
 *
 * The view is deliberately dumb: it draws a band or a dot, and it knows
 * nothing about runs. What it shows is set from outside, and the state is
 * the only thing that changes.
 *
 * The edge glow is drawn with AGSL where the platform has it, and with the
 * Canvas on the versions that do not. The two are the same picture: a
 * travelling comet along the perimeter. Every literal that shapes that comet
 * is a token in `GlassTokens` rather than a number inside one of the two
 * paths, so neither path can drift on its own.
 */
public class GlassOverlayView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private var state: IndicatorState = IndicatorState.IDLE
    private var mode: IndicatorMode = IndicatorMode.EDGE_GLOW

    private var phase: Float = 0f
    private var lastFrameMs: Long = 0L
    private var running: Boolean = false

    private val density = resources.displayMetrics.density

    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private var shader: RuntimeShader? = null

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            shader = try {
                RuntimeShader(
                    context.assets.open("shaders/edge_glow.frag").bufferedReader().use { it.readText() },
                )
            } catch (error: Exception) {
                // The Canvas path renders the same comet, so a shader that will
                // not compile degrades the indicator rather than losing it.
                Log.w(TAG, "the edge glow shader could not be compiled; drawing it on the canvas", error)
                null
            }
        }
    }

    /** The state the indicator is showing. */
    public fun setIndicatorState(state: IndicatorState) {
        if (this.state == state) return
        this.state = state
        invalidate()
    }

    /** The mode the indicator is in. */
    public fun setIndicatorMode(mode: IndicatorMode) {
        if (this.mode == mode) return
        this.mode = mode
        visibility = if (mode == IndicatorMode.OFF) View.GONE else View.VISIBLE
        invalidate()
    }

    /** The mode the indicator is in. */
    public fun indicatorMode(): IndicatorMode = mode

    /** The state the indicator is showing. */
    public fun indicatorState(): IndicatorState = state

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        start()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stop()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == View.VISIBLE) start() else stop()
    }

    private fun start() {
        if (running) return
        running = true
        lastFrameMs = System.currentTimeMillis()
        postInvalidateOnAnimation()
    }

    private fun stop() {
        running = false
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (mode == IndicatorMode.OFF) return

        val now = System.currentTimeMillis()
        // A frame that arrives late because the UI thread was busy must not
        // throw the comet forward, so the elapsed time is clamped.
        val delta = ((now - lastFrameMs) / MS_PER_SECOND).coerceIn(0f, MAX_FRAME_DELTA_SECONDS)
        lastFrameMs = now

        val speed = GlassTokens.edgeGlowSpeed(state)
        phase = (phase + delta * speed) % 1f

        when (mode) {
            IndicatorMode.EDGE_GLOW -> drawEdgeGlow(canvas)
            IndicatorMode.STATUS_DOT -> drawStatusDot(canvas)
            IndicatorMode.OFF -> Unit
        }

        if (running) postInvalidateOnAnimation()
    }

    /** The travelling band along the edges. */
    private fun drawEdgeGlow(canvas: Canvas) {
        val active = shader
        if (active != null) {
            drawWithShader(canvas, active)
            return
        }
        drawEdgeGlowWithCanvas(canvas)
    }

    /**
     * The shader path.
     *
     * The band is drawn by the shader; the nodes are added on top so the
     * comet head is crisp on every device.
     */
    private fun drawWithShader(canvas: Canvas, active: RuntimeShader) {
        // AGSL uniforms need API 33. The shader is only built on 33+; the
        // check is restated here because it is where the calls live.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val thickness = GlassTokens.EDGE_GLOW_THICKNESS_DP * density
        active.setFloatUniform("uSize", width.toFloat(), height.toFloat())
        active.setFloatUniform("uThickness", thickness)
        active.setFloatUniform("uTailLength", GlassTokens.EDGE_GLOW_TAIL_LENGTH)
        active.setFloatUniform("uNodeCount", GlassTokens.EDGE_GLOW_NODES)
        active.setFloatUniform("uPhase", phase)
        active.setFloatUniform("uSpeed", GlassTokens.edgeGlowSpeed(state))
        active.setColorUniform("uColor", GlassTokens.stateHaloColor(state))
        active.setColorUniform("uCoreColor", GlassTokens.stateColor(state))

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = active }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
    }

    /**
     * The canvas path, for the platforms without AGSL.
     *
     * The comet is drawn as a run of halo nodes along the perimeter, fading
     * away from the head. The trail this path draws is as long as the node
     * count times the node spacing, which is the same picture the shader
     * builds from [GlassTokens.EDGE_GLOW_TAIL_LENGTH]; the two are shaped by
     * the tokens in `STYLE_GUIDE.md` §5.1, not by literals here.
     */
    private fun drawEdgeGlowWithCanvas(canvas: Canvas) {
        val thickness = GlassTokens.EDGE_GLOW_THICKNESS_DP * density
        val nodes = GlassTokens.EDGE_GLOW_NODES.toInt()
        val spacing = GlassTokens.EDGE_GLOW_NODE_SPACING
        val halo = GlassTokens.stateHaloColor(state)
        val core = GlassTokens.stateColor(state)

        for (index in 0 until nodes) {
            val alpha = (1f - index / nodes.toFloat()).coerceAtLeast(0f)
            val position = (phase - index * spacing) % 1f
            val (x, y) = perimeterPoint(position)

            haloPaint.color = argb(
                ((GlassTokens.EDGE_GLOW_HEAD_ALPHA * alpha).toInt()).coerceIn(0, ALPHA_CHANNEL_MAX),
                halo,
            )
            haloPaint.setShadowLayer(
                GlassTokens.EDGE_GLOW_HALO_SHADOW_DP * density * alpha + 2f * density,
                0f,
                0f,
                haloPaint.color,
            )
            canvas.drawCircle(
                x,
                y,
                GlassTokens.EDGE_GLOW_HALO_RADIUS_DP * density *
                    (GlassTokens.EDGE_GLOW_HALO_SCALE_HEAD * alpha + GlassTokens.EDGE_GLOW_HALO_SCALE_TAIL),
                haloPaint,
            )
            haloPaint.clearShadowLayer()

            if (alpha > GlassTokens.EDGE_GLOW_CORE_TRAIL_FRACTION) {
                val coreAlpha = (alpha - GlassTokens.EDGE_GLOW_CORE_TRAIL_FRACTION) /
                    GlassTokens.EDGE_GLOW_CORE_RAMP_SPAN
                corePaint.color = argb(
                    (ALPHA_CHANNEL_MAX * coreAlpha).toInt().coerceIn(0, ALPHA_CHANNEL_MAX),
                    core,
                )
                corePaint.setShadowLayer(
                    GlassTokens.EDGE_GLOW_CORE_SHADOW_DP * density * coreAlpha,
                    0f,
                    0f,
                    corePaint.color,
                )
                canvas.drawCircle(
                    x,
                    y,
                    GlassTokens.EDGE_GLOW_CORE_RADIUS_DP * density *
                        (GlassTokens.EDGE_GLOW_CORE_RADIUS_BASE + coreAlpha),
                    corePaint,
                )
                corePaint.clearShadowLayer()
            }
        }
    }

    /** A point at [position] around the perimeter, clockwise from the top left. */
    private fun perimeterPoint(position: Float): Pair<Float, Float> {
        val w = width.toFloat()
        val h = height.toFloat()
        val total = (w * 2f + h * 2f).coerceAtLeast(1f)
        val distance = position * total

        return when {
            distance < w -> Pair(distance, 0f)
            distance < w + h -> Pair(w, distance - w)
            distance < w * 2f + h -> Pair(w - (distance - w - h), h)
            else -> Pair(0f, h - (distance - w * 2f - h))
        }
    }

    /** The pulsing dot at the top of the screen. */
    private fun drawStatusDot(canvas: Canvas) {
        val radius = GlassTokens.STATUS_DOT_DIAMETER_DP * density / 2f
        val x = width / 2f
        val y = GlassTokens.STATUS_DOT_TOP_INSET_DP * density + radius
        val color = GlassTokens.stateColor(state)
        val halo = GlassTokens.stateHaloColor(state)

        val pulse = 1f + GlassTokens.STATUS_DOT_PULSE_AMPLITUDE * kotlin.math.sin(
            phase * 2f * Math.PI * (MS_PER_SECOND / GlassTokens.STATUS_DOT_PULSE_MS),
        ).toFloat()

        dotPaint.color = argb(GlassTokens.STATUS_DOT_HALO_ALPHA, halo)
        canvas.drawCircle(x, y, GlassTokens.STATUS_DOT_HALO_RADIUS_DP * density, dotPaint)

        dotPaint.color = color.toInt()
        dotPaint.setShadowLayer(
            GlassTokens.STATUS_DOT_SHADOW_DP * density,
            0f,
            0f,
            argb(GlassTokens.STATUS_DOT_SHADOW_ALPHA, color),
        )
        canvas.drawCircle(x, y, radius * pulse, dotPaint)
        dotPaint.clearShadowLayer()
    }

    /**
     * An ARGB colour with [alpha] over the token [color].
     *
     * The tokens are Long, because an ARGB literal does not fit in an Int
     * with its sign bit set, and the android colour accessors take an Int.
     */
    private fun argb(alpha: Int, color: Long): Int {
        val value = color.toInt()
        return Color.argb(alpha, Color.red(value), Color.green(value), Color.blue(value))
    }

    public companion object {
        private const val TAG = "LumenOverlay"

        /** Milliseconds in a second, for converting a frame delta to seconds. */
        private const val MS_PER_SECOND = 1000f

        /** The largest frame delta the comet trusts, so a stalled UI thread does not jump it. */
        private const val MAX_FRAME_DELTA_SECONDS = 0.1f

        /** The ceiling of one ARGB channel. */
        private const val ALPHA_CHANNEL_MAX = 255
    }
}
