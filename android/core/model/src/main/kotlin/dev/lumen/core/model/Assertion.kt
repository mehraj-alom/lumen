@file:OptIn(ExperimentalSerializationApi::class)

package dev.lumen.core.model

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A condition that the critic evaluates against an observation.
 *
 * A step carries a precondition (what must be true before the action runs)
 * and an expectation (what must be true afterwards). The expectation is what
 * makes a replan possible: without it, a step that did not work looks exactly
 * like a step that did.
 */
@Serializable
public sealed interface Assertion {

    /** The foreground app is [packageName]. */
    @Serializable
    @SerialName("app_is")
    public data class AppIs(val packageName: String) : Assertion {
        init {
            require(packageName.isNotBlank()) { "a package name must not be blank" }
        }
    }

    /** Some visible text contains [text], case-insensitively. */
    @Serializable
    @SerialName("text_present")
    public data class TextPresent(val text: String) : Assertion {
        init {
            require(text.isNotBlank()) { "a text assertion must not be blank" }
        }
    }

    /** No visible text contains [text], case-insensitively. */
    @Serializable
    @SerialName("text_absent")
    public data class TextAbsent(val text: String) : Assertion {
        init {
            require(text.isNotBlank()) { "a text assertion must not be blank" }
        }
    }

    /** The screen holds at least [count] nodes. */
    @Serializable
    @SerialName("node_count_at_least")
    public data class NodeCountAtLeast(val count: Int) : Assertion {
        init {
            require(count >= 0) { "a node count cannot be negative" }
        }
    }

    /** The screen exposes an overlay window. */
    @Serializable
    @SerialName("has_overlay")
    public data class HasOverlay(val packageName: String? = null) : Assertion

    /** At least one of [assertions] holds. */
    @Serializable
    @SerialName("any_of")
    public data class AnyOf(val assertions: List<Assertion>) : Assertion {
        init {
            require(assertions.isNotEmpty()) { "an any-of assertion needs at least one alternative" }
        }
    }

    /** No assertion holds. */
    @Serializable
    @SerialName("none")
    public object None : Assertion
}

/**
 * Evaluates an [Assertion] against an [Observation].
 *
 * This lives in the model module rather than in the critic because both the
 * actor and the critic need it, and because it must be testable without a
 * brain.
 */
public object Assertions {

    /** Whether [assertion] holds for [observation]. */
    public fun holds(assertion: Assertion, observation: Observation): Boolean = when (assertion) {
        is Assertion.AppIs -> observation.app == assertion.packageName
        is Assertion.TextPresent -> observation.nodesWithText(assertion.text).isNotEmpty()
        is Assertion.TextAbsent -> observation.nodesWithText(assertion.text).isEmpty()
        is Assertion.NodeCountAtLeast -> observation.nodes.size >= assertion.count
        is Assertion.HasOverlay -> observation.overlays.any { overlay ->
            assertion.packageName == null || overlay.packageName == assertion.packageName
        }
        is Assertion.AnyOf -> assertion.assertions.any { holds(it, observation) }
        Assertion.None -> true
    }

    /** Whether every assertion in [assertions] holds for [observation]. */
    public fun allHold(assertions: List<Assertion>, observation: Observation): Boolean = assertions.all { holds(it, observation) }
}
