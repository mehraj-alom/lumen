package dev.lumen.core.grounding

import dev.lumen.core.model.ApiError
import dev.lumen.core.model.ErrorCode
import dev.lumen.core.model.Observation

/** What was asked of the grounding layer. */
public data class ObservationRequest(
    val includeScreenshot: Boolean = false,
    val includeOverlays: Boolean = true,
    val maxNodes: Int = 400,
)

/** Where an observation came from. */
public enum class GroundingSource {
    /** The accessibility tree. The primary source. */
    TREE,

    /** A vision model reading a screenshot. The fallback. */
    VLM,

    /** Nothing usable was found. */
    NONE,
}

/** The result of a grounding request. */
public data class ObservationResult(
    val source: GroundingSource,
    val observation: Observation?,
    val error: ApiError? = null,
) {
    /** Whether an observation was produced. */
    public val isUsable: Boolean get() = observation != null
}

/** A source of observations. */
public fun interface GroundingSourceReader {
    /** Reads the screen, or returns null when it cannot. */
    public suspend fun read(request: ObservationRequest): Observation?
}

/**
 * The port the actor uses to see the screen.
 *
 * The actor never touches the accessibility APIs. It asks the port, and the
 * port decides where the observation came from.
 */
public interface GroundingPort {
    /** Reads the screen. */
    public suspend fun observe(request: ObservationRequest): ObservationResult
}

/**
 * The grounding precedence.
 *
 * The accessibility tree is the primary source: it is cheap, it is
 * structured, and it is always available. A vision model is the fallback,
 * used only when the tree cannot answer the question — an empty tree, a
 * canvas-rendered screen, or a tree the critic rejected.
 *
 * The fallback is used when it is enabled. It is not used by default,
 * because it costs money, it is slow, and it needs a frame.
 */
public class GroundingResolver(
    private val tree: GroundingSourceReader,
    private val vision: GroundingSourceReader? = null,
    private val visionEnabled: Boolean = false,
) : GroundingPort {

    override suspend fun observe(request: ObservationRequest): ObservationResult {
        val fromTree = tree.read(request)
        if (fromTree != null && fromTree.nodes.isNotEmpty()) {
            return ObservationResult(GroundingSource.TREE, fromTree)
        }

        if (visionEnabled && vision != null) {
            val fromVision = vision.read(request)
            if (fromVision != null) {
                return ObservationResult(GroundingSource.VLM, fromVision)
            }
        }

        return ObservationResult(
            source = GroundingSource.NONE,
            observation = null,
            error = ApiError(
                code = ErrorCode.GROUNDING_UNAVAILABLE,
                message = "neither the accessibility tree nor the vision model could read the screen",
            ),
        )
    }
}
