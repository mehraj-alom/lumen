package dev.lumen.core.agent

import com.google.common.truth.Truth.assertThat
import dev.lumen.core.agent.fixtures.InMemoryApprovalSignal
import dev.lumen.core.agent.fixtures.ScriptedEnvironment
import dev.lumen.core.brain.BrainReply
import dev.lumen.core.brain.BrainRoute
import dev.lumen.core.brain.Critique
import dev.lumen.core.brain.fixtures.FakeBrain
import dev.lumen.core.model.ApprovalState
import dev.lumen.core.model.Goal
import dev.lumen.core.model.ManualClock
import dev.lumen.core.model.RiskClass
import dev.lumen.core.model.RunId
import dev.lumen.core.model.UlidGenerator
import dev.lumen.core.model.Verdict
import dev.lumen.core.model.fixtures.InMemoryStateStore
import dev.lumen.core.policy.ApprovalBroker
import dev.lumen.core.policy.PolicyEngine
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * The hundred-step acceptance scenario.
 *
 * This is the workload from `PRD.md` §5, run end to end against fakes. It is
 * the first point at which the pieces are an agent rather than a collection
 * of good parts.
 */
class HundredStepScenarioTest {

    private val goal = Goal(
        id = RunId("01JBX000000000000000000001"),
        text = "find the cheapest flight to Lisbon and send the itinerary",
    )

    /** The step numbers that carry a hitl gate. */
    private val paymentStep = 63
    private val publishStep = 79
    private val messageStep = 89

    private fun labelFor(step: Int): String = when (step) {
        paymentStep -> "Pay 382 EUR"
        publishStep -> "Publish note"
        messageStep -> "Send message"
        else -> "Continue"
    }

    private fun screens(): List<dev.lumen.core.model.Observation> = (1..100).map { step ->
        when (step) {
            paymentStep -> ScriptedEnvironment.paymentScreen()
            publishStep -> ScriptedEnvironment.publishScreen()
            messageStep -> ScriptedEnvironment.messageScreen()
            else -> ScriptedEnvironment.screen()
        }
    }

    private fun plan(): BrainReply = BrainReply(
        thought = "the plan for the journey",
        steps = (1..100).map { step ->
            FakeBrain.tapStep("step $step", labelFor(step))
        },
    )

    @Test
    fun `L-100-01 the scenario reaches completion`() = runTest {
        val store = InMemoryStateStore()
        val clock = ManualClock(1_000L)
        val environment = ScriptedEnvironment(screens())
        val brain = FakeBrain(BrainRoute.LOCAL).apply {
            planReply = plan()
            critiqueReply = Critique(Verdict.CONTINUE, "the screen changed as expected")
        }

        val runtime = AgentRuntime(
            brain = brain,
            environment = environment,
            policy = PolicyEngine(),
            approvals = ApprovalBroker(store, clock),
            store = store,
            approvalSignal = InMemoryApprovalSignal(store),
            ids = UlidGenerator(clock = clock),
            clock = { clock.nowMs() },
        )

        val approver = launch {
            while (true) {
                store.loadPendingApprovals(goal.id).forEach { record ->
                    store.saveApproval(record.copy(state = ApprovalState.APPROVED))
                }
                delay(10)
            }
        }

        val outcome = runtime.run(goal)

        approver.cancel()
        assertThat(outcome).isInstanceOf(RunOutcome.Completed::class.java)
    }

    @Test
    fun `L-100-02 the scenario performs every step and records every observation`() = runTest {
        val store = InMemoryStateStore()
        val clock = ManualClock(1_000L)
        val environment = ScriptedEnvironment(screens())
        val brain = FakeBrain(BrainRoute.LOCAL).apply {
            planReply = plan()
        }

        val runtime = AgentRuntime(
            brain = brain,
            environment = environment,
            policy = PolicyEngine(),
            approvals = ApprovalBroker(store, clock),
            store = store,
            approvalSignal = InMemoryApprovalSignal(store),
            ids = UlidGenerator(clock = clock),
            clock = { clock.nowMs() },
        )

        val approver = launch {
            while (true) {
                store.loadPendingApprovals(goal.id).forEach { record ->
                    store.saveApproval(record.copy(state = ApprovalState.APPROVED))
                }
                delay(10)
            }
        }

        runtime.run(goal)

        approver.cancel()
        assertThat(environment.performed).hasSize(100)
        assertThat(store.observations).hasSize(100)
    }

