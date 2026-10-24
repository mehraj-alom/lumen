package dev.lumen.core.brain

import com.google.common.truth.Truth.assertThat
import dev.lumen.core.brain.fixtures.FakeBrain
import dev.lumen.core.model.BrainPin
import dev.lumen.core.model.ErrorCode
import dev.lumen.core.model.Goal
import dev.lumen.core.model.RunId
import kotlinx.coroutines.test.runTest
import org.junit.Test

class BrainRouterTest {

    private val goal = Goal(id = RunId("01JBX000000000000000000001"), text = "do the thing")

    private fun request() = PlanRequest(goal = goal)

    @Test
    fun `the automatic route uses the local brain when a model fits the budget`() = runTest {
        val local = FakeBrain(BrainRoute.LOCAL)
        val cloud = FakeBrain(BrainRoute.CLOUD)
        val router = BrainRouter(
            local = local,
            cloud = cloud,
            pin = BrainPin.AUTO,
            localGate = LocalModelGate { true },
        )

        router.plan(request())

        assertThat(local.planCalls).isEqualTo(1)
        assertThat(cloud.planCalls).isEqualTo(0)
        assertThat(router.lastRoute).isEqualTo(BrainRoute.LOCAL)
    }

    @Test
    fun `the automatic route uses the cloud when no local model fits the budget`() = runTest {
        val local = FakeBrain(BrainRoute.LOCAL)
        val cloud = FakeBrain(BrainRoute.CLOUD)
        val router = BrainRouter(
            local = local,
            cloud = cloud,
            pin = BrainPin.AUTO,
            localGate = LocalModelGate { false },
        )

        router.plan(request())

        assertThat(cloud.planCalls).isEqualTo(1)
        assertThat(local.planCalls).isEqualTo(0)
        assertThat(router.lastRoute).isEqualTo(BrainRoute.CLOUD)
    }

    @Test
    fun `the automatic route uses the cloud when no local model is installed`() = runTest {
        val cloud = FakeBrain(BrainRoute.CLOUD)
        val router = BrainRouter(
            local = null,
            cloud = cloud,
            pin = BrainPin.AUTO,
            localGate = LocalModelGate { true },
        )

        router.plan(request())

        assertThat(cloud.planCalls).isEqualTo(1)
    }

    @Test
    fun `a cloud pin is never overridden by a local model`() = runTest {
        val local = FakeBrain(BrainRoute.LOCAL)
        val cloud = FakeBrain(BrainRoute.CLOUD)
        val router = BrainRouter(
            local = local,
            cloud = cloud,
            pin = BrainPin.CLOUD,
            localGate = LocalModelGate { true },
        )

        router.plan(request())

        assertThat(cloud.planCalls).isEqualTo(1)
        assertThat(local.planCalls).isEqualTo(0)
    }

    @Test
    fun `a local pin is never downgraded to the cloud`() = runTest {
        val local = FakeBrain(BrainRoute.LOCAL)
        val cloud = FakeBrain(BrainRoute.CLOUD)
        val router = BrainRouter(
            local = local,
            cloud = cloud,
            pin = BrainPin.LOCAL,
            localGate = LocalModelGate { false },
        )

        router.plan(request())

        assertThat(local.planCalls).isEqualTo(1)
        assertThat(cloud.planCalls).isEqualTo(0)
    }

    @Test
    fun `a local pin with no local model fails rather than using the cloud`() = runTest {
        val cloud = FakeBrain(BrainRoute.CLOUD)
        val router = BrainRouter(
            local = null,
            cloud = cloud,
            pin = BrainPin.LOCAL,
        )

        val failure = runCatching { router.plan(request()) }.exceptionOrNull()

        assertThat(failure).isInstanceOf(BrainUnavailable::class.java)
        assertThat((failure as BrainUnavailable).error.code).isEqualTo(ErrorCode.BRAIN_UNAVAILABLE)
        assertThat(cloud.planCalls).isEqualTo(0)
    }

