package dev.lumen.core.grounding.fixtures

import dev.lumen.core.grounding.TreeSnapshot
import dev.lumen.core.model.NodeRef
import dev.lumen.core.model.OverlayInfo
import dev.lumen.core.model.RelativeRect

/**
 * Synthetic trees that reproduce the shapes that break real agents.
 *
 * Every scenario here was observed in a real accessibility tree. An agent
 * that passes against tidy synthetic trees and fails against these is an
 * agent that only works in the test.
 */
public class SyntheticTreeBuilder(private val app: String = "com.example.app") {

    private val nodes = mutableListOf<NodeRef>()

    private fun add(node: NodeRef): NodeRef {
        nodes += node
        return node
    }

    public fun root(className: String = "android.widget.FrameLayout"): NodeRef =
        add(NodeRef(id = nodes.size, className = className, packageName = app, depth = 0))

    public fun child(
        parent: NodeRef,
        className: String,
        text: String? = null,
        contentDescription: String? = null,
        viewId: String? = null,
        bounds: RelativeRect? = null,
        clickable: Boolean = false,
        editable: Boolean = false,
        scrollable: Boolean = false,
        focused: Boolean = false,
    ): NodeRef = add(
        NodeRef(
            id = nodes.size,
            parentId = parent.id,
            className = className,
            text = text,
            contentDescription = contentDescription,
            viewId = viewId,
            packageName = app,
            bounds = bounds,
            clickable = clickable,
            editable = editable,
            scrollable = scrollable,
            focused = focused,
            depth = parent.depth + 1,
        ),
    )

    public fun build(overlays: List<OverlayInfo> = emptyList()): TreeSnapshot = TreeSnapshot(
        app = app,
        nodes = nodes.toList(),
        overlays = overlays,
    )

    companion object {

        /**
         * A label on a node that is not itself clickable, with a clickable
         * parent. "Click the thing that says Pay" has to find the parent.
         */
        public fun labelOnNonClickableChild(): TreeSnapshot {
            val builder = SyntheticTreeBuilder()
            val root = builder.root()
            val card = builder.child(
                parent = root,
                className = "android.widget.LinearLayout",
                bounds = RelativeRect(0.0, 0.2, 1.0, 0.4),
                clickable = true,
            )
            builder.child(
                parent = card,
                className = "android.widget.TextView",
                text = "Pay 382 EUR",
                bounds = RelativeRect(0.1, 0.25, 0.5, 0.35),
            )
            return builder.build()
        }

        /**
         * A clickable child whose parent holds the text. The other half of
         * the same problem.
         */
        public fun clickableChildWithParentLabel(): TreeSnapshot {
            val builder = SyntheticTreeBuilder()
            val root = builder.root()
            val row = builder.child(
                parent = root,
                className = "android.widget.LinearLayout",
                text = "Continue",
                bounds = RelativeRect(0.0, 0.5, 1.0, 0.6),
            )
            builder.child(
                parent = row,
                className = "android.widget.ImageView",
                contentDescription = "Continue",
                bounds = RelativeRect(0.8, 0.52, 0.9, 0.58),
                clickable = true,
            )
            return builder.build()
        }

        /** Two nodes with the same text and the same bounds. */
        public fun duplicateLabels(): TreeSnapshot {
            val builder = SyntheticTreeBuilder()
            val root = builder.root()
            val bounds = RelativeRect(0.1, 0.1, 0.4, 0.2)
            builder.child(parent = root, className = "android.widget.Button", text = "Next", bounds = bounds)
            builder.child(parent = root, className = "android.widget.Button", text = "Next", bounds = bounds)
            return builder.build()
        }

        /** A recycled list row whose holder still carries the old bounds. */
        public fun staleRecyclerItem(): TreeSnapshot {
            val builder = SyntheticTreeBuilder()
            val root = builder.root()
            val list = builder.child(
                parent = root,
                className = "androidx.recyclerview.widget.RecyclerView",
                scrollable = true,
            )
            builder.child(
                parent = list,
                className = "android.widget.TextView",
                text = "Stale row",
                bounds = RelativeRect(0.0, 0.0, 0.0, 0.0),
            )
            return builder.build()
        }

        /** A canvas-rendered screen: no nodes at all. */
        public fun canvasRegion(): TreeSnapshot = SyntheticTreeBuilder().build()

        /** A node whose content description carries the text. */
        public fun contentDescriptionOnly(): TreeSnapshot {
            val builder = SyntheticTreeBuilder()
            val root = builder.root()
            builder.child(
                parent = root,
                className = "android.widget.ImageButton",
                contentDescription = "Search flights",
                clickable = true,
            )
            return builder.build()
        }

        /** A screen whose content is web content. */
        public fun webContent(): TreeSnapshot {
            val builder = SyntheticTreeBuilder(app = "com.example.browser")
            val root = builder.root()
            val web = builder.child(
                parent = root,
                className = "android.webkit.WebView",
                viewId = "com.example.browser:id/web",
            )
            builder.child(
                parent = web,
                className = "android.webkit.WebView",
                text = "Best price 351 EUR",
            )
            return builder.build()
        }

        /** A screen with a system overlay from another application. */
        public fun withForeignOverlay(): TreeSnapshot {
            val builder = SyntheticTreeBuilder()
            val root = builder.root()
            builder.child(parent = root, className = "android.widget.TextView", text = "Behind")
            return builder.build(
                overlays = listOf(
                    OverlayInfo(
                        packageName = "com.android.systemui",
                        type = "TYPE_APPLICATION_OVERLAY",
                        title = "Notification",
                    ),
                ),
            )
        }

        /** A deep tree, for the summariser's budget test. */
        public fun deepTree(depth: Int = 12, breadth: Int = 4): TreeSnapshot {
            val builder = SyntheticTreeBuilder()
            var parent = builder.root()
            repeat(depth) { level ->
                repeat(breadth) { column ->
                    parent = builder.child(
                        parent = parent,
                        className = "android.widget.TextView",
                        text = "level $level column $column",
                    )
                }
            }
            return builder.build()
        }

        /** A tree with a text field, a button, and a scrollable list. */
        public fun typicalScreen(): TreeSnapshot {
            val builder = SyntheticTreeBuilder()
            val root = builder.root()
            builder.child(
                parent = root,
                className = "android.widget.EditText",
                viewId = "com.example.app:id/search",
                editable = true,
                focused = true,
                bounds = RelativeRect(0.05, 0.1, 0.7, 0.16),
            )
            builder.child(
                parent = root,
                className = "android.widget.Button",
                text = "Search",
                viewId = "com.example.app:id/search_button",
                clickable = true,
                bounds = RelativeRect(0.75, 0.1, 0.95, 0.16),
            )
            builder.child(
                parent = root,
                className = "androidx.recyclerview.widget.RecyclerView",
                scrollable = true,
                bounds = RelativeRect(0.0, 0.2, 1.0, 0.9),
            )
            return builder.build()
        }
    }
}
