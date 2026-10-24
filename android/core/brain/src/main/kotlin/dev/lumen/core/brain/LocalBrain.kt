package dev.lumen.core.brain

import dev.lumen.core.model.Action
import dev.lumen.core.model.RiskClass
import dev.lumen.core.model.Verdict
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/**
 * The native inference runtime.
 *
 * This is the seam between the agent and llama.cpp. The implementation
 * loads the native library and calls it; the agent never does.
 */
public interface NativeInference {

    /** Whether the native library is present and a model is loaded. */
    public fun isReady(): Boolean

    /** The model that is loaded, or null. */
    public fun loadedModel(): String?

    /** Loads a model from the sandbox. */
    public fun loadModel(path: String, contextSize: Int): Boolean

    /** Unloads the model and releases its memory. */
    public fun unloadModel()

    /**
     * Generates a completion.
     *
     * @param prompt the prompt
     * @param maxTokens the ceiling on the reply
     * @param seed the sampling seed, for determinism
     * @return the reply, or null when the runtime could not serve it
     */
    public fun complete(prompt: String, maxTokens: Int, seed: Long): String?
}

/**
 * The local brain.
 *
 * The same contract as the cloud brain, served by a model on the device.
 * The rules are the same: the reply is validated against the tool schema
 * before it becomes steps, and a reply that does not fit fails the step.
 *
 * The model is loaded through the quota. A model that does not fit inside
 * the remaining RAM budget is never loaded, and the router escalates to the
 * cloud instead.
 */
public class LocalBrain(private val inference: NativeInference, private val maxTokens: Int = DEFAULT_MAX_TOKENS) : BrainPort {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override val route: BrainRoute get() = BrainRoute.LOCAL

    override suspend fun plan(request: PlanRequest): BrainReply {
        val prompt = buildPlanPrompt(request)
        val reply = generate(prompt) ?: return BrainReply("the local model could not answer", emptyList())
        return parsePlan(reply)
    }

    override suspend fun replan(request: ReplanRequest): BrainReply {
        val prompt = buildReplanPrompt(request)
        val reply = generate(prompt) ?: return BrainReply("the local model could not answer", emptyList())
        return parsePlan(reply)
    }

    override suspend fun critique(request: CritiqueRequest): Critique {
        val prompt = buildCritiquePrompt(request)
        val reply = generate(prompt) ?: return Critique(Verdict.FAIL, "the local model could not answer")
        return decodeCritique(reply)
    }

    private fun decodeCritique(reply: String): Critique {
        val envelope = try {
            json.decodeFromString(CritiqueReply.serializer(), reply)
        } catch (ignored: SerializationException) {
            // The local model is allowed to answer nonsense: the failure is
            // deliberately converted into a FAIL verdict, not propagated.
            // The parser's own message quotes the reply, and a reply can
            // carry screen text that may not travel unredacted, so the
            // detail is dropped on purpose here and in `parsePlan` (§5).
            return Critique(Verdict.FAIL, "the local reply did not match the critique envelope")
        }

        val verdict = Verdict.entries.firstOrNull { it.name.equals(envelope.verdict, ignoreCase = true) }
            ?: return Critique(Verdict.FAIL, "the local reply carried an unknown verdict")

        return Critique(verdict, envelope.reason, envelope.observationSummary)
    }

    private fun generate(prompt: String): String? {
        if (!inference.isReady()) return null
        return inference.complete(prompt, maxTokens, seed = 0L)
    }

    private fun parsePlan(reply: String): BrainReply {
        val envelope = try {
            json.decodeFromString(PlanReply.serializer(), reply)
        } catch (ignored: SerializationException) {
            // The detail is dropped with a reason; see `decodeCritique`.
            return BrainReply("the local reply did not match the plan envelope", emptyList(), reply)
        }

        val steps = envelope.steps.mapNotNull { step ->
            val action: Action = ActionCodec.decode(step.action.tool, step.action.args)
                ?: return@mapNotNull null
            PlannedStep(
                seq = step.seq,
                intent = step.intent,
                reason = step.reason,
                action = action,
                expectation = step.expect,
                riskClass = RiskClass.NONE,
            )
        }

        return BrainReply(envelope.thought, steps, reply)
    }

    private fun buildPlanPrompt(request: PlanRequest): String = buildString {
        appendLine("You are planning a mobile automation task.")
        appendLine("Reply with the plan envelope and nothing else.")
        appendLine("Goal: ${request.goal.text}")
        appendLine("Remaining steps: ${request.remainingSteps}")
    }

    private fun buildReplanPrompt(request: ReplanRequest): String = buildString {
        appendLine("The previous step failed. Reply with a revised plan envelope.")
        appendLine("Goal: ${request.goal.text}")
        appendLine("Failed step: ${request.failedStep.intent}")
        appendLine("Its reason: ${request.failedStep.reason.text}")
    }

    private fun buildCritiquePrompt(request: CritiqueRequest): String = buildString {
        appendLine("Judge the step. Reply with the critique envelope.")
        appendLine("Goal: ${request.goal.text}")
        appendLine("Step: ${request.step.intent}")
    }

    public companion object {
        /** The default ceiling on a local reply. */
        public const val DEFAULT_MAX_TOKENS: Int = 2_048
    }
}

/**
 * The registry of local models.
 *
 * The user installs a model; the agent never downloads one. The registry
 * says what a model is for and what it costs in memory, so the router can
 * decide whether it fits before anything is loaded.
 */
public object LocalModelRegistry {

    /** A model the user may install. */
    public data class Entry(val id: String, val role: Role, val fileName: String, val residentMiB: Int, val contextTokens: Int)

    /** What a model is for. */
    public enum class Role {
        PLANNER,
        CRITIC,
        VISION,
    }

    /** The models the agent knows about. */
    public val entries: List<Entry> = listOf(
        Entry(
            id = "qwen2.5-1.5b-instruct-q4",
            role = Role.PLANNER,
            fileName = "qwen2.5-1.5b-instruct-q4_k_m.gguf",
            residentMiB = 1_100,
            contextTokens = 8_192,
        ),
        Entry(
            id = "qwen2.5-0.5b-instruct-q4",
            role = Role.CRITIC,
            fileName = "qwen2.5-0.5b-instruct-q4_k_m.gguf",
            residentMiB = 500,
            contextTokens = 4_096,
        ),
    )

    /** The entry for [id], or null. */
    public fun entry(id: String): Entry? = entries.firstOrNull { it.id == id }

    /** The model for [role], or null. */
    public fun forRole(role: Role): Entry? = entries.firstOrNull { it.role == role }
}
