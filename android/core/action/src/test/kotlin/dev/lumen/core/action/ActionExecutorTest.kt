package dev.lumen.core.action

import com.google.common.truth.Truth.assertThat
import dev.lumen.core.action.fixtures.FakeActionExecutor
import dev.lumen.core.action.fixtures.FakeLedgerSink
import dev.lumen.core.model.Action
import dev.lumen.core.model.ErrorCode
import dev.lumen.core.model.Observation
import dev.lumen.core.model.Reason
import dev.lumen.core.model.RelativePoint
import dev.lumen.core.model.RunId
import dev.lumen.core.model.StepId
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ActionValidatorTest {

    @Test
    fun `T-DSP-07 a package that is not on the allowlist is refused`() {
        val validator = ActionValidator(PackageAllowlist(setOf("com.example.allowed")))

        val error = validator.validate(Action.OpenApp("com.example.other"), null)

        assertThat(error).isNotNull()
        assertThat(error?.code).isEqualTo(ErrorCode.DENIED_BY_POLICY)
    }

    @Test
    fun `a package on the allowlist is permitted`() {
        val validator = ActionValidator(PackageAllowlist(setOf("com.example.allowed")))

        val error = validator.validate(Action.OpenApp("com.example.allowed"), null)

        assertThat(error).isNull()
    }

    @Test
    fun `the allowlist is empty by default`() {
        val validator = ActionValidator()

        val error = validator.validate(Action.OpenApp("com.example.any"), null)

        assertThat(error).isNotNull()
    }

    @Test
    fun `T-DSP-08 a foreground package outside the allowlist stops a gesture`() {
        val validator = ActionValidator(PackageAllowlist(setOf("com.example.allowed")))

        val error = validator.validate(
            Action.Tap(RelativePoint(0.5), RelativePoint(0.5)),
            foregroundPackage = "com.example.other",
        )

        assertThat(error).isNotNull()
        assertThat(error?.code).isEqualTo(ErrorCode.DENIED_BY_POLICY)
    }

    @Test
    fun `a gesture on an allowed foreground package is permitted`() {
        val validator = ActionValidator(PackageAllowlist(setOf("com.example.allowed")))

        val error = validator.validate(
            Action.Tap(RelativePoint(0.5), RelativePoint(0.5)),
            foregroundPackage = "com.example.allowed",
        )

        assertThat(error).isNull()
    }

    @Test
    fun `a gesture with no foreground app is permitted`() {
        val validator = ActionValidator(PackageAllowlist(setOf("com.example.allowed")))

        val error = validator.validate(
            Action.Tap(RelativePoint(0.5), RelativePoint(0.5)),
            foregroundPackage = null,
        )

        assertThat(error).isNull()
    }

    @Test
    fun `an unknown url scheme is refused`() {
        val validator = ActionValidator(PackageAllowlist(setOf("com.example.allowed")))

        val error = validator.validate(Action.OpenUrl("file:///etc/passwd"), null)

        assertThat(error).isNotNull()
        assertThat(error?.code).isEqualTo(ErrorCode.DENIED_BY_POLICY)
    }

    @Test
    fun `an http url is permitted`() {
        val validator = ActionValidator(PackageAllowlist(setOf("com.example.allowed")))

        val error = validator.validate(Action.OpenUrl("https://example.com"), null)

        assertThat(error).isNull()
    }
}

class ActionExecutorTest {

    private val runId = RunId("01JBX000000000000000000001")
    private val stepId = StepId("01JBX000000000000000000002")

    @Test
    fun `T-DSP-04 every action goes through the one funnel`() = runTest {
        val delegate = FakeActionExecutor()
        val executor = LedgeredActionExecutor(
            delegate = delegate,
            ledger = ActionLedger(FakeLedgerSink()),
            runId = runId,
            stepId = stepId,
            preState = { "before" },
            startedAtMs = 0L,
        )

        executor.dispatch(Action.Tap(RelativePoint(0.5), RelativePoint(0.5)))
        executor.dispatch(Action.PressEnter)

        assertThat(delegate.dispatched).hasSize(2)
    }

