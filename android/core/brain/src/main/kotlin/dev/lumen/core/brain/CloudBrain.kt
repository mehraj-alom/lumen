package dev.lumen.core.brain

import dev.lumen.core.model.ApiError
import dev.lumen.core.model.ErrorCode
import dev.lumen.core.model.Observation
import dev.lumen.core.model.RiskClass
import dev.lumen.core.model.Verdict
import dev.lumen.core.protocol.ContentEgressGuard
import dev.lumen.core.protocol.EgressContent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** An HTTP response, reduced to what the brain needs. */
public data class HttpResponse(val code: Int, val body: String)

/** The transport the cloud brain uses. */
public fun interface HttpTransport {
    /** Posts [body] to [url]. */
    public suspend fun post(url: String, headers: Map<String, String>, body: String): HttpResponse
}

/** The plan envelope the brain replies with. */
@Serializable
public data class PlanReply(
    @SerialName("plan_version") val planVersion: Int = 1,
    val thought: String = "",
    val steps: List<PlannedStepReply> = emptyList(),
)

/** One step of the plan envelope. */
@Serializable
public data class PlannedStepReply(
    val seq: Int = 0,
    val intent: String = "",
    val reason: String = "",
    val action: ActionReply = ActionReply(),
    val expect: dev.lumen.core.model.Assertion? = null,
    @SerialName("risk_class") val riskClass: String = "none",
)

/** The tool call inside a step. */
@Serializable
public data class ActionReply(val tool: String = "", val args: JsonObject = JsonObject(emptyMap()))

/** The critique envelope the brain replies with. */
@Serializable
public data class CritiqueReply(
    val verdict: String = "continue",
    val reason: String = "",
    @SerialName("observation_summary") val observationSummary: String? = null,
)

/**
 * A malformed reply from the brain.
 *
 * The parse failure that proved it malformed is kept as the cause, so the
 * audit trail says why the reply was rejected (invariant I9).
 */
