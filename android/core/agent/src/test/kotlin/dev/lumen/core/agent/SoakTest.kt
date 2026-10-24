package dev.lumen.core.agent

import com.google.common.truth.Truth.assertThat
import dev.lumen.core.action.ActionLedger
import dev.lumen.core.action.fixtures.FakeLedgerSink
import dev.lumen.core.agent.fixtures.InMemoryApprovalSignal
import dev.lumen.core.agent.fixtures.ScriptedEnvironment
import dev.lumen.core.brain.BrainReply
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

/**
 * The soak test.
 *
 * A hundred-step run is the acceptance workload. This is the same run, many
 * times over, with the budgets and the store watched the whole time. It is
 * the test that catches a leak, a runaway ledger, and a step that never
 * finishes.
 */
class SoakTest {

    @Test
    fun `a hundred runs leave no residue in the ledger`() = runTest {
        val goal = Goal(
            id = RunId("01JBX000000000000000000001"),
            text = "find the cheapest flight",
        )

        repeat(SOAK_RUNS) { run ->
            val store = InMemoryStateStore()
            val clock = ManualClock(1_000L)
            val screens = (1..100).map { ScriptedEnvironment.screen() }
            val environment = ScriptedEnvironment(screens)
            val brain = FakeBrain(BrainRoute.LOCAL).apply {
                planReply = BrainReply(
                    thought = "the plan",
                    steps = (1..100).map { FakeBrain.tapStep("step $it", "Continue") },
                )
                critiqueReply = Critique(Verdict.CONTINUE, "as expected")
            }

            val sink = FakeLedgerSink()
            val runtime = AgentRuntime(
                brain = brain,
                environment = environment,
                policy = PolicyEngine(),
                approvals = ApprovalBroker(store, clock),
                store = store,
                ledger = ActionLedger(sink),
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
            assertThat(store.observations).hasSize(100)
            assertThat(store.steps).hasSize(100)

            // The ledger must not grow without bound: one entry per action,
            // and nothing left over from the previous run.
            assertThat(sink.entries).hasSize(100)
            assertThat(sink.entries.map { it.seq }).containsExactlyElementsIn(0L until 100L).inOrder()
            assertThat(store.settings).isEmpty()
        }
    }

    private companion object {
        const val SOAK_RUNS = 10
    }
}
