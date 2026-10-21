package dev.lumen.core.brain

import com.google.common.truth.Truth.assertThat
import dev.lumen.core.model.Goal
import dev.lumen.core.model.NodeRef
import dev.lumen.core.model.Observation
import dev.lumen.core.model.RunId
import dev.lumen.core.protocol.ContentEgressGuard
import dev.lumen.core.protocol.EgressDenied
import kotlinx.coroutines.test.runTest
import org.junit.Test

private class FakeTransport(var response: HttpResponse) : HttpTransport {
    val requests = mutableListOf<Pair<String, String>>()

    override suspend fun post(url: String, headers: Map<String, String>, body: String): HttpResponse {
        requests += url to body
        return response
    }
}

class CloudBrainTest {

    private val goal = Goal(id = RunId("01JBX0000000000000000000001"), text = "do the thing")

    private fun completion(content: String): String =
        """{"choices":[{"message":{"content":${content.jsonEscape()}}}]}"""

    private fun String.jsonEscape(): String = "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun brain(transport: FakeTransport, egress: ContentEgressGuard = ContentEgressGuard()) =
        CloudBrain(
            transport = transport,
            baseUrl = "https://brain.example/v1",
            apiKey = "key",
            modelId = "pinned-model",
            egress = egress,
            retry = RetryPolicy(maxAttempts = 1),
        )

    @Test
    fun `a well formed reply becomes a plan`() = runTest {
        val plan = """{"plan_version":1,"thought":"the plan","steps":[{"seq":1,"intent":"tap pay","reason":"because","action":{"tool":"click_text","args":{"text":"Pay"}},"risk_class":"none"}]}"""
        val transport = FakeTransport(HttpResponse(200, completion(plan)))

        val reply = brain(transport).plan(PlanRequest(goal = goal))

        assertThat(reply.steps).hasSize(1)
        assertThat(reply.steps.first().intent).isEqualTo("tap pay")
        assertThat(reply.thought).isEqualTo("the plan")
    }

    @Test
    fun `a reply that is not json fails the step`() = runTest {
        val transport = FakeTransport(HttpResponse(200, "not json at all"))

        val failure = runCatching { brain(transport).plan(PlanRequest(goal = goal)) }.exceptionOrNull()

        assertThat(failure).isInstanceOf(MalformedBrainReply::class.java)
    }

    @Test
    fun `a reply with an unknown tool fails the step`() = runTest {
        val plan = """{"plan_version":1,"thought":"t","steps":[{"seq":1,"intent":"i","reason":"r","action":{"tool":"launch_missiles","args":{}}}]}"""
        val transport = FakeTransport(HttpResponse(200, completion(plan)))

        val failure = runCatching { brain(transport).plan(PlanRequest(goal = goal)) }.exceptionOrNull()

        assertThat(failure).isInstanceOf(MalformedBrainReply::class.java)
    }

    @Test
    fun `a reply with bad arguments fails the step`() = runTest {
        val plan = """{"plan_version":1,"thought":"t","steps":[{"seq":1,"intent":"i","reason":"r","action":{"tool":"tap","args":{"x":4.0,"y":0.5}}}]}"""
        val transport = FakeTransport(HttpResponse(200, completion(plan)))

        val failure = runCatching { brain(transport).plan(PlanRequest(goal = goal)) }.exceptionOrNull()

        assertThat(failure).isInstanceOf(MalformedBrainReply::class.java)
    }

    @Test
    fun `an unauthorised reply fails`() = runTest {
        val transport = FakeTransport(HttpResponse(401, "no"))

        val failure = runCatching { brain(transport).plan(PlanRequest(goal = goal)) }.exceptionOrNull()

        assertThat(failure).isInstanceOf(MalformedBrainReply::class.java)
    }

    @Test
    fun `screen text does not leave the device when spectating is off`() = runTest {
        val transport = FakeTransport(HttpResponse(200, completion("""{"plan_version":1,"thought":"t","steps":[]}""")))
        val observation = Observation(
            app = "com.example.app",
            nodes = listOf(NodeRef(id = 0, className = "android.widget.TextView", text = "secret screen text")),
        )

        val failure = runCatching {
            brain(transport, ContentEgressGuard(spectatingEnabled = false))
                .plan(PlanRequest(goal = goal, observations = listOf(observation)))
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(EgressDenied::class.java)
    }

    @Test
    fun `screen text leaves the device when spectating is on`() = runTest {
        val transport = FakeTransport(HttpResponse(200, completion("""{"plan_version":1,"thought":"t","steps":[]}""")))
        val observation = Observation(
            app = "com.example.app",
            nodes = listOf(NodeRef(id = 0, className = "android.widget.TextView", text = "visible text")),
        )

        val reply = brain(transport, ContentEgressGuard(spectatingEnabled = true))
            .plan(PlanRequest(goal = goal, observations = listOf(observation)))

        assertThat(reply.thought).isNotEmpty()
        assertThat(transport.requests).hasSize(1)
        assertThat(transport.requests.first().first).isEqualTo("https://brain.example/v1/chat/completions")
    }

    @Test
    fun `the pinned model is sent with the request`() = runTest {
        val transport = FakeTransport(HttpResponse(200, completion("""{"plan_version":1,"thought":"t","steps":[]}""")))

        brain(transport).plan(PlanRequest(goal = goal))

        assertThat(transport.requests.first().second).contains("pinned-model")
    }
}

class RetryPolicyTest {

    @Test
    fun `a failing call is retried until it succeeds`() = runTest {
        val policy = RetryPolicy(maxAttempts = 3, initialDelayMs = 10)
        var attempts = 0

        val result = policy.execute { attempt ->
            attempts++
            if (attempt < 3) throw IllegalStateException("not yet")
            "ok"
        }

        assertThat(result).isEqualTo("ok")
        assertThat(attempts).isEqualTo(3)
    }

    @Test
    fun `the attempts run out and the last error is thrown`() = runTest {
        val policy = RetryPolicy(maxAttempts = 2, initialDelayMs = 10)

        val failure = runCatching {
            policy.execute { throw IllegalStateException("always") }
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalStateException::class.java)
        assertThat(failure?.message).isEqualTo("always")
    }

    @Test
    fun `a failure that is not retryable is thrown at once`() = runTest {
        val policy = RetryPolicy(maxAttempts = 5, initialDelayMs = 10)
        var attempts = 0

        runCatching {
            policy.execute {
                attempts++
                throw IllegalStateException("fatal")
            }
        }

        assertThat(attempts).isEqualTo(1)
    }

    @Test
    fun `the backoff grows and is capped`() {
        val policy = RetryPolicy(initialDelayMs = 100, multiplier = 2.0, maxDelayMs = 400)

        assertThat(policy.delayFor(1)).isEqualTo(100L)
        assertThat(policy.delayFor(2)).isEqualTo(200L)
        assertThat(policy.delayFor(3)).isEqualTo(400L)
        assertThat(policy.delayFor(4)).isEqualTo(400L)
    }
}
