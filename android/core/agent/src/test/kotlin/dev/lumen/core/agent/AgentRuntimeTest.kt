package dev.lumen.core.agent

import com.google.common.truth.Truth.assertThat
import dev.lumen.core.agent.fixtures.InMemoryApprovalSignal
import dev.lumen.core.agent.fixtures.ScriptedEnvironment
import dev.lumen.core.brain.BrainRoute
import dev.lumen.core.brain.Critique
import dev.lumen.core.brain.fixtures.FakeBrain
import dev.lumen.core.model.ApprovalState
import dev.lumen.core.model.Goal
import dev.lumen.core.model.ManualClock
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

class AgentRuntimeTest {

    private val goal = Goal(
        id = RunId("01JBX0000000000000000000001"),
        text = "find the cheapest flight",
    )

    private fun runtime(
        brain: FakeBrain,
        environment: ScriptedEnvironment,
        store: InMemoryStateStore,
        clock: ManualClock,
        config: RunConfig = RunConfig(),
    ): AgentRuntime = AgentRuntime(
        brain = brain,
        environment = environment,
        policy = PolicyEngine(),
        approvals = ApprovalBroker(store, clock),
        store = store,
        approvalSignal = InMemoryApprovalSignal(store),
        ids = UlidGenerator(clock = clock),
        config = config,
        abort = AbortSignal(),
        clock = { clock.nowMs() },
    )

    @Test
    fun `a run executes the plan and completes`() = runTest {
        val store = InMemoryStateStore()
        val clock = ManualClock(1_000L)
        val environment = ScriptedEnvironment(listOf(ScriptedEnvironment.screen()))
        val brain = FakeBrain(BrainRoute.LOCAL).apply {
            planReply = FakeBrain.planWith(FakeBrain.tapStep("tap continue", "Continue"))
            critiqueReply = Critique(Verdict.CONTINUE, "the screen changed as expected")
        }

        val outcome = runtime(brain, environment, store, clock).run(goal)

        assertThat(outcome).isInstanceOf(RunOutcome.Completed::class.java)
        assertThat(environment.performed).hasSize(1)
        assertThat(store.observations).hasSize(1)
    }

    @Test
    fun `every step records a reason`() = runTest {
        val store = InMemoryStateStore()
        val clock = ManualClock(1_000L)
        val environment = ScriptedEnvironment(listOf(ScriptedEnvironment.screen()))
        val brain = FakeBrain(BrainRoute.LOCAL).apply {
            planReply = FakeBrain.planWith(FakeBrain.tapStep("tap continue", "Continue"))
        }

        runtime(brain, environment, store, clock).run(goal)

        val plan = store.loadPlan(goal.id)
        assertThat(plan?.steps).isNotEmpty()
        plan?.steps?.forEach { step ->
            assertThat(step.reason.text).isNotBlank()
        }
    }

    @Test
    fun `a failing action ends the run`() = runTest {
        val store = InMemoryStateStore()
        val clock = ManualClock(1_000L)
        val environment = ScriptedEnvironment(listOf(ScriptedEnvironment.screen())).apply { failAt = 1 }
        val brain = FakeBrain(BrainRoute.LOCAL).apply {
            planReply = FakeBrain.planWith(FakeBrain.tapStep("tap continue", "Continue"))
        }

        val outcome = runtime(brain, environment, store, clock).run(goal)

        assertThat(outcome).isInstanceOf(RunOutcome.Failed::class.java)
    }

    @Test
    fun `a replan verdict revises the plan`() = runTest {
        val store = InMemoryStateStore()
        val clock = ManualClock(1_000L)
        val environment = ScriptedEnvironment(listOf(ScriptedEnvironment.screen()))
        val brain = FakeBrain(BrainRoute.LOCAL).apply {
            planReply = FakeBrain.planWith(FakeBrain.tapStep("tap continue", "Continue"))
            replanReply = FakeBrain.planWith(FakeBrain.tapStep("tap the other thing", "Other"))
            critiqueReply = Critique(Verdict.REPLAN, "the screen did not change")
        }

        val outcome = runtime(brain, environment, store, clock).run(goal)

        assertThat(brain.replanCalls).isEqualTo(1)
        assertThat(outcome).isInstanceOf(RunOutcome.Completed::class.java)
        assertThat(store.loadPlanHistory(goal.id)).hasSize(2)
    }

