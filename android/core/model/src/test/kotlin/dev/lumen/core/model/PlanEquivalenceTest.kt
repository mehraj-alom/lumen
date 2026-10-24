package dev.lumen.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlanEquivalenceTest {

    @Test
    fun `two plans that differ only in timestamps are equivalent`() {
        val left = plan(createdAtMs = 1_000L, updatedAtMs = 2_000L)
        val right = plan(createdAtMs = 9_000L, updatedAtMs = 9_500L)

        assertThat(PlanEquivalence.equivalent(left, right)).isTrue()
    }

    @Test
    fun `two plans that differ in an action are not equivalent`() {
        val left = plan()
        val right = plan().copy(
            steps = listOf(baseStep().copy(action = Action.ClickText("Pay"))),
        )

        assertThat(PlanEquivalence.equivalent(left, right)).isFalse()
    }

    @Test
    fun `two plans that differ in a reason are not equivalent`() {
        val left = plan()
        val right = plan().copy(
            steps = listOf(baseStep().copy(reason = Reason.critic("a different reason"))),
        )

        assertThat(PlanEquivalence.equivalent(left, right)).isFalse()
    }

    @Test
    fun `two plans that differ in a dependency are not equivalent`() {
        val left = plan()
        val right = plan().copy(
            steps = listOf(baseStep().copy(dependsOn = listOf(StepId(sampleId(9))))),
        )

        assertThat(PlanEquivalence.equivalent(left, right)).isFalse()
    }

    @Test
    fun `plans of different versions are not equivalent`() {
        val left = plan()
        val right = left.copy(version = 2)

        assertThat(PlanEquivalence.equivalent(left, right)).isFalse()
    }

    @Test
    fun `the assertion reports the first difference`() {
        val left = plan()
        val right = plan().copy(steps = listOf(baseStep().copy(intent = "Open the payment sheet")))

        val failure = runCatching { PlanEquivalence.assertEquivalent(left, right) }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(failure?.message).contains("differ at step 0")
    }

    @Test
    fun `the assertion passes for equivalent plans`() {
        val failure = runCatching {
            PlanEquivalence.assertEquivalent(plan(createdAtMs = 1L), plan(createdAtMs = 2L))
        }.exceptionOrNull()

        assertThat(failure).isNull()
    }

    private fun sampleId(index: Int): String {
        val alphabet = Ulid.ENCODING.take(Ulid.LENGTH)
        return alphabet.dropLast(1) + alphabet[index % alphabet.length]
    }

    private fun baseStep(): Step = Step(
        id = StepId(sampleId(1)),
        planVersion = 1,
        phase = "research",
        intent = "Open the results list",
        reason = Reason.planner("Prices are only visible on the results page"),
        action = Action.ClickText("Prices"),
        expectation = Assertion.TextPresent("Total price"),
        riskClass = RiskClass.NONE,
    )

    private fun plan(createdAtMs: Long = 0L, updatedAtMs: Long = 0L): Plan = Plan(
        runId = RunId(sampleId(0)),
        version = 1,
        steps = listOf(
            baseStep().copy(createdAtMs = createdAtMs, updatedAtMs = updatedAtMs),
        ),
        createdAtMs = createdAtMs,
    )
}
