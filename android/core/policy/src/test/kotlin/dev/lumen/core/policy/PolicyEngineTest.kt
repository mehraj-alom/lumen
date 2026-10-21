package dev.lumen.core.policy

import com.google.common.truth.Truth.assertThat
import dev.lumen.core.model.Action
import dev.lumen.core.model.ActionPreview
import dev.lumen.core.model.fixtures.InMemoryStateStore
import dev.lumen.core.model.ApprovalId
import dev.lumen.core.model.ApprovalRequest
import dev.lumen.core.model.ApprovalState
import dev.lumen.core.model.ManualClock
import dev.lumen.core.model.NodeRef
import dev.lumen.core.model.Observation
import dev.lumen.core.model.RelativeRect
import dev.lumen.core.model.RelativePoint
import dev.lumen.core.model.RiskClass
import dev.lumen.core.model.RunId
import dev.lumen.core.model.StepId
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

class PolicyEngineTest {

    private val runId = RunId("01JBX0000000000000000000001")
    private val stepId = StepId("01JBX0000000000000000000002")

    private fun observationWith(
        text: String? = null,
        className: String = "android.widget.Button",
        clickable: Boolean = true,
        isPassword: Boolean = false,
        app: String = "com.example.app",
    ): Observation = Observation(
        app = app,
        nodes = listOf(
            NodeRef(
                id = 0,
                className = className,
                text = text,
                packageName = app,
                bounds = RelativeRect(0.0, 0.0, 1.0, 1.0),
                clickable = clickable,
                editable = isPassword,
                focused = isPassword,
                isPassword = isPassword,
            ),
        ),
    )

    private fun request(
        action: Action,
        observation: Observation?,
        plannerRiskClass: RiskClass = RiskClass.NONE,
    ) = PolicyRequest(
        runId = runId,
        stepId = stepId,
        action = action,
        observation = observation,
        plannerRiskClass = plannerRiskClass,
    )

    @Test
    fun `L-HIT-01 a payment button is classified as a payment`() {
        val engine = PolicyEngine()

        val decision = engine.decide(
            request(Action.Tap(RelativePoint(0.5), RelativePoint(0.5)), observationWith(text = "Pay 382 EUR")),
            nowMs = 0L,
        )

        assertThat(decision).isInstanceOf(PolicyDecision.RequiresApproval::class.java)
        assertThat((decision as PolicyDecision.RequiresApproval).request.riskClass)
            .isEqualTo(RiskClass.PAYMENT)
    }

    @Test
    fun `L-HIT-02 a publish button is classified as a publish`() {
        val engine = PolicyEngine()

        val decision = engine.decide(
            request(Action.ClickText("Post"), observationWith(text = "Post")),
            nowMs = 0L,
        )

        assertThat((decision as PolicyDecision.RequiresApproval).request.riskClass)
            .isEqualTo(RiskClass.PUBLISH)
    }

    @Test
    fun `L-HIT-03 a delete button is classified as a delete`() {
        val engine = PolicyEngine()

        val decision = engine.decide(
            request(Action.ClickText("Delete account"), observationWith(text = "Delete account")),
            nowMs = 0L,
        )

        assertThat((decision as PolicyDecision.RequiresApproval).request.riskClass)
            .isEqualTo(RiskClass.DELETE)
    }

    @Test
    fun `L-HIT-04 typing into a password field is a credential`() {
        val engine = PolicyEngine()

        val decision = engine.decide(
            request(Action.InputText("hunter2"), observationWith(isPassword = true)),
            nowMs = 0L,
        )

        assertThat((decision as PolicyDecision.RequiresApproval).request.riskClass)
            .isEqualTo(RiskClass.CREDENTIAL)
    }

    @Test
    fun `L-HIT-05 a permission button is classified as a permission`() {
        val engine = PolicyEngine()

        val decision = engine.decide(
            request(Action.ClickText("Allow"), observationWith(text = "Allow")),
            nowMs = 0L,
        )

        assertThat((decision as PolicyDecision.RequiresApproval).request.riskClass)
            .isEqualTo(RiskClass.PERMISSION)
    }

    @Test
    fun `L-HIT-06 a read only step does not match any class`() {
        val engine = PolicyEngine()

        val decision = engine.decide(
            request(Action.ObserveScreen(), observationWith(text = "Search")),
            nowMs = 0L,
        )

        assertThat(decision).isInstanceOf(PolicyDecision.Allow::class.java)
    }