    @Test
    fun `the hard step limit stops the run`() = runTest {
        val store = InMemoryStateStore()
        val clock = ManualClock(1_000L)
        val environment = ScriptedEnvironment(listOf(ScriptedEnvironment.screen()))
        val brain = FakeBrain(BrainRoute.LOCAL).apply {
            planReply = FakeBrain.planWith(
                FakeBrain.tapStep("tap continue", "Continue"),
                FakeBrain.tapStep("tap continue again", "Continue"),
            )
            critiqueReply = Critique(Verdict.REPLAN, "never settles")
        }

        val outcome = runtime(brain, environment, store, clock, RunConfig(maxReplans = 2)).run(goal)

        assertThat(outcome).isInstanceOf(RunOutcome.Failed::class.java)
    }

    @Test
    fun `an approval is staged and the step waits for it`() = runTest {
        val store = InMemoryStateStore()
        val clock = ManualClock(1_000L)
        val environment = ScriptedEnvironment(
            listOf(ScriptedEnvironment.paymentScreen(), ScriptedEnvironment.screen()),
        )
        val brain = FakeBrain(BrainRoute.LOCAL).apply {
            planReply = FakeBrain.planWith(FakeBrain.tapStep("tap pay", "Pay 382 EUR"))
        }

        val runtime = runtime(brain, environment, store, clock)
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
        assertThat(store.approvals).hasSize(1)
        assertThat(store.approvals.values.first().riskClass)
            .isEqualTo(dev.lumen.core.model.RiskClass.PAYMENT)
    }

    @Test
    fun `a denied approval ends the run`() = runTest {
        val store = InMemoryStateStore()
        val clock = ManualClock(1_000L)
        val environment = ScriptedEnvironment(listOf(ScriptedEnvironment.paymentScreen()))
        val brain = FakeBrain(BrainRoute.LOCAL).apply {
            planReply = FakeBrain.planWith(FakeBrain.tapStep("tap pay", "Pay 382 EUR"))
        }

        val runtime = runtime(brain, environment, store, clock)
        val denier = launch {
            while (true) {
                store.loadPendingApprovals(goal.id).forEach { record ->
                    store.saveApproval(record.copy(state = ApprovalState.DENIED))
                }
                delay(10)
            }
        }

        val outcome = runtime.run(goal)

        denier.cancel()
        assertThat(outcome).isInstanceOf(RunOutcome.Failed::class.java)
        assertThat(environment.performed).isEmpty()
    }

    @Test
    fun `an abort stops the run before the next dispatch`() = runTest {
        val store = InMemoryStateStore()
        val clock = ManualClock(1_000L)
        val environment = ScriptedEnvironment(listOf(ScriptedEnvironment.screen()))
        val brain = FakeBrain(BrainRoute.LOCAL).apply {
            planReply = FakeBrain.planWith(
                FakeBrain.tapStep("one", "Continue"),
                FakeBrain.tapStep("two", "Continue"),
            )
        }
        val abort = AbortSignal()
        val runtime = AgentRuntime(
            brain = brain,
            environment = environment,
            policy = PolicyEngine(),
            approvals = ApprovalBroker(store, clock),
            store = store,
            approvalSignal = InMemoryApprovalSignal(store),
            ids = UlidGenerator(clock = clock),
            abort = abort,
            clock = { clock.nowMs() },
        )

        abort.abort()
        val outcome = runtime.run(goal)

        assertThat(outcome).isInstanceOf(RunOutcome.Aborted::class.java)
        assertThat(environment.performed).isEmpty()
    }
}
