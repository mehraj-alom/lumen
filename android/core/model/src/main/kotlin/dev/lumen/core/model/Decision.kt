@file:OptIn(ExperimentalSerializationApi::class)

package dev.lumen.core.model

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What the planner decided to do next.
 *
 * A decision is data, not authority. The actor validates it, the policy engine
 * classifies it, and the actor may refuse it. The planner cannot dispatch
 * anything itself.
 */
@Serializable
public sealed interface Decision {

    /** Run this step. */
    @Serializable
    @SerialName("execute")
    public data class Execute(val step: Step) : Decision

    /** Replace the plan with [revised] and carry on. */
    @Serializable
    @SerialName("replan")
    public data class Replan(val reason: Reason, val revised: Plan) : Decision

    /** The goal is satisfied. */
    @Serializable
    @SerialName("finish")
    public data class Finish(val summary: String) : Decision {
        init {
            require(summary.isNotBlank()) { "a finish must carry a summary" }
        }
    }

    /** The run cannot continue. */
    @Serializable
    @SerialName("abort")
    public data class Abort(val reason: Reason) : Decision
}
