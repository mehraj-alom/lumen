package dev.lumen.core.grounding

import dev.lumen.core.model.NodeRef
import dev.lumen.core.model.Observation
import dev.lumen.core.model.OverlayInfo

/**
 * A snapshot of the screen's node tree.
 *
 * The tree is flat: the nodes are in a canonical order and each one points at
 * its parent by index. A flat list is cheaper to serialise, cheaper to diff
 * between two observations, and cheaper to hold inside the 192 MiB tree-cache
 * budget than a nested structure would be.
 */
public data class TreeSnapshot(
    val app: String,
    val nodes: List<NodeRef>,
    val overlays: List<OverlayInfo> = emptyList(),
    val capturedAtMs: Long = 0L,
) {
    init {
        require(app.isNotBlank()) { "a snapshot must name the foreground app" }
    }

    /**
     * A stable fingerprint of the tree.
     *
     * The fingerprint covers only what a change in the screen would alter:
     * the identity, the class, the visible text, and the bounds of each node.
     * Two snapshots with the same fingerprint describe the same screen, which
     * is what the settle detector's node-count heuristic approximates.
     */
    public val signature: String by lazy {
        val builder = StringBuilder(app)
        for (node in nodes) {
            builder.append('|').append(node.id)
                .append(':').append(node.className)
                .append(':').append(node.text.orEmpty())
                .append(':').append(node.contentDescription.orEmpty())
            node.bounds?.let { bounds ->
                builder.append(':').append(bounds.left)
                    .append(',').append(bounds.top)
                    .append(',').append(bounds.right)
                    .append(',').append(bounds.bottom)
            }
        }
        fnv1a(builder.toString())
    }

    /** The number of nodes. */
    public val nodeCount: Int get() = nodes.size

    /** Whether the snapshot carries anything a planner could act on. */
    public val isUsable: Boolean get() = nodes.isNotEmpty()

    /** The observation this snapshot describes. */
    public fun toObservation(): Observation = Observation(
        app = app,
        nodes = nodes,
        overlays = overlays,
        capturedAtMs = capturedAtMs,
        signature = signature,
    )
}

/**
 * An index over a snapshot.
 *
 * The index is built once per snapshot and answers the questions the actor
 * and the planner ask most often: what is at this point, what has this id,
 * what says this, and what is the clickable ancestor of this node.
 */
public class NodeIndex(private val snapshot: TreeSnapshot) {

    private val byId: Map<Int, NodeRef> = snapshot.nodes.associateBy { it.id }
    private val byViewId: Map<String, List<NodeRef>> = snapshot.nodes
        .filter { !it.viewId.isNullOrBlank() }
        .groupBy { it.viewId!! }
    private val byClass: Map<String, List<NodeRef>> = snapshot.nodes.groupBy { it.className }

    /** The snapshot this index was built over. */
    public val source: TreeSnapshot get() = snapshot

    /** The node with [id], or null. */
    public fun node(id: Int): NodeRef? = byId[id]

    /** The nodes with a given view id. */
    public fun nodesWithViewId(viewId: String): List<NodeRef> = byViewId[viewId].orEmpty()

    /** The nodes of a given class. */
    public fun nodesOfClass(className: String): List<NodeRef> = byClass[className].orEmpty()

    /** The children of [id]. */
    public fun childrenOf(id: Int): List<NodeRef> = snapshot.nodes.filter { it.parentId == id }

    /** The root nodes, the ones with no parent. */
    public fun roots(): List<NodeRef> = snapshot.nodes.filter { it.parentId == null }

    /**
     * The nearest clickable ancestor of [node], or the node itself.
     *
     * A label is often on a container while the clickable flag is on a child,
     * or the other way round. Walking up until something is clickable is what
     * makes "click the thing that says 'Pay'" work.
     */
    public fun clickableAncestor(node: NodeRef): NodeRef? {
        var current: NodeRef? = node
        val seen = mutableSetOf<Int>()
        while (current != null && seen.add(current.id)) {
            if (current.clickable) return current
            current = current.parentId?.let { byId[it] }
        }
        return null
    }

    /** The chain from the root down to [node], inclusive. */
    public fun pathTo(node: NodeRef): List<NodeRef> {
        val path = mutableListOf<NodeRef>()
        var current: NodeRef? = node
        val seen = mutableSetOf<Int>()
        while (current != null && seen.add(current.id)) {
            path += current
            current = current.parentId?.let { byId[it] }
        }
        return path.reversed()
    }

    /** The label of a node: its text, or its content description, or neither. */
    public fun labelOf(node: NodeRef): String = node.text?.takeIf { it.isNotBlank() }
        ?: node.contentDescription?.takeIf { it.isNotBlank() }
        ?: ""

    /**
     * The node at a relative point, or null.
     *
     * The deepest node whose bounds contain the point wins, so a button
     * inside a card is found before the card.
     */
    public fun nodeAt(x: Double, y: Double): NodeRef? = snapshot.nodes
        .filter { node ->
            val bounds = node.bounds ?: return@filter false
            x >= bounds.left && x <= bounds.right && y >= bounds.top && y <= bounds.bottom
        }
        .maxByOrNull { node -> node.depth }
}

/**
 * A 64-bit FNV-1a hash, rendered as hex.
 *
 * The fingerprint has to be stable across processes and versions, so it is
 * implemented here rather than taken from `hashCode`, whose contract is not
 * stable across implementations.
 */
internal fun fnv1a(value: String): String {
    var hash = FNV_OFFSET_BASIS
    for (character in value) {
        hash = hash xor character.code.toLong()
        hash *= FNV_PRIME
    }
    return java.lang.Long.toHexString(hash)
}

// The FNV-1a 64-bit parameters, named so the algorithm reads as the spec
// states it. These are fixed constants of the hash, not tuning values.
private const val FNV_OFFSET_BASIS: Long = -0x340d631b7bdddcdbL
private const val FNV_PRIME: Long = 0x100000001b3L
