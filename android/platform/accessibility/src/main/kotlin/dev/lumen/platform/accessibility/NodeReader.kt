package dev.lumen.platform.accessibility

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import dev.lumen.core.grounding.TreeSnapshot
import dev.lumen.core.model.NodeRef
import dev.lumen.core.model.OverlayInfo
import dev.lumen.core.model.RelativeRect

/**
 * Reads the real accessibility tree into the model the agent reasons about.
 *
 * The tree is flattened. A flat list is cheaper to serialise, cheaper to
 * diff between two observations, and cheaper to hold inside the 192 MiB
 * tree-cache budget than a nested structure would be.
 */
public class NodeReader {

    /**
     * The tree under [root], in the model.
     *
     * @param maxNodes the ceiling on the flat tree, clamped to
     *   [DEFAULT_MAX_NODES]: the node index has a fixed share of the RAM
     *   budget, so a caller may ask for a smaller tree but never a bigger one.
     * @param screenWidth the width of the display the bounds are relative to
     * @param screenHeight the height of the display
     */
    public fun read(
        root: AccessibilityNodeInfo?,
        packageName: String,
        screenWidth: Int,
        screenHeight: Int,
        maxNodes: Int = DEFAULT_MAX_NODES,
    ): TreeSnapshot {
        if (root == null) {
            return TreeSnapshot(app = packageName, nodes = emptyList())
        }

        val ceiling = maxNodes.coerceIn(MIN_NODES, DEFAULT_MAX_NODES)
        val frame = TreeFrame(packageName, screenWidth, screenHeight)
        val nodes = mutableListOf<NodeRef>()
        val visited = mutableSetOf<Int>()

        fun walk(node: AccessibilityNodeInfo, parentId: Int?, depth: Int) {
            if (nodes.size >= ceiling) return
            val id = node.hashCode()
            if (!visited.add(id)) return

            val index = nodes.size
            nodes += node.toRef(index, parentId, depth, frame)

            for (childIndex in 0 until node.childCount) {
                val child = node.getChild(childIndex) ?: continue
                walk(child, index, depth + 1)
            }
        }

        walk(root, null, 0)
        return TreeSnapshot(app = packageName, nodes = nodes)
    }

    /** The windows drawn over the application. */
    public fun overlays(windows: List<AccessibilityWindowInfoCompat>, screenWidth: Int, screenHeight: Int): List<OverlayInfo> =
        windows.map { window ->
            OverlayInfo(
                packageName = window.packageName,
                type = window.type,
                title = window.title,
                bounds = window.bounds?.toRelative(screenWidth, screenHeight),
            )
        }

    private fun AccessibilityNodeInfo.toRef(index: Int, parentId: Int?, depth: Int, frame: TreeFrame): NodeRef = NodeRef(
        id = index,
        parentId = parentId,
        viewId = viewIdResourceName,
        text = text?.toString()?.takeIf { it.isNotBlank() },
        contentDescription = contentDescription?.toString()?.takeIf { it.isNotBlank() },
        className = className?.toString() ?: "android.view.View",
        packageName = frame.packageName,
        bounds = screenBounds().toRelative(frame.screenWidth, frame.screenHeight),
        clickable = isClickable,
        editable = isEditable,
        scrollable = isScrollable,
        enabled = isEnabled,
        focused = isFocused,
        checkable = isCheckable,
        checked = isChecked,
        isPassword = isPassword,
        depth = depth,
    )

    /** The node's bounds on the screen. */
    private fun AccessibilityNodeInfo.screenBounds(): Rect? {
        val rect = Rect()
        getBoundsInScreen(rect)
        return if (rect.isEmpty) null else rect
    }

    private fun Rect?.toRelative(screenWidth: Int, screenHeight: Int): RelativeRect? {
        if (this == null) return null
        if (screenWidth <= 0 || screenHeight <= 0) return null
        val left = left.toDouble() / screenWidth
        val top = top.toDouble() / screenHeight
        val right = right.toDouble() / screenWidth
        val bottom = bottom.toDouble() / screenHeight
        return RelativeRect(
            left.coerceIn(0.0, 1.0),
            top.coerceIn(0.0, 1.0),
            right.coerceIn(0.0, 1.0),
            bottom.coerceIn(0.0, 1.0),
        )
    }

    public companion object {
        /** The default ceiling on the number of nodes in a tree. */
        public const val DEFAULT_MAX_NODES: Int = 400

        /** A tree with no nodes in it is a read that failed, not an empty screen. */
        private const val MIN_NODES: Int = 1
    }
}

/**
 * The window a tree read happens in: who is in front, and how big the display
 * the bounds are relative to is.
 *
 * It travels as one value so a read cannot mix a package from one window with
 * the metrics of another.
 */
private data class TreeFrame(val packageName: String, val screenWidth: Int, val screenHeight: Int)

/** The parts of an accessibility window the reader needs. */
public data class AccessibilityWindowInfoCompat(val packageName: String, val type: String, val title: String?, val bounds: Rect?)

/**
 * The service the reader belongs to.
 *
 * The reader is a plain class, and the service is passed to it, so the
 * reader itself is testable against a fake.
 */
public interface AccessibilityServiceHost {
    /** The root of the current window tree. */
    public fun rootNode(): AccessibilityNodeInfo?

    /** The windows drawn over the application. */
    public fun overlayWindows(): List<AccessibilityWindowInfoCompat>

    /** The size of the display, in pixels. */
    public fun screenSize(): Pair<Int, Int>

    /** The package currently in front. */
    public fun foregroundPackage(): String?

    /** Whether the service is enabled. */
    public fun isServiceEnabled(): Boolean
}

/** The default host, backed by a real service. */
public class ServiceAccessibilityHost(private val service: AccessibilityService) : AccessibilityServiceHost {

    override fun rootNode(): AccessibilityNodeInfo? = service.rootInActiveWindow

    override fun overlayWindows(): List<AccessibilityWindowInfoCompat> {
        // A window does not carry its package name, so the foreground
        // package stands in for it. The windows that matter are the ones
        // drawn OVER that package, and the title is what identifies them.
        val packageName = foregroundPackage().orEmpty()
        return service.windows.map { window ->
            val rect = android.graphics.Rect()
            window.getBoundsInScreen(rect)
            AccessibilityWindowInfoCompat(
                packageName = packageName,
                type = window.type.toString(),
                title = window.title?.toString(),
                bounds = if (rect.isEmpty) null else rect,
            )
        }
    }

    override fun screenSize(): Pair<Int, Int> {
        val metrics = service.resources.displayMetrics
        return metrics.widthPixels to metrics.heightPixels
    }

    override fun foregroundPackage(): String? = service.rootInActiveWindow?.packageName?.toString()

    override fun isServiceEnabled(): Boolean = true
}
