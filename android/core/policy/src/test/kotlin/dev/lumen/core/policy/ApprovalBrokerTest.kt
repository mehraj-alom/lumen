package dev.lumen.core.policy

import com.google.common.truth.Truth.assertThat
import dev.lumen.core.model.ActionPreview
import dev.lumen.core.model.ApprovalId
import dev.lumen.core.model.ApprovalRequest
import dev.lumen.core.model.ApprovalState
import dev.lumen.core.model.ManualClock
import dev.lumen.core.model.RiskClass
import dev.lumen.core.model.RunId
import dev.lumen.core.model.StepId
import dev.lumen.core.model.fixtures.InMemoryStateStore
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ApprovalBrokerTest {

    private val runId = RunId("01JBX0000000000000000000001")
    private val otherRunId = RunId("01JBX0000000000000000000009")
    private val stepId = StepId("01JBX000000000000000000002")

    private val store = InMemoryStateStore()
    private val clock = ManualClock()

    private fun broker(leaseMs: Long = 60_000L) = ApprovalBroker(store = store, clock = clock, leaseMs = leaseMs)

    private fun request(runId: RunId = this.runId) = ApprovalRequest(
        runId = runId,
        approvalId = ApprovalId("placeholder"),
        stepId = stepId,
        riskClass = RiskClass.PAYMENT,
        title = "Send a payment",
        detail = "The agent wants to confirm the purchase.",
        app = "com.example.shop",
        appLabel = "Shop",
        consequences = listOf("Money leaves the account."),
        actionPreview = ActionPreview(kind = "tap", label = "Pay now"),
        expiresAtMs = clock.nowMs() + 60_000L,
        leaseSeconds = 60,
    )

    @Test
    fun `a stop closes every open gate of the run denied`() = runTest {
        val broker = broker()
        val first = broker.stage(request())
        val second = broker.stage(request())

        val closed = broker.expireOpen(runId)

        assertThat(closed).containsExactly(first, second)
        assertThat(store.loadApproval(first)!!.state).isEqualTo(ApprovalState.EXPIRED)
        assertThat(store.loadApproval(second)!!.state).isEqualTo(ApprovalState.EXPIRED)
        assertThat(store.loadPendingApprovals(runId)).isEmpty()
    }

    @Test
    fun `a closed gate answers to nothing`() = runTest {
        val broker = broker()
        val id = broker.stage(request())
        broker.expireOpen(runId)

        // The stop must not leave a door open: an answer arriving afterwards —
        // from a card the console had not yet dropped — must not run the step.
        assertThat(broker.resolve(id, ApprovalDecision.Approve)).isFalse()
        assertThat(store.loadApproval(id)!!.state).isEqualTo(ApprovalState.EXPIRED)
    }

    @Test
    fun `a gate that was already answered keeps its answer`() = runTest {
        val broker = broker()
        val id = broker.stage(request())
        broker.resolve(id, ApprovalDecision.Approve)

        assertThat(broker.expireOpen(runId)).isEmpty()
        assertThat(store.loadApproval(id)!!.state).isEqualTo(ApprovalState.APPROVED)
    }

    @Test
    fun `a stop closes only the gates of the run it stopped`() = runTest {
        val broker = broker()
        val mine = broker.stage(request())
        val theirs = broker.stage(request(runId = otherRunId))

        assertThat(broker.expireOpen(runId)).containsExactly(mine)
        assertThat(store.loadApproval(theirs)!!.state).isEqualTo(ApprovalState.PENDING)
    }
}