    @Test
    fun `L-100-05 three separate approvals were staged`() = runTest {
        val store = InMemoryStateStore()
        val clock = ManualClock(1_000L)
        val environment = ScriptedEnvironment(screens())
        val brain = FakeBrain(BrainRoute.LOCAL).apply {
            planReply = plan()
        }

        val runtime = AgentRuntime(
            brain = brain,
            environment = environment,
            policy = PolicyEngine(),
            approvals = ApprovalBroker(store, clock),
            store = store,
            approvalSignal = InMemoryApprovalSignal(store),
            ids = UlidGenerator(clock = clock),
            clock = { clock.nowMs() },
        )

        val approver = launch {
            while (true) {
                store.loadPendingApprovals(goal.id).forEach { record ->
                    store.saveApproval(record.copy(state = ApprovalState.APPROVED))
                }
                delay(10)
            }
        }

        runtime.run(goal)

        approver.cancel()
        assertThat(store.approvals).hasSize(3)
        val classes = store.approvals.values.map { it.riskClass }
        assertThat(classes).contains(RiskClass.PAYMENT)
        assertThat(classes).contains(RiskClass.PUBLISH)
    }

    @Test
    fun `L-100-03 every step carries a reason`() = runTest {
        val store = InMemoryStateStore()
        val clock = ManualClock(1_000L)
        val environment = ScriptedEnvironment(screens())
        val brain = FakeBrain(BrainRoute.LOCAL).apply {
            planReply = plan()
        }

        val runtime = AgentRuntime(
            brain = brain,
            environment = environment,
            policy = PolicyEngine(),
            approvals = ApprovalBroker(store, clock),
            store = store,
            approvalSignal = InMemoryApprovalSignal(store),
            ids = UlidGenerator(clock = clock),
            clock = { clock.nowMs() },
        )

        val approver = launch {
            while (true) {
                store.loadPendingApprovals(goal.id).forEach { record ->
                    store.saveApproval(record.copy(state = ApprovalState.APPROVED))
                }
                delay(10)
            }
        }

        runtime.run(goal)

        approver.cancel()
        store.steps.values.forEach { step ->
            assertThat(step.reason.text.trim()).isNotEmpty()
        }
        assertThat(store.steps).hasSize(100)
    }

    @Test
    fun `L-100-07 the trace reconstructs the run`() = runTest {
        val store = InMemoryStateStore()
        val clock = ManualClock(1_000L)
        val environment = ScriptedEnvironment(screens())
        val brain = FakeBrain(BrainRoute.LOCAL).apply {
            planReply = plan()
        }

        val runtime = AgentRuntime(
            brain = brain,
            environment = environment,
            policy = PolicyEngine(),
            approvals = ApprovalBroker(store, clock),
            store = store,
            approvalSignal = InMemoryApprovalSignal(store),
            ids = UlidGenerator(clock = clock),
            clock = { clock.nowMs() },
        )

        val approver = launch {
            while (true) {
                store.loadPendingApprovals(goal.id).forEach { record ->
                    store.saveApproval(record.copy(state = ApprovalState.APPROVED))
                }
                delay(10)
            }
        }

        runtime.run(goal)

        approver.cancel()
        val storedPlan = store.loadPlan(goal.id)
        assertThat(storedPlan?.steps).hasSize(100)
        storedPlan?.steps?.forEach { step ->
            assertThat(step.state).isEqualTo(dev.lumen.core.model.StepState.COMPLETE)
        }
    }
}
