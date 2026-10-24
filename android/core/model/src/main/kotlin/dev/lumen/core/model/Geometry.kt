package dev.lumen.core.model

import kotlinx.serialization.Serializable

/**
 * A point in the `0.0..1.0` coordinate space of the root window.
 *
 * All coordinates crossing the boundary are relative, so a plan survives a
 * display change, a different device, and a rotation (`ARCH.md` §4.1).
 */
@JvmInline
@Serializable
public value class RelativePoint(public val fraction: Double) {
    init {
        require(fraction in 0.0..1.0) { "a relative coordinate must be within 0.0..1.0, got $fraction" }
    }

    override fun toString(): String = fraction.toString()
}

/**
 * A rectangle in the `0.0..1.0` coordinate space of the root window.
 */
@Serializable
public data class RelativeRect(val left: Double, val top: Double, val right: Double, val bottom: Double) {
    init {
        require(left in 0.0..1.0 && right in 0.0..1.0) { "a relative rect must be within 0.0..1.0" }
        require(top in 0.0..1.0 && bottom in 0.0..1.0) { "a relative rect must be within 0.0..1.0" }
        require(right >= left) { "a relative rect must not have a negative width" }
        require(bottom >= top) { "a relative rect must not have a negative height" }
    }

    /** The width in relative units. */
    public val width: Double get() = right - left

    /** The height in relative units. */
    public val height: Double get() = bottom - top

    /** The centre point of the rectangle. */
    public val center: RelativePoint
        get() = RelativePoint((left + right) / 2.0)

    public companion object {
        /** The whole root window. */
        public val FULL: RelativeRect = RelativeRect(0.0, 0.0, 1.0, 1.0)
    }
}