    @Test
    fun `T-DSP-10 an action is not dispatched when the ledger fails`() = runTest {
        val delegate = FakeActionExecutor()
        val sink = FakeLedgerSink().apply { failing = true }
        val executor = LedgeredActionExecutor(
            delegate = delegate,
            ledger = ActionLedger(sink),
            runId = runId,
            stepId = stepId,
            preState = { "before" },
            startedAtMs = 0L,
        )

        val result = executor.dispatch(Action.Tap(RelativePoint(0.5), RelativePoint(0.5)))

        assertThat(result.ok).isFalse()
        assertThat(result.error?.code).isEqualTo(ErrorCode.INTERNAL)
        assertThat(delegate.dispatched).isEmpty()
    }

    @Test
    fun `the pre-state is recorded before the action runs`() = runTest {
        val delegate = FakeActionExecutor()
        val sink = FakeLedgerSink()
        val executor = LedgeredActionExecutor(
            delegate = delegate,
            ledger = ActionLedger(sink),
            runId = runId,
            stepId = stepId,
            preState = { "the screen before" },
            startedAtMs = 1234L,
        )

        executor.dispatch(Action.PressEnter)

        assertThat(sink.entries).hasSize(1)
        val entry = sink.entries.first()
        assertThat(entry.preState).isEqualTo("the screen before")
        assertThat(entry.action).isEqualTo(Action.PressEnter)
        assertThat(entry.runId).isEqualTo(runId)
        assertThat(entry.stepId).isEqualTo(stepId)
        assertThat(entry.startedAtMs).isEqualTo(1234L)
    }

    @Test
    fun `the ledger assigns a monotonic sequence`() = runTest {
        val sink = FakeLedgerSink()
        val ledger = ActionLedger(sink)

        ledger.record(entry("a"))
        ledger.record(entry("b"))

        assertThat(sink.entries.map { it.seq }).containsExactly(0L, 1L).inOrder()
    }

    @Test
    fun `a failing ledger reports that it is unhealthy`() = runTest {
        val sink = FakeLedgerSink().apply { failing = true }
        val ledger = ActionLedger(sink)

        assertThat(ledger.isHealthy()).isFalse()
    }

    @Test
    fun `a dispatch failure is reported as a failed result`() = runTest {
        val delegate = FakeActionExecutor().apply {
            failure = ErrorCode.IDLE_GATE_BLOCKED
            failureCount = 1
        }
        val executor = LedgeredActionExecutor(
            delegate = delegate,
            ledger = ActionLedger(FakeLedgerSink()),
            runId = runId,
            stepId = stepId,
            preState = { null },
            startedAtMs = 0L,
        )

        val result = executor.dispatch(Action.PressEnter)

        assertThat(result.ok).isFalse()
        assertThat(result.error?.code).isEqualTo(ErrorCode.IDLE_GATE_BLOCKED)
    }

    @Test
    fun `T-DSP-03 the observation travels with the result`() = runTest {
        val observation = Observation(app = "com.example.app", nodes = emptyList())
        val delegate = FakeActionExecutor(observation)
        val executor = LedgeredActionExecutor(
            delegate = delegate,
            ledger = ActionLedger(FakeLedgerSink()),
            runId = runId,
            stepId = stepId,
            preState = { null },
            startedAtMs = 0L,
        )

        val result = executor.dispatch(Action.PressEnter)

        assertThat(result.ok).isTrue()
        assertThat(result.observation).isEqualTo(observation)
    }

    @Test
    fun `T-DSP-02 a coordinate outside the unit range never reaches the executor`() {
        val failure = runCatching { RelativePoint(1.5) }.exceptionOrNull()

        assertThat(failure).isNotNull()
    }

    private fun entry(reason: String) = ActionLedgerEntry(
        runId = runId,
        stepId = stepId,
        seq = 0L,
        action = Action.PressEnter,
        reason = Reason.planner(reason),
        preState = null,
        startedAtMs = 0L,
    )
}
