package dev.lumen.core.grounding

import com.google.common.truth.Truth.assertThat
import dev.lumen.core.grounding.fixtures.SyntheticTreeBuilder
import dev.lumen.core.model.RelativePoint
import org.junit.Test

class NodeMatcherTest {

    @Test
    fun `T-NAV-01 a label on a non-clickable child resolves to the clickable ancestor`() {
        val snapshot = SyntheticTreeBuilder.labelOnNonClickableChild()
        val index = NodeIndex(snapshot)

        val match = NodeMatcher.byText(index, "Pay 382 EUR").first()
        val actionable = index.clickableAncestor(match)

        assertThat(actionable).isNotNull()
        assertThat(actionable?.clickable).isTrue()
        assertThat(actionable?.id).isNotEqualTo(match.id)
    }

    @Test
    fun `T-NAV-02 duplicate labels are all returned`() {
        val snapshot = SyntheticTreeBuilder.duplicateLabels()
        val index = NodeIndex(snapshot)

        val matches = NodeMatcher.byText(index, "Next")

        assertThat(matches).hasSize(2)
        assertThat(NodeMatcher.byTextOccurrence(index, "Next", 0)?.id).isEqualTo(matches[0].id)
        assertThat(NodeMatcher.byTextOccurrence(index, "Next", 1)?.id).isEqualTo(matches[1].id)
        assertThat(NodeMatcher.byTextOccurrence(index, "Next", 2)).isNull()
    }

    @Test
    fun `T-NAV-02 a text match is case insensitive`() {
        val index = NodeIndex(SyntheticTreeBuilder.labelOnNonClickableChild())

        assertThat(NodeMatcher.byText(index, "pay 382")).hasSize(1)
    }

    @Test
    fun `T-NAV-03 a stale recycler row is not treated as a hit target`() {
        val snapshot = SyntheticTreeBuilder.staleRecyclerItem()
        val index = NodeIndex(snapshot)

        val hit = index.nodeAt(0.5, 0.5)

        assertThat(hit).isNull()
    }

    @Test
    fun `T-NAV-05 a canvas screen with no nodes is not usable`() {
        val snapshot = SyntheticTreeBuilder.canvasRegion()

        assertThat(snapshot.isUsable).isFalse()
        assertThat(snapshot.nodeCount).isEqualTo(0)
    }

    @Test
    fun `T-NAV-06 web content is recognised`() {
        val index = NodeIndex(SyntheticTreeBuilder.webContent())

        val webNodes = NodeMatcher.webNodes(index)

        assertThat(webNodes).isNotEmpty()
        assertThat(webNodes.all { it.className.startsWith("android.webkit") }).isTrue()
    }

    @Test
    fun `T-NAV-07 a node with only a content description is labelled by it`() {
        val index = NodeIndex(SyntheticTreeBuilder.contentDescriptionOnly())

        val node = index.source.nodes.first { it.contentDescription != null }

        assertThat(index.labelOf(node)).isEqualTo("Search flights")
        assertThat(NodeMatcher.byText(index, "Search flights")).hasSize(1)
    }

    @Test
    fun `T-NAV-08 the snapshot signature is stable and order sensitive`() {
        val snapshot = SyntheticTreeBuilder.typicalScreen()
        val same = SyntheticTreeBuilder.typicalScreen()

        assertThat(snapshot.signature).isEqualTo(same.signature)

        val reordered = snapshot.copy(nodes = snapshot.nodes.reversed())
        assertThat(reordered.signature).isNotEqualTo(snapshot.signature)
    }

    @Test
    fun `a coordinate hit resolves to the deepest node`() {
        val index = NodeIndex(SyntheticTreeBuilder.labelOnNonClickableChild())

        val hit = index.nodeAt(0.2, 0.3)

        assertThat(hit).isNotNull()
        assertThat(hit?.text).isEqualTo("Pay 382 EUR")
    }

    @Test
    fun `an actionable hit resolves to the clickable ancestor`() {
        val index = NodeIndex(SyntheticTreeBuilder.labelOnNonClickableChild())

        val actionable = NodeMatcher.actionableAt(index, RelativePoint(0.2), RelativePoint(0.3))

        assertThat(actionable?.clickable).isTrue()
    }

    @Test
    fun `the focused field is found when there is one`() {
        val index = NodeIndex(SyntheticTreeBuilder.typicalScreen())

        val field = NodeMatcher.focusedField(index)

        assertThat(field).isNotNull()
        assertThat(field?.editable).isTrue()
        assertThat(field?.focused).isTrue()
    }

    @Test
    fun `the path to a node runs from the root`() {
        val index = NodeIndex(SyntheticTreeBuilder.labelOnNonClickableChild())
        val leaf = index.source.nodes.last()

        val path = index.pathTo(leaf)

        assertThat(path).hasSize(3)
        assertThat(path.first().parentId).isNull()
        assertThat(path.last().id).isEqualTo(leaf.id)
    }

    @Test
    fun `a cycle in the parent chain does not hang the matcher`() {
        val snapshot = SyntheticTreeBuilder.typicalScreen()
        val cyclic = snapshot.copy(
            nodes = snapshot.nodes.map { node ->
                if (node.parentId == null) node.copy(parentId = node.id + 1) else node
            },
        )
        val index = NodeIndex(cyclic)

        val path = index.pathTo(cyclic.nodes.first())

        assertThat(path).isNotEmpty()
    }
}
