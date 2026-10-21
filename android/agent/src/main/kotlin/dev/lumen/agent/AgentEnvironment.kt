package dev.lumen.agent

import dev.lumen.core.agent.StepEnvironment
import dev.lumen.core.model.Action
import dev.lumen.core.model.ActionResult
import dev.lumen.core.model.Observation
import dev.lumen.platform.accessibility.AccessibilityBridgeClient
import dev.lumen.platform.accessibility.UiEventTracker
import dev.lumen.platform.idle.IdleGate
import dev.lumen.platform.virtualdisplay.SurfaceDecision
import dev.lumen.platform.virtualdisplay.SurfaceRequest
import dev.lumen.platform.virtualdisplay.SurfaceSelection
import dev.lumen.platform.virtualdisplay.VirtualDisplayManager

/**
 * The device the agent acts on.
 *
 * Every action goes through the surface policy first, so the agent never
 * draws on a screen the user is using, and never acts on an app that is
 * not on the allowlist.
 */
public class AgentEnvironment(
    private val bridge: AccessibilityBridgeClient,
    private val idleGate: IdleGate,
    private val virtualDisplay: VirtualDisplayManager,
    private val eventTracker: UiEventTracker,
    private val allowlist: () -> Set<String> = { emptySet() },
) : StepEnvironment {

    override suspend fun observe(): Observation? = bridge.readTree()

    override suspend fun perform(action: Action): ActionResult {
        val decision = SurfaceSelection.choose(
            request = SurfaceRequest(
                packageName = foregroundPackage() ?: "",
                allowlist = allowlist(),
                deviceIsIdle = idleGate.permitsLiveSurface().permitted,
                idleWaitMs = idleGate.waitMs(),
                virtualDisplayAvailable = virtualDisplay.openDisplayCount() > 0,
                targetSupportsSecondaryDisplay = false,
                foregroundPackage = foregroundPackage(),
            ),
            virtualDisplayId = null,
        )

        return when (decision) {
            is SurfaceDecision.Deny -> ActionResult.failed(
                action = action,
                code = dev.lumen.core.model.ErrorCode.DENIED_BY_POLICY,
                message = decision.reason,
            )

            is SurfaceDecision.Hold -> ActionResult.failed(
                action = action,
                code = dev.lumen.core.model.ErrorCode.IDLE_GATE_BLOCKED,
                message = decision.reason,
            )

            is SurfaceDecision.UseVirtualDisplay -> ActionResult.failed(
                action = action,
                code = dev.lumen.core.model.ErrorCode.INTERNAL,
                message = "the action was routed to a private display, which the bridge does not drive yet",
            )

            is SurfaceDecision.UseLiveSurface -> {
                val performed = bridge.perform(action)
                if (performed) {
                    ActionResult.ok(action, observe())
                } else {
                    ActionResult.failed(
                        action = action,
                        code = dev.lumen.core.model.ErrorCode.INTERNAL,
                        message = "the action could not be dispatched",
                    )
                }
            }
        }
    }

    override suspend fun foregroundPackage(): String? = bridge.foregroundPackage()

    /** The event clock the settle detector consumes. */
    public fun uiEventClock(): UiEventTracker = eventTracker
}
