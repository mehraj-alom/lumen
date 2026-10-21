package dev.lumen.core.grounding

import com.google.common.truth.Truth.assertThat
import dev.lumen.core.model.ErrorCode
import dev.lumen.core.model.NodeRef
import dev.lumen.core.model.Observation
import kotlinx.coroutines.test.runTest
import org.junit.Test

class TreeSummariserTest {

    @Test
    fun `T-NAV-04 a summary fits the character budget`() {
        val snapshot = SyntheticTreeBuilder.deepTree(depth = 12, breadth = 4)
        val summariser = TreeSummariser(maxCharacters = 500)

        val summary = summariser.summarise(snapshot)

        assertThat(summary.length).isAtMost(500)
        assertThat(summary).startsWith("app=com.example.app")
        assertThat(summary).contains("nodes=49")
    }

    @Test
    fun `a summary of a small tree keeps everything`() {
        val snapshot = SyntheticTreeBuilder.typicalScreen()
        val summariser = TreeSummariser(maxCharacters = 8_000)

        val summary = summariser.summarise(snapshot)

        assertThat(summary).contains("EditText")
        assertThat(summary).contains("Button")
        assertThat(summary).contains("Search")
        assertThat(summary).contains("[editable]")
        assertThat(summary).contains("[clickable]")
    }

    @Test
    fun `the summary is deterministic`() {
        val snapshot = SyntheticTreeBuilder.deepTree()
        val summariser = TreeSummariser(maxCharacters = 600)

        val first = summariser.summarise(snapshot)
        val second = summariser.summarise(snapshot)

        assertThat(first).isEqualTo(second)
    }

    @Test
    fun `the summary drops the least valuable lines first`() {
        val snapshot = SyntheticTreeBuilder.deepTree(depth = 10, breadth = 5)
        val summariser = TreeSummariser(maxCharacters = 400)

        val summary = summariser.summarise(snapshot)

        assertThat(summary.length).isAtMost(400)
        // Every line in the deep tree is a plain text view, so they all score
        // the same and the selection is the first ones in order.
        assertThat(summary).contains("level 0 column 0")
    }

    @Test
    fun `a node with neither text nor action is not summarised`() {
        val builder = SyntheticTreeBuilder()
        val root = builder.root()
        builder.child(parent = root, className = "android.widget.FrameLayout")
        val summariser = TreeSummariser()

        val summary = summariser.summarise(builder.build())

        assertThat(summary).doesNotContain("FrameLayout")
    }

    @Test
    fun `an empty tree still produces a header`() {
        val summary = TreeSummariser().summarise(SyntheticTreeBuilder.canvasRegion())

        assertThat(summary).isEqualTo("app=com.example.app nodes=0")
    }

    @Test
    fun `the view id is shown without its package prefix`() {
        val summary = TreeSummariser().summarise(SyntheticTreeBuilder.typicalScreen())

        assertThat(summary).contains("#id/search")
    }
}

class GroundingResolverTest {

    private fun observation(nodeCount: Int): Observation = Observation(
        app = "com.example.app",
        nodes = List(nodeCount) { index ->
            NodeRef(
                id = index,
                className = "android.widget.TextView",
                text = "node $index",
            )
        },
    )

    @Test
    fun `the accessibility tree is the primary source`() = runTest {
        val resolver = GroundingResolver(
            tree = GroundingSourceReader { observation(3) },
            vision = GroundingSourceReader { observation(1) },
            visionEnabled = true,
        )

        val result = resolver.observe(ObservationRequest())

        assertThat(result.source).isEqualTo(GroundingSource.TREE)
        assertThat(result.observation?.nodes).hasSize(3)
    }

    @Test
    fun `the vision model is the fallback when the tree is empty`() = runTest {
        val resolver = GroundingResolver(
            tree = GroundingSourceReader { observation(0) },
            vision = GroundingSourceReader { observation(1) },
            visionEnabled = true,
        )

        val result = resolver.observe(ObservationRequest())

        assertThat(result.source).isEqualTo(GroundingSource.VLM)
    }

    @Test
    fun `the vision model is not used when it is disabled`() = runTest {
        val resolver = GroundingResolver(
            tree = GroundingSourceReader { observation(0) },
            vision = GroundingSourceReader { observation(1) },
            visionEnabled = false,
        )

        val result = resolver.observe(ObservationRequest())

        assertThat(result.source).isEqualTo(GroundingSource.NONE)
        assertThat(result.error?.code).isEqualTo(ErrorCode.GROUNDING_UNAVAILABLE)
    }

    @Test
    fun `an empty tree with no fallback reports the grounding failure`() = runTest {
        val resolver = GroundingResolver(tree = GroundingSourceReader { observation(0) })

        val result = resolver.observe(ObservationRequest())

        assertThat(result.source).isEqualTo(GroundingSource.NONE)
        assertThat(result.isUsable).isFalse()
        assertThat(result.error?.code).isEqualTo(ErrorCode.GROUNDING_UNAVAILABLE)
    }
}
