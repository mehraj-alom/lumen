@file:OptIn(ExperimentalSerializationApi::class)

package dev.lumen.core.model

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The direction of a scroll. */
@Serializable
public enum class ScrollDirection {
    @SerialName("up")
    UP,

    @SerialName("down")
    DOWN,

    @SerialName("left")
    LEFT,

    @SerialName("right")
    RIGHT,
}

/** The easing of a synthesised drag. */
@Serializable
public enum class Easing {
    @SerialName("linear")
    LINEAR,

    @SerialName("ease_in_out")
    EASE_IN_OUT,

    @SerialName("ease_out")
    EASE_OUT,

    @SerialName("natural")
    NATURAL,
}

/**
 * The closed set of things the agent can do to a device.
 *
 * Every mutating action is dispatched through exactly one funnel
 * (`ActionExecutor`, `ARCH.md` §4.1), and every mutating action returns the
 * post-action screen state, so a plan never has to ask "and now what does it
 * look like?".
 *
 * The vocabulary is deliberately small. A primitive that cannot be expressed
 * here does not belong in the system.
 */
@Serializable
public sealed interface Action {

    /** Whether this action changes the device state. */
    public val mutating: Boolean

    /** A single tap at a relative position. */
    @Serializable
    @SerialName("tap")
    public data class Tap(val x: RelativePoint, val y: RelativePoint) : Action {
        override val mutating: Boolean get() = true
    }

    /** A double tap at a relative position. */
    @Serializable
    @SerialName("double_tap")
    public data class DoubleTap(val x: RelativePoint, val y: RelativePoint) : Action {
        override val mutating: Boolean get() = true
    }

    /** A long press at a relative position. */
    @Serializable
    @SerialName("long_press")
    public data class LongPress(val x: RelativePoint, val y: RelativePoint, val durationMs: Long = 600L) : Action {
        init {
            require(durationMs > 0) { "a long press must have a positive duration" }
        }

        override val mutating: Boolean get() = true
    }

    /** A swipe between two relative positions. */
    @Serializable
    @SerialName("swipe")
    public data class Swipe(
        val fromX: RelativePoint,
        val fromY: RelativePoint,
        val toX: RelativePoint,
        val toY: RelativePoint,
        val durationMs: Long = 300L,
    ) : Action {
        init {
            require(durationMs > 0) { "a swipe must have a positive duration" }
        }

        override val mutating: Boolean get() = true
    }

    /** A scroll along a direction. */
    @Serializable
    @SerialName("scroll")
    public data class Scroll(val direction: ScrollDirection, val amount: Double = 0.5, val lane: RelativePoint? = null) : Action {
        init {
            require(amount > 0.0) { "a scroll must have a positive amount" }
        }

        override val mutating: Boolean get() = true
    }

    /**
     * A drag along a path.
     *
     * The path is interpolated with an easing function and replayed as a
     * gesture stroke. A drag is never a start-to-end fling: the shape of the
     * path is the point.
     */
    @Serializable
    @SerialName("drag")
    public data class Drag(val path: List<RelativePoint>, val durationMs: Long = 500L, val easing: Easing = Easing.NATURAL) : Action {
        init {
            require(path.size >= 2) { "a drag needs at least two points" }
            require(durationMs > 0) { "a drag must have a positive duration" }
        }

        override val mutating: Boolean get() = true
    }

    /** Types text into the focused field. */
    @Serializable
    @SerialName("input_text")
    public data class InputText(val text: String) : Action {
        init {
            require(text.isNotEmpty()) { "there is nothing to type" }
        }

        override val mutating: Boolean get() = true
    }

    /** Pastes text into the focused field. */
    @Serializable
    @SerialName("paste_text")
    public data class PasteText(val text: String) : Action {
        init {
            require(text.isNotEmpty()) { "there is nothing to paste" }
        }

        override val mutating: Boolean get() = true
    }

    /** Presses the enter key. */
    @Serializable
    @SerialName("press_enter")
    public object PressEnter : Action {
        override val mutating: Boolean get() = true
    }

    /** Clicks the node with a given view id. */
    @Serializable
    @SerialName("click_by_id")
    public data class ClickById(val viewId: String) : Action {
        init {
            require(viewId.isNotBlank()) { "a view id must not be blank" }
        }

        override val mutating: Boolean get() = true
    }

    /** Clicks the first, or the n-th, node whose label matches. */
    @Serializable
    @SerialName("click_text")
    public data class ClickText(val text: String, val occurrence: Int = 0) : Action {
        init {
            require(text.isNotBlank()) { "a click target must not be blank" }
            require(occurrence >= 0) { "an occurrence index cannot be negative" }
        }

        override val mutating: Boolean get() = true
    }

    /** Opens an installed application. */
    @Serializable
    @SerialName("open_app")
    public data class OpenApp(val packageName: String) : Action {
        init {
            require(packageName.isNotBlank()) { "a package name must not be blank" }
        }

        override val mutating: Boolean get() = true
    }

    /** Opens a url. */
    @Serializable
    @SerialName("open_url")
    public data class OpenUrl(val url: String) : Action {
        init {
            require(url.isNotBlank()) { "a url must not be blank" }
        }

        override val mutating: Boolean get() = true
    }

    /** Lists the launchable applications. */
    @Serializable
    @SerialName("list_apps")
    public object ListApps : Action {
        override val mutating: Boolean get() = false
    }

    /** Reads the current screen. */
    @Serializable
    @SerialName("observe_screen")
    public data class ObserveScreen(val includeScreenshot: Boolean = false, val includeOverlays: Boolean = true, val maxNodes: Int = 400) :
        Action {
        init {
            require(maxNodes > 0) { "a node budget must be positive" }
        }

        override val mutating: Boolean get() = false
    }

    /** Captures a region of the screen. */
    @Serializable
    @SerialName("screenshot")
    public data class Screenshot(val region: RelativeRect = RelativeRect.FULL) : Action {
        override val mutating: Boolean get() = false
    }

    /** Reads a file from the sandbox. */
    @Serializable
    @SerialName("read_file")
    public data class ReadFile(val path: String) : Action {
        init {
            require(path.isNotBlank()) { "a path must not be blank" }
        }

        override val mutating: Boolean get() = false
    }
}