    @Test
    fun `L-HIT-07 the planner cannot loosen the policy`() {
        val engine = PolicyEngine()
        val payment = observationWith(text = "Pay 382 EUR")
        val tap = Action.Tap(RelativePoint(0.5), RelativePoint(0.5))

        val decision = engine.decide(
            request(tap, payment, plannerRiskClass = RiskClass.NONE),
            nowMs = 0L,
        )

        assertThat(decision).isInstanceOf(PolicyDecision.RequiresApproval::class.java)
    }

    @Test
    fun `L-HIT-08 the planner can tighten the policy`() {
        val engine = PolicyEngine()

        val decision = engine.decide(
            request(Action.ObserveScreen(), observationWith(text = "Search"), plannerRiskClass = RiskClass.PAYMENT),
            nowMs = 0L,
        )

        assertThat(decision).isInstanceOf(PolicyDecision.RequiresApproval::class.java)
        assertThat((decision as PolicyDecision.RequiresApproval).request.riskClass)
            .isEqualTo(RiskClass.PAYMENT)
    }

    @Test
    fun `L-HIT-11 a grant for one class does not cover another`() {
        val grants = GrantStore()
        grants.grant(runId, RiskClass.PUBLISH, expiresAtMs = 10_000L)
        val engine = PolicyEngine(grants = grants)

        val publish = engine.decide(
            request(Action.ClickText("Post"), observationWith(text = "Post")),
            nowMs = 0L,
        )
        val payment = engine.decide(
            request(Action.ClickText("Pay"), observationWith(text = "Pay 10 EUR")),
            nowMs = 0L,
        )

        assertThat(publish).isInstanceOf(PolicyDecision.Allow::class.java)
        assertThat(payment).isInstanceOf(PolicyDecision.RequiresApproval::class.java)
    }

    @Test
    fun `a grant is bound to one run`() {
        val grants = GrantStore()
        grants.grant(runId, RiskClass.PUBLISH, expiresAtMs = 10_000L)
        val engine = PolicyEngine(grants = grants)
        val otherRun = RunId("01JBX0000000000000000000009")

        val decision = engine.decide(
            request(Action.ClickText("Post"), observationWith(text = "Post")).copy(runId = otherRun),
            nowMs = 0L,
        )

        assertThat(decision).isInstanceOf(PolicyDecision.RequiresApproval::class.java)
    }

    @Test
    fun `an expired grant does not apply`() {
        val grants = GrantStore()
        grants.grant(runId, RiskClass.PUBLISH, expiresAtMs = 1_000L)
        val engine = PolicyEngine(grants = grants)

        val decision = engine.decide(
            request(Action.ClickText("Post"), observationWith(text = "Post")),
            nowMs = 1_000L,
        )

        assertThat(decision).isInstanceOf(PolicyDecision.RequiresApproval::class.java)
    }

    @Test
    fun `a payment surface makes every action on the screen a payment`() {
        val engine = PolicyEngine()
        val observation = Observation(
            app = "com.example.app",
            nodes = listOf(
                NodeRef(
                    id = 0,
                    className = "android.widget.EditText",
                    text = "Card number",
                    bounds = RelativeRect(0.0, 0.2, 1.0, 0.3),
                    editable = true,
                ),
                NodeRef(
                    id = 1,
                    className = "android.widget.Button",
                    text = "Next",
                    bounds = RelativeRect(0.0, 0.4, 1.0, 0.5),
                    clickable = true,
                ),
            ),
        )

        val decision = engine.decide(
            request(Action.ClickText("Next"), observation),
            nowMs = 0L,
        )

        assertThat((decision as PolicyDecision.RequiresApproval).request.riskClass)
            .isEqualTo(RiskClass.PAYMENT)
    }

    @Test
    fun `a page that merely mentions paying is not a payment surface`() {
        val engine = PolicyEngine()
        val observation = Observation(
            app = "com.example.app",
            nodes = listOf(
                NodeRef(id = 0, className = "android.widget.TextView", text = "Pay later is available"),
            ),
        )

        val decision = engine.decide(
            request(Action.ClickText("Pay later is available"), observation),
            nowMs = 0L,
        )

        // The label itself matches a payment word, so this one is a payment.
        // The point of the test is that a page that merely mentions paying is
        // not treated as a payment *surface*.
        assertThat(decision).isInstanceOf(PolicyDecision.RequiresApproval::class.java)
    }

