package dev.lumen.core.brain.fixtures

import dev.lumen.core.brain.BrainPort
import dev.lumen.core.brain.BrainReply
import dev.lumen.core.brain.BrainRoute
import dev.lumen.core.brain.Critique
import dev.lumen.core.brain.CritiqueRequest
import dev.lumen.core.brain.PlanRequest
import dev.lumen.core.brain.PlannedStep
import dev.lumen.core.brain.ReplanRequest
import dev.lumen.core.model.Action
import dev.lumen.core.model.RiskClass
import dev.lumen.core.model.Verdict

/**
 * A brain that answers from a script.
 *
 * The fake is what lets the agent loop be tested without a model, a
 * network, or a device.
 */
public class FakeBrain(override val route: BrainRoute = BrainRoute.LOCAL) : BrainPort {

    /** The plan the brain returns. */
    public var planReply: BrainReply = BrainReply(thought = "no plan", steps = emptyList())

    /** The plan the brain returns when asked to replan. */
    public var replanReply: BrainReply = BrainReply(thought = "no replan", steps = emptyList())

    /** The judgement the brain returns. */
    public var critiqueReply: Critique = Critique(Verdict.CONTINUE, "all good")

    /**
     * Successive judgements, one per critique call; the last entry repeats.
     * Takes precedence over [critiqueReply].
     */
    public var critiqueReplies: List<Critique> = emptyList()

    /** The error the brain fails with, or null. */
    public var failure: Exception? = null

    /** How many times each entry point was called. */
    public var planCalls: Int = 0
    public var replanCalls: Int = 0
    public var critiqueCalls: Int = 0

    override suspend fun plan(request: PlanRequest): BrainReply {
        planCalls++
        failure?.let { throw it }
        return planReply
    }

    override suspend fun replan(request: ReplanRequest): BrainReply {
        replanCalls++
        failure?.let { throw it }
        return replanReply
    }

    override suspend fun critique(request: CritiqueRequest): Critique {
        critiqueCalls++
        failure?.let { throw it }
        if (critiqueReplies.isEmpty()) return critiqueReply
        return critiqueReplies.getOrElse(critiqueCalls - 1) { critiqueReplies.last() }
    }

    public companion object {
        /** A plan with one step. */
        public fun planWith(vararg steps: PlannedStep): BrainReply = BrainReply(thought = "scripted", steps = steps.toList())

        /** A step that taps a label. */
        public fun tapStep(intent: String, label: String): PlannedStep = PlannedStep(
            seq = 1,
            intent = intent,
            reason = "scripted by the fake",
            action = Action.ClickText(label),
            riskClass = RiskClass.NONE,
        )
    }
}
