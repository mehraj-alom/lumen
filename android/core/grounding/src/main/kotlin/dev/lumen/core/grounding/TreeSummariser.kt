package dev.lumen.core.grounding

import dev.lumen.core.model.NodeRef

/**
 * Turns a tree into a summary that fits the planner's prompt.
 *
 * The raw tree is never placed in a prompt. It is too large, and most of it
 * is noise: containers with no text, invisible views, repeated list rows. The
 * summariser keeps the nodes that carry information, scores them, and drops
 * the least useful ones until the summary fits the budget.
 *
 * The result is deterministic. The same tree always produces the same
 * summary, in the same order, with the same content (invariant I8).
 */
public class TreeSummariser(
    private val maxCharacters: Int = DEFAULT_MAX_CHARACTERS,
) {
    init {
        require(maxCharacters > 0) { "the summary budget must be positive" }
    }

    /** The summary of [snapshot]. */
    public fun summarise(snapshot: TreeSnapshot): String {
        val header = buildString {
            append("app=").append(snapshot.app)
            append(" nodes=").append(snapshot.nodeCount)
            if (snapshot.overlays.isNotEmpty()) {
                append(" overlays=").append(snapshot.overlays.size)
            }
        }

        val lines = snapshot.nodes.mapNotNull { node -> lineFor(node) }
        val budget = maxCharacters - header.length - 2
        if (budget <= 0) return header

        val selected = selectWithinBudget(lines, budget)
        return buildString {
            append(header)
            for (line in selected) {
                append('\n').append(line)
            }
        }
    }

    /**
     * The lines that carry information, scored by how much they are worth.
     *
     * A line is only produced for a node that says something, or that can be
     * acted on. A container with no text of its own is represented by its
     * children instead.
     */
    private fun lineFor(node: NodeRef): String? {
        val label = node.text?.takeIf { it.isNotBlank() }
            ?: node.contentDescription?.takeIf { it.isNotBlank() }
        val actionable = node.clickable || node.editable || node.scrollable

        if (label == null && !actionable) return null

        return buildString {
            append("  ".repeat(node.depth.coerceIn(0, MAX_DEPTH)))
            append(node.className.substringAfterLast('.'))
            val viewId = node.viewId
            if (viewId != null) append('#').append(viewId.substringAfterLast('/'))
            if (label != null) append(" \"").append(label.take(MAX_LABEL)).append('"')
            if (node.clickable) append(" [clickable]")
            if (node.editable) append(" [editable]")
            if (node.scrollable) append(" [scrollable]")
            if (node.focused) append(" [focused]")
        }
    }

    /**
     * The lines that fit the budget, keeping the most valuable ones and
     * preserving the original order.
     */
    private fun selectWithinBudget(lines: List<String>, budget: Int): List<String> {
        val total = lines.sumOf { it.length + 1 }
        if (total <= budget) return lines

        val scored = lines
            .mapIndexed { index, line -> ScoredLine(index, line, score(line)) }
            .sortedWith(compareByDescending<ScoredLine> { it.score }.thenBy { it.index })

        val kept = sortedSetOf<Int>()
        var used = 0
        for (candidate in scored) {
            val cost = candidate.line.length + 1
            if (used + cost > budget) continue
            kept += candidate.index
            used += cost
            if (used >= budget) break
        }

        return lines.filterIndexed { index, _ -> index in kept }
    }

    /**
     * How much a line is worth.
     *
     * A clickable label is worth more than a static one, because it is a
     * candidate for the next action. A focused field is worth the most,
     * because it is where the text goes.
     */
    private fun score(line: String): Int {
        var value = 0
        if (line.contains("[clickable]")) value += 8
        if (line.contains("[editable]")) value += 6
        if (line.contains("[focused]")) value += 4
        if (line.contains("[scrollable]")) value += 2
        if (line.contains('"')) value += 3
        return value
    }

    private data class ScoredLine(val index: Int, val line: String, val score: Int)

    public companion object {
        /**
         * The default character budget.
         *
         * A planner prompt holds 8,192 tokens. At roughly four characters to
         * the token, a quarter of that is about 8,000 characters, and the
         * summary is one part of the prompt among several.
         */
        public const val DEFAULT_MAX_CHARACTERS: Int = 8_000

        private const val MAX_LABEL: Int = 120
        private const val MAX_DEPTH: Int = 6
    }
}