public class MalformedBrainReply(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * The cloud brain.
 *
 * The brain is a *proposal*, never an authority. The reply is validated
 * against the tool schema before it is turned into steps, and a reply that
 * does not fit fails the step rather than being executed on a best guess.
 *
 * Screen-derived content in the prompt is checked by the egress guard, so a
 * run with spectating off cannot leak the screen to a cloud model.
 */
public class CloudBrain(
    private val transport: HttpTransport,
    private val baseUrl: String,
    private val apiKey: String,
    private val modelId: String,
    private val egress: ContentEgressGuard = ContentEgressGuard(),
    private val retry: RetryPolicy = RetryPolicy(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val temperature: Double = DEFAULT_TEMPERATURE,
    private val maxTokens: Int = DEFAULT_MAX_TOKENS,
) : BrainPort {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override val route: BrainRoute get() = BrainRoute.CLOUD

    override suspend fun plan(request: PlanRequest): BrainReply {
        val prompt = planPrompt(request)
        val (reply, usage) = call(prompt, "You are planning a mobile automation task. Reply with the plan envelope.")
        return parsePlan(reply, usage)
    }

    override suspend fun replan(request: ReplanRequest): BrainReply {
        val prompt = replanPrompt(request)
        val (reply, usage) = call(prompt, "The previous step failed. Reply with a revised plan envelope.")
        return parsePlan(reply, usage)
    }

    override suspend fun critique(request: CritiqueRequest): Critique {
        val prompt = critiquePrompt(request)
        val reply = call(prompt, "Judge the step. Reply with the critique envelope.")
        return parseCritique(reply)
    }

    /**
     * Sends [prompt] and returns the raw reply.
     *
     * The prompt is checked for screen content before it is sent, and the
     * call is retried by the policy rather than by the caller.
     */
    private suspend fun call(prompt: String, instruction: String): Pair<String, Usage> {
        val body = buildJsonObject {
            put("model", JsonPrimitive(modelId))
            put("temperature", JsonPrimitive(temperature))
            put("max_tokens", JsonPrimitive(maxTokens))
            put("seed", JsonPrimitive(0L))
            put(
                "messages",
                JsonArray(
                    listOf(
                        buildJsonObject {
                            put("role", JsonPrimitive("system"))
                            put("content", JsonPrimitive(instruction))
                        },
                        buildJsonObject {
                            put("role", JsonPrimitive("user"))
                            put("content", JsonPrimitive(prompt))
                        },
                    ),
                ),
            )
        }

        return retry.execute {
            val response = withContext(dispatcher) {
                transport.post(
                    url = "$baseUrl/chat/completions",
                    headers = mapOf(
                        "Authorization" to "Bearer $apiKey",
                        "Content-Type" to "application/json",
                    ),
                    body = body.toString(),
                )
            }

            refusalFor(response.code)?.let { throw it }
            extractContentAndUsage(response.body)
        }
    }

    /**
     * The failure a status code carries, or null when the reply may be read.
     *
     * A refused credential is terminal, so it is not retryable; a rate limit
     * or a server fault is transient and the retry policy feeds on it. The
     * status code stays in every message so the audit can tell the two apart.
     */
    private fun refusalFor(code: Int): Exception? = when {
        code == HTTP_UNAUTHORIZED || code == HTTP_FORBIDDEN ->
            MalformedBrainReply("the brain refused the credentials: $code")

        code == HTTP_TOO_MANY_REQUESTS || code >= HTTP_SERVER_ERROR ->
            BrainUnavailable(
                ApiError(
                    code = ErrorCode.BRAIN_UNAVAILABLE,
                    message = "the brain is unavailable: $code",
                ),
            )

        code !in HTTP_OK_MIN..HTTP_OK_MAX ->
            MalformedBrainReply("the brain replied with $code")

        else -> null
    }

    /** The assistant's text and token usage from a chat completion. */
    private fun extractContentAndUsage(body: String): Pair<String, Usage> {
        val parsed = parseReplyJson(body)
        val choices = (parsed["choices"] as? JsonArray).orMalformed("the brain reply had no choices")
        val message = ((choices.firstOrNull() as? JsonObject)?.get("message") as? JsonObject)
            .orMalformed("the brain reply had no message")
        val content = (message["content"] as? JsonPrimitive).orMalformed("the brain reply had no content").content

        val usage = (parsed["usage"] as? JsonObject)?.let { usage ->
            Usage(
                promptTokens = (usage["prompt_tokens"] as? JsonPrimitive)?.content?.toInt() ?: 0,
                completionTokens = (usage["completion_tokens"] as? JsonPrimitive)?.content?.toInt() ?: 0,
            )
        } ?: Usage()

        return content to usage
    }

    /**
     * The reply body as a json object.
     *
     * A body that is not a json object at all is the same malformed reply as
     * one that does not parse; the parse failure rides along as the cause.
     */
    private fun parseReplyJson(body: String): JsonObject {
        val element = try {
            json.parseToJsonElement(body)
        } catch (error: SerializationException) {
            throw MalformedBrainReply("the brain reply was not json", error)
        }
        return element as? JsonObject ?: throw MalformedBrainReply("the brain reply was not json")
    }

    private inline fun <T> T?.orMalformed(message: String): T = this ?: throw MalformedBrainReply(message)

    /** Turns a plan reply into steps, or fails. */
    internal fun parsePlan(reply: String, usage: Usage = Usage()): BrainReply {
        val envelope = try {
            json.decodeFromString(PlanReply.serializer(), reply)
        } catch (error: SerializationException) {
            throw MalformedBrainReply("the brain reply did not match the plan envelope", error)
        }

        val steps = envelope.steps.map { step ->
            val action = ActionCodec.decode(step.action.tool, step.action.args)
                ?: throw MalformedBrainReply(
                    "the step '${step.intent}' used the tool '${step.action.tool}' with arguments the schema does not allow",
                )
            PlannedStep(
                seq = step.seq,
                intent = step.intent,
                reason = step.reason,
                action = action,
                expectation = step.expect,
                riskClass = parseRiskClass(step.riskClass),
            )
        }

        return BrainReply(
            thought = envelope.thought,
            steps = steps,
            raw = reply,
            promptTokens = usage.promptTokens,
            completionTokens = usage.completionTokens,
        )
    }

    /** Turns a critique reply into a verdict, or fails. */
    internal fun parseCritique(reply: String): Critique {
        val envelope = try {
            json.decodeFromString(CritiqueReply.serializer(), reply)
        } catch (error: SerializationException) {
            throw MalformedBrainReply("the brain reply did not match the critique envelope", error)
        }
        val verdict = Verdict.entries.firstOrNull { it.name.equals(envelope.verdict, ignoreCase = true) }
            ?: throw MalformedBrainReply("the critique carried the unknown verdict '${envelope.verdict}'")

        return Critique(
            verdict = verdict,
            reason = envelope.reason,
            observationSummary = envelope.observationSummary,
        )
    }

    /**
     * The risk class of a step, as the brain named it.
     *
     * The brain's word is advisory. The policy engine decides the real
     * class, and this value can only make it stricter.
     */
    private fun parseRiskClass(value: String): RiskClass {
        val normalised = value.trim().lowercase()
        RiskClass.entries.firstOrNull { it.name.lowercase() == normalised }?.let { return it }
        if (normalised.startsWith("h")) {
            val index = normalised.removePrefix("h").toIntOrNull()
            if (index != null) {
                val ordered = listOf(
                    RiskClass.PAYMENT, RiskClass.PUBLISH, RiskClass.DELETE, RiskClass.CREDENTIAL,
                    RiskClass.PERMISSION, RiskClass.COMMUNICATION, RiskClass.ACCOUNT, RiskClass.LEGAL,
                    RiskClass.IRREVERSIBLE,
                )
                ordered.getOrNull(index - 1)?.let { return it }
            }
        }
        return RiskClass.NONE
    }

    private fun planPrompt(request: PlanRequest): String = buildString {
        appendLine("Goal: ${request.goal.text}")
        appendLine("Device: ${request.deviceState}")
        appendLine("Remaining steps: ${request.remainingSteps}")
        append(
            request.observations.take(MAX_OBSERVATIONS_IN_PROMPT).joinToString("\n") { observation ->
                "\nScreen in ${observation.app}:\n${summarise(observation)}"
            },
        )
    }

    private fun replanPrompt(request: ReplanRequest): String = buildString {
        appendLine("Goal: ${request.goal.text}")
        appendLine("The step '${request.failedStep.intent}' failed.")
        appendLine("Its reason was: ${request.failedStep.reason.text}")
        request.observation?.let { appendLine("The screen now shows:\n${summarise(it)}") }
    }

    private fun critiquePrompt(request: CritiqueRequest): String = buildString {
        appendLine("Goal: ${request.goal.text}")
        appendLine("Step: ${request.step.intent}")
        appendLine("Expected: ${request.step.expectation}")
        request.observation?.let { appendLine("The screen shows:\n${summarise(it)}") }
    }

    /**
     * The screen text that goes into a prompt.
     *
     * This is screen-derived content, so it is checked by the egress guard
     * before it can leave the device.
     */
    private fun summarise(observation: Observation): String {
        egress.require(EgressContent.SCREEN_TEXT)
        return observation.nodes
            .filter { !it.text.isNullOrBlank() }
            .take(MAX_NODES_IN_PROMPT)
            .joinToString("\n") { node -> node.text.orEmpty() }
    }

    public companion object {
        /** The default sampling temperature for the planner. */
        public const val DEFAULT_TEMPERATURE: Double = 0.2

        /** The default ceiling on a reply. */
        public const val DEFAULT_MAX_TOKENS: Int = 2_048

        private const val MAX_OBSERVATIONS_IN_PROMPT = 3
        private const val MAX_NODES_IN_PROMPT = 120

        // The status vocabulary of the chat endpoint. The values are protocol
        // facts, not tuning knobs: 401/403 end the run, 429/5xx are transient.
        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_FORBIDDEN = 403
        private const val HTTP_TOO_MANY_REQUESTS = 429
        private const val HTTP_SERVER_ERROR = 500
        private const val HTTP_OK_MIN = 200
        private const val HTTP_OK_MAX = 299
    }
}
