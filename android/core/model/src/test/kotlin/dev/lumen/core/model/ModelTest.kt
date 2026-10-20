package dev.lumen.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AssertionsTest {

    private val observation = Observation(
        app = "com.example.browser",
        nodes = listOf(
            NodeRef(id = 0, className = "android.widget.FrameLayout", packageName = "com.example.browser"),
            NodeRef(
                id = 1,
                parentId = 0,
                className = "android.widget.TextView",
                text = "Total price 382 EUR",
                packageName = "com.example.browser",
            ),
            NodeRef(
                id = 2,
                parentId = 0,
                className = "android.widget.Button",
                contentDescription = "Pay now",
                packageName = "com.example.browser",
                clickable = true,
            ),
        ),
        overlays = listOf(OverlayInfo(packageName = "com.android.systemui", type = "TYPE_APPLICATION_OVERLAY")),
    )

    @Test
    fun `an app assertion holds for the foreground app`() {
        assertThat(Assertions.holds(Assertion.AppIs("com.example.browser"), observation)).isTrue()
        assertThat(Assertions.holds(Assertion.AppIs("com.other.app"), observation)).isFalse()
    }

    @Test
    fun `a text assertion matches the visible text`() {
        assertThat(Assertions.holds(Assertion.TextPresent("Total price"), observation)).isTrue()
        assertThat(Assertions.holds(Assertion.TextPresent("total PRICE"), observation)).isTrue()
        assertThat(Assertions.holds(Assertion.TextPresent("Refund"), observation)).isFalse()
    }

    @Test
    fun `a text assertion matches the content description`() {
        assertThat(Assertions.holds(Assertion.TextPresent("Pay now"), observation)).isTrue()
    }

    @Test
    fun `an absent-text assertion is the inverse`() {
        assertThat(Assertions.holds(Assertion.TextAbsent("Refund"), observation)).isTrue()
        assertThat(Assertions.holds(Assertion.TextAbsent("Pay now"), observation)).isFalse()
    }

    @Test
    fun `a node count assertion holds at the boundary`() {
        assertThat(Assertions.holds(Assertion.NodeCountAtLeast(3), observation)).isTrue()
        assertThat(Assertions.holds(Assertion.NodeCountAtLeast(4), observation)).isFalse()
    }

    @Test
    fun `an overlay assertion can be narrowed to a package`() {
        assertThat(Assertions.holds(Assertion.HasOverlay(), observation)).isTrue()
        assertThat(Assertions.holds(Assertion.HasOverlay("com.android.systemui"), observation)).isTrue()
        assertThat(Assertions.holds(Assertion.HasOverlay("com.other.app"), observation)).isFalse()
    }

    @Test
    fun `an any-of assertion holds when one alternative holds`() {
        val assertion = Assertion.AnyOf(
            listOf(
                Assertion.AppIs("com.other.app"),
                Assertion.TextPresent("Total price"),
            ),
        )

        assertThat(Assertions.holds(assertion, observation)).isTrue()
    }

    @Test
    fun `every assertion in a list must hold`() {
        assertThat(
            Assertions.allHold(
                listOf(Assertion.AppIs("com.example.browser"), Assertion.TextPresent("Total price")),
                observation,
            ),
        ).isTrue()
        assertThat(
            Assertions.allHold(
                listOf(Assertion.AppIs("com.example.browser"), Assertion.TextPresent("Refund")),
                observation,
            ),
        ).isFalse()
    }
}

class ReasonTest {

    @Test
    fun `a blank reason is rejected`() {
        assertThat(runCatching { Reason(ReasonSource.PLANNER, "  ") }.exceptionOrNull())
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `a reason records its source`() {
        assertThat(Reason.user("the user asked for it").source).isEqualTo(ReasonSource.USER)
        assertThat(Reason.policy("payment detected").source).isEqualTo(ReasonSource.POLICY)
    }
}

class RiskClassTest {

    @Test
    fun `every class except none requires an approval`() {
        RiskClass.entries.forEach { riskClass ->
            assertThat(riskClass.requiresApproval).isEqualTo(riskClass != RiskClass.NONE)
        }
    }

    @Test
    fun `a risk class has a human readable label`() {
        RiskClass.entries.forEach { riskClass ->
            assertThat(riskClass.label).isNotEmpty()
        }
    }
}

class GoalConstraintsTest {

    @Test
    fun `the soft step limit cannot exceed the hard limit`() {
        assertThat(
            runCatching { GoalConstraints(stepsSoftLimit = 500, stepsHardLimit = 400) }.exceptionOrNull(),
        ).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `a negative cost limit is rejected`() {
        assertThat(runCatching { GoalConstraints(costLimitMicros = -1L) }.exceptionOrNull())
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `the default constraints match the specification`() {
        val constraints = GoalConstraints()

        assertThat(constraints.stepsSoftLimit).isEqualTo(150)
        assertThat(constraints.stepsHardLimit).isEqualTo(400)
        assertThat(constraints.wallClockLimitMs).isEqualTo(45L * 60L * 1000L)
        assertThat(constraints.costLimitMicros).isEqualTo(0L)
        assertThat(constraints.allowedPackages).isEmpty()
        assertThat(constraints.brainPin).isEqualTo(BrainPin.AUTO)
    }
}
