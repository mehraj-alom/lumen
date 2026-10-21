package dev.lumen.platform.virtualdisplay

/**
 * What the agent knows before it chooses a surface.
 */
public data class SurfaceRequest(
    val packageName: String,
    val allowlist: Set<String>,
    val deviceIsIdle: Boolean,
    val idleWaitMs: Long,
    val virtualDisplayAvailable: Boolean,
    val targetSupportsSecondaryDisplay: Boolean,
    val foregroundPackage: String?,
)

/** The surface the agent will act on. */
public sealed interface SurfaceDecision {

    /** The app is launched onto a private display. The live screen is untouched. */
    public data class UseVirtualDisplay(val displayId: Int) : SurfaceDecision

    /** The app is driven on the live screen, because the device is idle. */
    public data class UseLiveSurface(val reason: String) : SurfaceDecision

    /** The step waits. The device is in use. */
    public data class Hold(val reason: String, val waitMs: Long) : SurfaceDecision

    /** The step is refused. */
    public data class Deny(val reason: String) : SurfaceDecision
}

/**
 * The surface selection policy.
 *
 * This is decision `D1`: a live surface is permitted **only** behind a
 * strict idle gate and a package allowlist, and a private virtual display is
 * used opportunistically when the target app supports a secondary display.
 *
 * The order matters. The allowlist is checked first, because no amount of
 * idleness makes an app the user has not allowed fair game. Then the
 * virtual display, because a private display never touches the live screen
 * at all. Then the idle gate, because a live surface is the last resort and
 * it needs the user to be away.
 */
public object SurfaceSelection {

    /**
     * The surface to use.
     *
     * @param virtualDisplayId the id of the private display, when one is
     *   available
     */
    public fun choose(request: SurfaceRequest, virtualDisplayId: Int? = null): SurfaceDecision {
        if (request.packageName !in request.allowlist) {
            return SurfaceDecision.Deny(
                "the package ${request.packageName} is not on the allowlist",
            )
        }

        val foreground = request.foregroundPackage
        if (foreground != null && foreground !in request.allowlist) {
            return SurfaceDecision.Deny(
                "the foreground package $foreground is not on the allowlist",
            )
        }

        if (request.virtualDisplayAvailable &&
            request.targetSupportsSecondaryDisplay &&
            virtualDisplayId != null
        ) {
            return SurfaceDecision.UseVirtualDisplay(virtualDisplayId)
        }

        if (!request.deviceIsIdle) {
            return SurfaceDecision.Hold(
                "the device is in use, so the live surface is not permitted",
                request.idleWaitMs,
            )
        }

        val reason = if (request.targetSupportsSecondaryDisplay) {
            "the app could not be moved to a private display, and the device is idle"
        } else {
            "the app does not support a secondary display, and the device is idle"
        }
        return SurfaceDecision.UseLiveSurface(reason)
    }
}
