@file:OptIn(ExperimentalSerializationApi::class)

package dev.lumen.core.model

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One node of an observed screen.
 *
 * The node list is flat and indexed, and each node points at its parent by
 * index. A tree would be more natural to read, but a flat list is cheaper to
 * serialise, cheaper to diff between two observations, and cheaper to hold in
 * the 192 MiB tree-cache budget.
 */
@Serializable
public data class NodeRef(
    val id: Int,
    val parentId: Int? = null,
    val viewId: String? = null,
    val text: String? = null,
    val contentDescription: String? = null,
    val className: String,
    val packageName: String? = null,
    val bounds: RelativeRect? = null,
    val clickable: Boolean = false,
    val editable: Boolean = false,
    val scrollable: Boolean = false,
    val enabled: Boolean = true,
    val focused: Boolean = false,
    val checkable: Boolean = false,
    val checked: Boolean = false,
    val isPassword: Boolean = false,
    val depth: Int = 0,
)

/** A window that is drawn over the application. */
@Serializable
public data class OverlayInfo(
    val packageName: String,
    val type: String,
    val title: String? = null,
    val bounds: RelativeRect? = null,
)

/** A reference to a captured frame held in the sandbox. */
@Serializable
public data class ScreenshotRef(
    val path: String,
    val width: Int,
    val height: Int,
    val mimeType: String = "image/jpeg",
    val capturedAtMs: Long = 0L,
)

/** How the screen settled after an action. */
@Serializable
public data class SettleInfo(
    val settled: Boolean,
    val kind: SettleKind,
    val durationMs: Long,
    val nodeCount: Int,
    val quietMs: Long = 0L,
)

/** The kind of change the settle detector was waiting for. */
@Serializable
public enum class SettleKind {
    @SerialName("scroll")
    SCROLL,

    @SerialName("non_scroll")
    NON_SCROLL,

    @SerialName("typing")
    TYPING,
}

/**
 * What the screen looked like at a point in time.
 *
 * An observation always follows the action that produced it, so the pair
 * (action, observation) is the unit of progress in a run.
 */
@Serializable
public data class Observation(
    val app: String,
    val nodes: List<NodeRef>,
    val overlays: List<OverlayInfo> = emptyList(),
    val screenshot: ScreenshotRef? = null,
    val settle: SettleInfo? = null,
    val capturedAtMs: Long = 0L,
    val signature: String? = null,
) {
    init {
        require(app.isNotBlank()) { "an observation must name the foreground app" }
    }

    /** The nodes whose visible text matches [needle], case-insensitively. */
    public fun nodesWithText(needle: String): List<NodeRef> {
        val lowered = needle.lowercase()
        return nodes.filter { node ->
            node.text?.lowercase()?.contains(lowered) == true ||
                node.contentDescription?.lowercase()?.contains(lowered) == true
        }
    }

    /** The first node whose visible text matches [needle], case-insensitively. */
    public fun firstNodeWithText(needle: String): NodeRef? = nodesWithText(needle).firstOrNull()
}