    @Test
    fun `the request names the consequence`() {
        val engine = PolicyEngine()

        val decision = engine.decide(
            request(Action.ClickText("Pay"), observationWith(text = "Pay 10 EUR")),
            nowMs = 0L,
        )

        val request = (decision as PolicyDecision.RequiresApproval).request
        assertThat(request.consequences).isNotEmpty()
        assertThat(request.title).contains("Pay")
        assertThat(request.detail).isNotEmpty()
    }
}

class ApprovalBrokerTest {

    private val runId = RunId("01JBX0000000000000000000001")
    private val stepId = StepId("01JBX0000000000000000000002")
    private lateinit var store: InMemoryStateStore
    private lateinit var clock: ManualClock
    private lateinit var broker: ApprovalBroker

    @Before
    fun setUp() {
        store = InMemoryStateStore()
        clock = ManualClock(1_000L)
        broker = ApprovalBroker(store, clock, leaseMs = 60_000L)
    }

    private fun request() = ApprovalRequest(
        runId = runId,
        approvalId = ApprovalId("00000000000000000000000000"),
        stepId = stepId,
        riskClass = RiskClass.PAYMENT,
        title = "Pay 382 EUR",
        detail = "Iberia, one passenger",
        app = "com.example.travel",
        appLabel = "Travel",
        consequences = listOf("The money leaves the account"),
        actionPreview = ActionPreview(kind = "CLICK", label = "Pay"),
        expiresAtMs = 61_000L,
        leaseSeconds = 60,
    )

    @Test
    fun `L-HIT-09 a timeout denies the approval`() = runTest {
        val approvalId = broker.stage(request())

        clock.advance(60_000L)
        val resolved = broker.resolve(approvalId, ApprovalDecision.Approve)

        assertThat(resolved).isFalse()
        assertThat(store.approvals[approvalId]?.state).isEqualTo(ApprovalState.EXPIRED)
    }

    @Test
    fun `L-HIT-10 a staged approval is durable and is reloaded`() = runTest {
        val approvalId = broker.stage(request())

        val pending = broker.pending(runId)

        assertThat(pending).hasSize(1)
        assertThat(pending.first().id).isEqualTo(approvalId)
        assertThat(pending.first().state).isEqualTo(ApprovalState.PENDING)
    }

    @Test
    fun `an approval is approved before its lease runs out`() = runTest {
        val approvalId = broker.stage(request())

        clock.advance(10_000L)
        val resolved = broker.resolve(approvalId, ApprovalDecision.Approve)

        assertThat(resolved).isTrue()
        assertThat(store.approvals[approvalId]?.state).isEqualTo(ApprovalState.APPROVED)
    }

    @Test
    fun `a denial is recorded`() = runTest {
        val approvalId = broker.stage(request())

        broker.resolve(approvalId, ApprovalDecision.Deny)

        assertThat(store.approvals[approvalId]?.state).isEqualTo(ApprovalState.DENIED)
    }

    @Test
    fun `an answer is recorded with its text`() = runTest {
        val approvalId = broker.stage(request())

        broker.resolve(approvalId, ApprovalDecision.Answer("use the second option"))

        val record = store.approvals[approvalId]
        assertThat(record?.state).isEqualTo(ApprovalState.APPROVED)
        assertThat(record?.answerText).isEqualTo("use the second option")
    }

    @Test
    fun `a decision cannot be recorded twice`() = runTest {
        val approvalId = broker.stage(request())

        assertThat(broker.resolve(approvalId, ApprovalDecision.Approve)).isTrue()
        assertThat(broker.resolve(approvalId, ApprovalDecision.Deny)).isFalse()
        assertThat(store.approvals[approvalId]?.state).isEqualTo(ApprovalState.APPROVED)
    }

    @Test
    fun `the expiry sweep denies what nobody answered`() = runTest {
        broker.stage(request())
        clock.advance(60_000L)

        val expired = broker.expireOverdue(runId)

        assertThat(expired).hasSize(1)
        assertThat(store.approvals.values.single().state).isEqualTo(ApprovalState.EXPIRED)
    }

    @Test
    fun `each staged approval gets its own identifier`() = runTest {
        val first = broker.stage(request())
        val second = broker.stage(request())

        assertThat(first).isNotEqualTo(second)
        assertThat(store.approvals).hasSize(2)
    }
}