    @Test
    fun `the router routes the replan and the critique as well`() = runTest {
        val local = FakeBrain(BrainRoute.LOCAL)
        val cloud = FakeBrain(BrainRoute.CLOUD)
        val router = BrainRouter(local, cloud, BrainPin.AUTO, LocalModelGate { true })

        router.replan(
            ReplanRequest(
                goal = goal,
                failedStep = dev.lumen.core.model.Step(
                    id = dev.lumen.core.model.StepId("01JBX000000000000000000002"),
                    planVersion = 1,
                    phase = "research",
                    intent = "open the results",
                    reason = dev.lumen.core.model.Reason.planner("because"),
                    action = dev.lumen.core.model.Action.PressEnter,
                ),
                observation = null,
                previousPlanVersion = 1,
            ),
        )
        router.critique(
            CritiqueRequest(
                goal = goal,
                step = dev.lumen.core.model.Step(
                    id = dev.lumen.core.model.StepId("01JBX000000000000000000002"),
                    planVersion = 1,
                    phase = "research",
                    intent = "open the results",
                    reason = dev.lumen.core.model.Reason.planner("because"),
                    action = dev.lumen.core.model.Action.PressEnter,
                ),
                observation = null,
            ),
        )

        assertThat(local.replanCalls).isEqualTo(1)
        assertThat(local.critiqueCalls).isEqualTo(1)
        assertThat(cloud.replanCalls).isEqualTo(0)
    }
}

class ToolSchemaTest {

    @Test
    fun `every action in the model has a tool in the schema`() {
        val actions = listOf(
            dev.lumen.core.model.Action.Tap(
                dev.lumen.core.model.RelativePoint(0.1),
                dev.lumen.core.model.RelativePoint(0.2),
            ),
            dev.lumen.core.model.Action.PressEnter,
            dev.lumen.core.model.Action.ListApps,
            dev.lumen.core.model.Action.ObserveScreen(),
            dev.lumen.core.model.Action.ReadFile("a.txt"),
        )

        actions.forEach { action ->
            val (tool, _) = ActionCodec.encode(action)
            assertThat(ToolSchema.definition(tool)).isNotNull()
        }
    }

    @Test
    fun `an unknown tool is refused`() {
        val error = ToolSchema.validate("launch_missiles", kotlinx.serialization.json.JsonObject(emptyMap()))

        assertThat(error).isNotNull()
        assertThat(error?.code).isEqualTo(ErrorCode.DENIED_BY_POLICY)
    }

    @Test
    fun `a missing required argument is refused`() {
        val error = ToolSchema.validate(
            "input_text",
            kotlinx.serialization.json.JsonObject(emptyMap()),
        )

        assertThat(error).isNotNull()
        assertThat(error?.message).contains("text")
    }

    @Test
    fun `an unknown argument is refused`() {
        val error = ToolSchema.validate(
            "press_enter",
            kotlinx.serialization.json.JsonObject(mapOf("surprise" to kotlinx.serialization.json.JsonPrimitive(1))),
        )

        assertThat(error).isNotNull()
    }

    @Test
    fun `a valid call passes`() {
        val error = ToolSchema.validate(
            "click_text",
            kotlinx.serialization.json.JsonObject(
                mapOf("text" to kotlinx.serialization.json.JsonPrimitive("Pay")),
            ),
        )

        assertThat(error).isNull()
    }

    @Test
    fun `an action round trips through the codec`() {
        val action = dev.lumen.core.model.Action.ClickText("Pay", 2)
        val (tool, args) = ActionCodec.encode(action)

        val decoded = ActionCodec.decode(tool, args)

        assertThat(decoded).isEqualTo(action)
    }

    @Test
    fun `a coordinate outside the unit range is not decoded`() {
        val decoded = ActionCodec.decode(
            "tap",
            kotlinx.serialization.json.JsonObject(
                mapOf(
                    "x" to kotlinx.serialization.json.JsonPrimitive(1.5),
                    "y" to kotlinx.serialization.json.JsonPrimitive(0.5),
                ),
            ),
        )

        assertThat(decoded).isNull()
    }
}
