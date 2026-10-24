package dev.lumen.core.agent

import com.google.common.truth.Truth.assertThat
import dev.lumen.core.action.ActionLedger
import dev.lumen.core.action.ActionLedgerEntry
import dev.lumen.core.action.LedgerSink
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
import dev.lumen.core.model.ReasonSource
import dev.lumen.core.model.RunId
import dev.lumen.core.model.UlidGenerator
import dev.lumen.core.model.Verdict
import dev.lumen.core.model.fixtures.InMemoryStateStore
import dev.lumen.core.policy.ApprovalBroker
import dev.lumen.core.policy.PolicyEngine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Test

class AgentRuntimeTest {

    private val goal = Goal(
        id = RunId("01JBX000000000000000000001"),
        text = "find the cheapest flight",
    )

    private fun runtime(
        brain: FakeBrain,
        environment: ScriptedEnvironment,
        store: InMemoryStateStore,
        clock: ManualClock,
        config: RunConfig = RunConfig(),
        ledger: ActionLedger = ActionLedger(FakeLedgerSink()),
    ): AgentRuntime = AgentRuntime(
        brain = brain,
        environment = environment,
        policy = PolicyEngine(),
        approvals = ApprovalBroker(store, clock),
        store = store,
        ledger = ledger,
        approvalSignal = InMemoryApprovalSignal(store),
        ids = UlidGenerator(clock = clock),
        config = config,
        abort = AbortSignal(),
        clock = { clock.nowMs() },
    )

    @Test
    fun `an empty plan is a failed run, not a satisfied goal`() = runTest {
        val store = InMemoryStateStore()
        val clock = ManualClock(1_000L)
        val environment = ScriptedEnvironment(listOf(ScriptedEnvironment.screen()))
        val brain = FakeBrain(BrainRoute.LOCAL).apply {
            planReply = BrainReply(thought = "nothing to do", steps = emptyList())
        }

        val outcome = runtime(brain, environment, store, clock).run(goal)

        // A run that acted on nothing has not satisfied anything, and the
        // trace has to say so rather than report success (invariant I9).
        assertThat(outcome).isInstanceOf(RunOutcome.Failed::class.java)
        assertThat(environment.performed).isEmpty()
    }

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
            assertThat(step.reason.text.trim()).isNotEmpty()
        }
    }

    @Test
    fun `T-DSP-11 an action is recorded before it reaches the device`() = runTest {
        val store = InMemoryStateStore()
        val clock = ManualClock(1_000L)
        val environment = ScriptedEnvironment(listOf(ScriptedEnvironment.screen()))
        val dispatchedWhenRecorded = mutableListOf<Int>()
        val sink = object : LedgerSink {
            override suspend fun append(entry: ActionLedgerEntry) {
                // What the device had been asked to do at the moment the entry
                // was written: zero means the record preceded the dispatch.
                dispatchedWhenRecorded += environment.performed.size
            }

            override suspend fun isHealthy(): Boolean = true
        }
        val brain = FakeBrain(BrainRoute.LOCAL).apply {
            planReply = FakeBrain.planWith(FakeBrain.tapStep("tap continue", "Continue"))
        }

        runtime(brain, environment, store, clock, ledger = ActionLedger(sink)).run(goal)

        assertThat(dispatchedWhenRecorded).containsExactly(0)
    }

    @Test
    fun `the ledger records the screen the action was taken from`() = runTest {
        val store = InMemoryStateStore()
        val clock = ManualClock(1_000L)
        val environment = ScriptedEnvironment(
            listOf(ScriptedEnvironment.screen(app = "com.example.browser"), ScriptedEnvironment.screen(app = "com.example.notes")),
        )
        val sink = FakeLedgerSink()
        val brain = FakeBrain(BrainRoute.LOCAL).apply {
            planReply = FakeBrain.planWith(FakeBrain.tapStep("tap continue", "Continue"))
        }

        runtime(brain, environment, store, clock, ledger = ActionLedger(sink)).run(goal)

        val entry = sink.entries.single()
        // The pre-state is the screen that was in front when the step was
        // chosen, not the one the action produced (invariant I6).
        assertThat(entry.preState).contains("app=com.example.browser")
        assertThat(entry.preState).doesNotContain("com.example.notes")
        // The step's own reason travels with the action (invariant I9).
        assertThat(entry.reason.source).isEqualTo(ReasonSource.PLANNER)
        assertThat(entry.runId).isEqualTo(goal.id)
    }

    @Test
    fun `an action that cannot be recorded is never dispatched`() = runTest {
        val store = InMemoryStateStore()
        val clock = ManualClock(1_000L)
        val environment = ScriptedEnvironment(listOf(ScriptedEnvironment.screen()))
        val sink = FakeLedgerSink().apply { failing = true }
        val brain = FakeBrain(BrainRoute.LOCAL).apply {
            planReply = FakeBrain.planWith(FakeBrain.tapStep("tap continue", "Continue"))
        }

        val outcome = runtime(brain, environment, store, clock, ledger = ActionLedger(sink)).run(goal)

        // Fail closed: an unrecorded action is an unauditable action.
        assertThat(outcome).isInstanceOf(RunOutcome.Failed::class.java)
        assertThat(environment.performed).isEmpty()
    }

    @Test
    fun `T-DSP-12 a cancellation during the ledger write cancels the run`() = runTest {
        val store = InMemoryStateStore()
        val clock = ManualClock(1_000L)
        val environment = ScriptedEnvironment(listOf(ScriptedEnvironment.screen()))
        val insideTheWrite = CompletableDeferred<Unit>()
        val sink = object : LedgerSink {
            override suspend fun append(entry: ActionLedgerEntry) {
                insideTheWrite.complete(Unit)
                awaitCancellation()
            }

            override suspend fun isHealthy(): Boolean = true
        }
        val brain = FakeBrain(BrainRoute.LOCAL).apply {
            planReply = FakeBrain.planWith(FakeBrain.tapStep("tap continue", "Continue"))
        }
        val runtime = runtime(brain, environment, store, clock, ledger = ActionLedger(sink))

        val run = launch { runtime.run(goal) }
        insideTheWrite.await()
        run.cancelAndJoin()

        // The abort surfaces as cancellation of the run; it is not turned into
        // a failed action, and the device was never touched.
        assertThat(run.isCancelled).isTrue()
        assertThat(environment.performed).isEmpty()
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
            critiqueReplies = listOf(
                Critique(Verdict.REPLAN, "the screen did not change"),
                Critique(Verdict.CONTINUE, "the revised step worked"),
            )
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
            ledger = ActionLedger(FakeLedgerSink()),
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
