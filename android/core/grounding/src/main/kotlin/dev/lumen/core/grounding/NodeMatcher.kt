package dev.lumen.core.grounding

import dev.lumen.core.model.NodeRef
import dev.lumen.core.model.RelativePoint

/**
 * The matchers the actor and the planner use to find a target.
 *
 * Every matcher is deterministic and returns *every* candidate rather than
 * the first one, so the caller decides what to do with a duplicate rather
 * than being handed an arbitrary one.
 */
public object NodeMatcher {

    /**
     * The nodes whose visible text or content description contains [text].
     *
     * The match is case-insensitive, because the label on a button and the
     * label the planner wrote rarely agree on case.
     */
    public fun byText(index: NodeIndex, text: String): List<NodeRef> {
        if (text.isBlank()) return emptyList()
        val needle = text.lowercase()
        return index.source.nodes.filter { node ->
            node.text?.lowercase()?.contains(needle) == true ||
                node.contentDescription?.lowercase()?.contains(needle) == true
        }
    }

    /**
     * The n-th node whose label contains [text], counting from zero.
     *
     * A page with two "Next" buttons is common, and picking the first one
     * blindly is how an agent ends up in the wrong place.
     */
    public fun byTextOccurrence(index: NodeIndex, text: String, occurrence: Int): NodeRef? {
        require(occurrence >= 0) { "an occurrence index cannot be negative" }
        return byText(index, text).getOrNull(occurrence)
    }

    /** The nodes with a given view id. */
    public fun byViewId(index: NodeIndex, viewId: String): List<NodeRef> =
        index.nodesWithViewId(viewId)

    /** The nodes of a given class name. */
    public fun byClass(index: NodeIndex, className: String): List<NodeRef> =
        index.nodesOfClass(className)

    /**
     * The nodes that look like a text field.
     *
     * A field is a node that is editable, or a node whose class is one of the
     * known input classes.
     */
    public fun editableFields(index: NodeIndex): List<NodeRef> = index.source.nodes.filter { node ->
        node.editable || node.className in EDITABLE_CLASSES
    }

    /**
     * The node the user is typing into, or null.
     *
     * The focused field wins. When nothing has focus, the first editable
     * field is used, because an agent that tapped into a field and then
     * asked for a fresh tree should find it.
     */
    public fun focusedField(index: NodeIndex): NodeRef? =
        index.source.nodes.firstOrNull { it.focused && (it.editable || it.className in EDITABLE_CLASSES) }
            ?: editableFields(index).firstOrNull()

    /** The nodes that are web content. */
    public fun webNodes(index: NodeIndex): List<NodeRef> =
        index.source.nodes.filter { node ->
            node.className.startsWith("android.webkit") || node.className.startsWith("chrome://")
        }

    /**
     * The node at a relative point, with the clickable ancestor resolved.
     *
     * This is what a coordinate tap resolves to: the node under the point,
     * and the nearest clickable thing above it.
     */
    public fun actionableAt(index: NodeIndex, x: RelativePoint, y: RelativePoint): NodeRef? {
        val hit = index.nodeAt(x.fraction, y.fraction) ?: return null
        return index.clickableAncestor(hit) ?: hit
    }

    /** The text of a node, or its content description, or an empty string. */
    public fun labelOf(index: NodeIndex, node: NodeRef): String = index.labelOf(node)

    private val EDITABLE_CLASSES = setOf(
        "android.widget.EditText",
        "android.widget.AutoCompleteTextView",
        "android.widget.MultiAutoCompleteTextView",
        "android.widget.SearchView",
    )
}
